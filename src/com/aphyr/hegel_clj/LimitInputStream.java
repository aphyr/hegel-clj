// Wraps an InputStream with one that only returns n bytes, then returns EOF.
// We use this to feed the CBOR decoder payloads. We also update a CRC32 as we
// go. Not thread-safe.

package com.aphyr.hegel_clj;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.CRC32;

public class LimitInputStream extends InputStream {
  private final long limit;
  private long consumed = 0;
  private final InputStream source;
  private final CRC32 crc;

  public LimitInputStream(long limit, InputStream source, CRC32 crc) {
    super();
    this.limit = limit;
    this.source = source;
    this.crc = crc;
  }

  public long consumed() {
    return consumed;
  }

  public int read() throws IOException {
    // System.out.println("read() " + consumed + "/" + limit);
    if (consumed < limit) {
      final int b = source.read();
      if (-1 == b) {
        return -1;
      }
      consumed += 1;
      crc.update(b);
      return b;
    } else {
      return -1;
    }
  }

  public int read(byte[] b) throws IOException {
    // System.out.println("read(byte[" + b.length + "]) " + consumed + "/" + limit);
    final long remaining = limit - consumed;
    if (remaining == 0) {
      return -1;
    }

    final int consumable;
    if (remaining < b.length) {
      consumable = (int) remaining;
    } else {
      consumable = b.length;
    }

    final int read_bytes = source.read(b, 0, consumable);
    if (0 < read_bytes) {
      consumed = consumed + read_bytes;
      crc.update(b, 0, read_bytes);
    }
    return read_bytes;
  }

  public int read(byte[] b, int off, int len) throws IOException {
    // System.out.println("read(byte[" + b.length + "], " + off + ", " + len + ") " + consumed + "/" + limit);
    final long remaining = limit - consumed;
    if (remaining == 0) {
      return -1;
    }
    final int consumable;
    if (remaining < len) {
      consumable = (int) remaining;
    } else {
      consumable = len;
    }

    final int read_bytes = source.read(b, off, consumable);
    if (0 < read_bytes) {
      consumed = consumed + read_bytes;
      crc.update(b, off, read_bytes);
    }
    return read_bytes;
  }

  public long skip(long n) throws IOException {
    // In order to update the CRC, we do a read and discard it.
    if (Integer.MAX_VALUE < n) {
      return read(new byte[(int) n]);
    } else {
      return read(new byte[Integer.MAX_VALUE]);
    }
  }

  public int available() throws IOException {
    final long remaining = limit - consumed;
    final int source_available = source.available();
    if (remaining < source_available) {
      return (int) remaining;
    } else {
      return source_available;
    }
  }

  // We don't want to close the underlying stream!
  public void close() {
  }

  public void mark(int readlimit) {
    System.out.println("Agh");
    throw new IllegalStateException("Not supported");
  }

  public void reset() {
    System.out.println("Agh");
    throw new IllegalStateException("Not supported");
  }

  public boolean markSupported() {
    return false;
  }
}
