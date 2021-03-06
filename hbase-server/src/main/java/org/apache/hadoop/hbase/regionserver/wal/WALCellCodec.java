/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.regionserver.wal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.codec.BaseDecoder;
import org.apache.hadoop.hbase.codec.BaseEncoder;
import org.apache.hadoop.hbase.codec.Codec;
import org.apache.hadoop.hbase.codec.KeyValueCodec;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.ReflectionUtils;
import org.apache.hadoop.io.IOUtils;

import com.google.common.base.Preconditions;
import com.google.protobuf.ByteString;


/**
 * Compression in this class is lifted off Compressor/KeyValueCompression.
 * This is a pure coincidence... they are independent and don't have to be compatible.
 */
public class WALCellCodec implements Codec {
  /** Configuration key for the class to use when encoding cells in the WAL */
  public static final String WAL_CELL_CODEC_CLASS_KEY = "hbase.regionserver.wal.codec";

  private final CompressionContext compression;
  private final ByteStringUncompressor statelessUncompressor = new ByteStringUncompressor() {
    @Override
    public byte[] uncompress(ByteString data, Dictionary dict) throws IOException {
      return WALCellCodec.uncompressByteString(data, dict);
    }
  };

  /**
   * Default constructor - <b>all subclasses must implement a constructor with this signature </b>
   * if they are to be dynamically loaded from the {@link Configuration}.
   * @param conf configuration to configure <tt>this</tt>
   * @param compression compression the codec should support, can be <tt>null</tt> to indicate no
   *          compression
   */
  public WALCellCodec(Configuration conf, CompressionContext compression) {
    this.compression = compression;
  }

  /**
   * Create and setup a {@link WALCellCodec} from the {@link Configuration} and CompressionContext,
   * if they have been specified. Fully prepares the codec for use.
   * @param conf {@link Configuration} to read for the user-specified codec. If none is specified,
   *          uses a {@link WALCellCodec}.
   * @param compression compression the codec should use
   * @return a {@link WALCellCodec} ready for use.
   * @throws UnsupportedOperationException if the codec cannot be instantiated
   */
  public static WALCellCodec create(Configuration conf, CompressionContext compression)
      throws UnsupportedOperationException {
    String className = conf.get(WAL_CELL_CODEC_CLASS_KEY, WALCellCodec.class.getName());
    return ReflectionUtils.instantiateWithCustomCtor(className, new Class[] { Configuration.class,
        CompressionContext.class }, new Object[] { conf, compression });
  }

  public interface ByteStringCompressor {
    ByteString compress(byte[] data, Dictionary dict) throws IOException;
  }

  public interface ByteStringUncompressor {
    byte[] uncompress(ByteString data, Dictionary dict) throws IOException;
  }

  // TODO: it sucks that compression context is in HLog.Entry. It'd be nice if it was here.
  //       Dictionary could be gotten by enum; initially, based on enum, context would create
  //       an array of dictionaries.
  static class BaosAndCompressor extends ByteArrayOutputStream implements ByteStringCompressor {
    public ByteString toByteString() {
      return ByteString.copyFrom(this.buf, 0, this.count);
    }

    @Override
    public ByteString compress(byte[] data, Dictionary dict) throws IOException {
      writeCompressed(data, dict);
      ByteString result = ByteString.copyFrom(this.buf, 0, this.count);
      reset(); // Only resets the count - we reuse the byte array.
      return result;
    }

    private void writeCompressed(byte[] data, Dictionary dict) throws IOException {
      assert dict != null;
      short dictIdx = dict.findEntry(data, 0, data.length);
      if (dictIdx == Dictionary.NOT_IN_DICTIONARY) {
        write(Dictionary.NOT_IN_DICTIONARY);
        StreamUtils.writeRawVInt32(this, data.length);
        write(data, 0, data.length);
      } else {
        StreamUtils.writeShort(this, dictIdx);
      }
    }
  }

  private static byte[] uncompressByteString(ByteString bs, Dictionary dict) throws IOException {
    InputStream in = bs.newInput();
    byte status = (byte)in.read();
    if (status == Dictionary.NOT_IN_DICTIONARY) {
      byte[] arr = new byte[StreamUtils.readRawVarint32(in)];
      int bytesRead = in.read(arr);
      if (bytesRead != arr.length) {
        throw new IOException("Cannot read; wanted " + arr.length + ", but got " + bytesRead);
      }
      if (dict != null) dict.addEntry(arr, 0, arr.length);
      return arr;
    } else {
      // Status here is the higher-order byte of index of the dictionary entry.
      short dictIdx = StreamUtils.toShort(status, (byte)in.read());
      byte[] entry = dict.getEntry(dictIdx);
      if (entry == null) {
        throw new IOException("Missing dictionary entry for index " + dictIdx);
      }
      return entry;
    }
  }

  static class CompressedKvEncoder extends BaseEncoder {
    private final CompressionContext compression;
    public CompressedKvEncoder(OutputStream out, CompressionContext compression) {
      super(out);
      this.compression = compression;
    }

    @Override
    public void write(Cell cell) throws IOException {
      if (!(cell instanceof KeyValue)) throw new IOException("Cannot write non-KV cells to WAL");
      KeyValue kv = (KeyValue)cell;
      byte[] kvBuffer = kv.getBuffer();
      int offset = kv.getOffset();

      // We first write the KeyValue infrastructure as VInts.
      StreamUtils.writeRawVInt32(out, kv.getKeyLength());
      StreamUtils.writeRawVInt32(out, kv.getValueLength());
      // To support tags. This will be replaced with kv.getTagsLength
      StreamUtils.writeRawVInt32(out, (short)0);

      // Write row, qualifier, and family; use dictionary
      // compression as they're likely to have duplicates.
      write(kvBuffer, kv.getRowOffset(), kv.getRowLength(), compression.rowDict);
      write(kvBuffer, kv.getFamilyOffset(), kv.getFamilyLength(), compression.familyDict);
      write(kvBuffer, kv.getQualifierOffset(), kv.getQualifierLength(), compression.qualifierDict);

      // Write the rest uncompressed.
      int pos = kv.getTimestampOffset();
      int remainingLength = kv.getLength() + offset - pos;
      out.write(kvBuffer, pos, remainingLength);

    }

    private void write(byte[] data, int offset, int length, Dictionary dict) throws IOException {
      short dictIdx = Dictionary.NOT_IN_DICTIONARY;
      if (dict != null) {
        dictIdx = dict.findEntry(data, offset, length);
      }
      if (dictIdx == Dictionary.NOT_IN_DICTIONARY) {
        out.write(Dictionary.NOT_IN_DICTIONARY);
        StreamUtils.writeRawVInt32(out, length);
        out.write(data, offset, length);
      } else {
        StreamUtils.writeShort(out, dictIdx);
      }
    }
  }

  static class CompressedKvDecoder extends BaseDecoder {
    private final CompressionContext compression;
    public CompressedKvDecoder(InputStream in, CompressionContext compression) {
      super(in);
      this.compression = compression;
    }

    @Override
    protected Cell parseCell() throws IOException {
      int keylength = StreamUtils.readRawVarint32(in);
      int vlength = StreamUtils.readRawVarint32(in);
      
      // To support Tags..Tags length will be 0.
      // For now ignore the read value. This will be the tagslength
      StreamUtils.readRawVarint32(in);
      int length = KeyValue.KEYVALUE_INFRASTRUCTURE_SIZE + keylength + vlength;

      byte[] backingArray = new byte[length];
      int pos = 0;
      pos = Bytes.putInt(backingArray, pos, keylength);
      pos = Bytes.putInt(backingArray, pos, vlength);

      // the row
      int elemLen = readIntoArray(backingArray, pos + Bytes.SIZEOF_SHORT, compression.rowDict);
      checkLength(elemLen, Short.MAX_VALUE);
      pos = Bytes.putShort(backingArray, pos, (short)elemLen);
      pos += elemLen;

      // family
      elemLen = readIntoArray(backingArray, pos + Bytes.SIZEOF_BYTE, compression.familyDict);
      checkLength(elemLen, Byte.MAX_VALUE);
      pos = Bytes.putByte(backingArray, pos, (byte)elemLen);
      pos += elemLen;

      // qualifier
      elemLen = readIntoArray(backingArray, pos, compression.qualifierDict);
      pos += elemLen;

      // the rest
      IOUtils.readFully(in, backingArray, pos, length - pos);
      return new KeyValue(backingArray, 0, length);
    }

    private int readIntoArray(byte[] to, int offset, Dictionary dict) throws IOException {
      byte status = (byte)in.read();
      if (status == Dictionary.NOT_IN_DICTIONARY) {
        // status byte indicating that data to be read is not in dictionary.
        // if this isn't in the dictionary, we need to add to the dictionary.
        int length = StreamUtils.readRawVarint32(in);
        IOUtils.readFully(in, to, offset, length);
        dict.addEntry(to, offset, length);
        return length;
      } else {
        // the status byte also acts as the higher order byte of the dictionary entry.
        short dictIdx = StreamUtils.toShort(status, (byte)in.read());
        byte[] entry = dict.getEntry(dictIdx);
        if (entry == null) {
          throw new IOException("Missing dictionary entry for index " + dictIdx);
        }
        // now we write the uncompressed value.
        Bytes.putBytes(to, offset, entry, 0, entry.length);
        return entry.length;
      }
    }

    private static void checkLength(int len, int max) throws IOException {
      if (len < 0 || len > max) {
        throw new IOException("Invalid length for compresesed portion of keyvalue: " + len);
      }
    }
  }

  public class EnsureKvEncoder extends KeyValueCodec.KeyValueEncoder {
    public EnsureKvEncoder(OutputStream out) {
      super(out);
    }
    @Override
    public void write(Cell cell) throws IOException {
      if (!(cell instanceof KeyValue)) throw new IOException("Cannot write non-KV cells to WAL");
      super.write(cell);
    }
  }

  @Override
  public Decoder getDecoder(InputStream is) {
    return (compression == null)
        ? new KeyValueCodec.KeyValueDecoder(is) : new CompressedKvDecoder(is, compression);
  }

  @Override
  public Encoder getEncoder(OutputStream os) {
    return (compression == null)
        ? new EnsureKvEncoder(os) : new CompressedKvEncoder(os, compression);
  }

  public ByteStringCompressor getByteStringCompressor() {
    // TODO: ideally this should also encapsulate compressionContext
    return new BaosAndCompressor();
  }

  public ByteStringUncompressor getByteStringUncompressor() {
    // TODO: ideally this should also encapsulate compressionContext
    return this.statelessUncompressor;
  }

  /**
   * It seems like as soon as somebody sets himself to the task of creating VInt encoding,
   * his mind blanks out for a split-second and he starts the work by wrapping it in the
   * most convoluted interface he can come up with. Custom streams that allocate memory,
   * DataOutput that is only used to write single bytes... We operate on simple streams.
   * Thus, we are going to have a simple implementation copy-pasted from protobuf Coded*Stream.
   */
  private static class StreamUtils {
    public static int computeRawVarint32Size(final int value) {
      if ((value & (0xffffffff <<  7)) == 0) return 1;
      if ((value & (0xffffffff << 14)) == 0) return 2;
      if ((value & (0xffffffff << 21)) == 0) return 3;
      if ((value & (0xffffffff << 28)) == 0) return 4;
      return 5;
    }

    static void writeRawVInt32(OutputStream output, int value) throws IOException {
      assert value >= 0;
      while (true) {
        if ((value & ~0x7F) == 0) {
          output.write(value);
          return;
        } else {
          output.write((value & 0x7F) | 0x80);
          value >>>= 7;
        }
      }
    }

    static int readRawVarint32(InputStream input) throws IOException {
      byte tmp = (byte)input.read();
      if (tmp >= 0) {
        return tmp;
      }
      int result = tmp & 0x7f;
      if ((tmp = (byte)input.read()) >= 0) {
        result |= tmp << 7;
      } else {
        result |= (tmp & 0x7f) << 7;
        if ((tmp = (byte)input.read()) >= 0) {
          result |= tmp << 14;
        } else {
          result |= (tmp & 0x7f) << 14;
          if ((tmp = (byte)input.read()) >= 0) {
            result |= tmp << 21;
          } else {
            result |= (tmp & 0x7f) << 21;
            result |= (tmp = (byte)input.read()) << 28;
            if (tmp < 0) {
              // Discard upper 32 bits.
              for (int i = 0; i < 5; i++) {
                if (input.read() >= 0) {
                  return result;
                }
              }
              throw new IOException("Malformed varint");
            }
          }
        }
      }
      return result;
    }

    static short toShort(byte hi, byte lo) {
      short s = (short) (((hi & 0xFF) << 8) | (lo & 0xFF));
      Preconditions.checkArgument(s >= 0);
      return s;
    }

    static void writeShort(OutputStream out, short v) throws IOException {
      Preconditions.checkArgument(v >= 0);
      out.write((byte)(0xff & (v >> 8)));
      out.write((byte)(0xff & v));
    }
  }
}
