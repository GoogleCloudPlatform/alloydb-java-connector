/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.alloydb;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketImpl;
import java.nio.channels.SocketChannel;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/**
 * A Socket wrapper that counts the bytes sent and received on the connection and records the
 * connection as closed when it is closed.
 *
 * <p>Every method of {@link Socket} is delegated. The superclass is deliberately constructed with a
 * null {@link SocketImpl} so that no real socket is allocated behind this one; any method that is
 * not delegated would operate on that empty superclass and silently return the wrong answer.
 *
 * <p>Byte counts are accumulated locally and flushed to the {@link MetricRecorder} in batches.
 * Recording every read and write directly would put an attribute-set lookup and an allocation on
 * the socket's hot path, which for a byte-at-a-time caller would mean one metric update per byte.
 */
class InstrumentedSocket extends Socket {

  /**
   * How often the accumulated counts are flushed to the recorder. The Go connector runs a 5s
   * reporting ticker per connection; a thread per connection would be far too expensive here, so
   * the interval is checked on the I/O path instead.
   */
  private static final long FLUSH_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(5);

  private final Socket delegate;
  private final MetricRecorder metricRecorder;
  private final TelemetryAttributes attrs;
  private final ByteCounter rx;
  private final ByteCounter tx;

  private InputStream inputStream;
  private OutputStream outputStream;
  private boolean closed;

  InstrumentedSocket(Socket delegate, MetricRecorder metricRecorder, TelemetryAttributes attrs)
      throws SocketException {
    super((SocketImpl) null);
    this.delegate = delegate;
    this.metricRecorder = metricRecorder;
    this.attrs = attrs;
    this.rx = new ByteCounter(metricRecorder::recordBytesRx);
    this.tx = new ByteCounter(metricRecorder::recordBytesTx);
  }

  @Override
  public synchronized InputStream getInputStream() throws IOException {
    // Socket.getInputStream() is expected to return the same stream on every call.
    if (inputStream == null) {
      inputStream = new CountingInputStream(delegate.getInputStream(), rx);
    }
    return inputStream;
  }

  @Override
  public synchronized OutputStream getOutputStream() throws IOException {
    if (outputStream == null) {
      outputStream = new CountingOutputStream(delegate.getOutputStream(), tx);
    }
    return outputStream;
  }

  @Override
  public synchronized void close() throws IOException {
    boolean firstClose = !closed;
    closed = true;
    try {
      delegate.close();
    } finally {
      if (firstClose) {
        rx.flush();
        tx.flush();
        metricRecorder.recordClosedConnection(attrs);
      }
    }
  }

  /*
   * Everything below simply delegates. Socket has no delegating implementation of its own, so a
   * method left out here would run against the null-impl superclass instead of the real socket.
   */

  @Override
  public void connect(SocketAddress endpoint) throws IOException {
    delegate.connect(endpoint);
  }

  @Override
  public void connect(SocketAddress endpoint, int timeout) throws IOException {
    delegate.connect(endpoint, timeout);
  }

  @Override
  public void bind(SocketAddress bindpoint) throws IOException {
    delegate.bind(bindpoint);
  }

  @Override
  public InetAddress getInetAddress() {
    return delegate.getInetAddress();
  }

  @Override
  public InetAddress getLocalAddress() {
    return delegate.getLocalAddress();
  }

  @Override
  public int getPort() {
    return delegate.getPort();
  }

  @Override
  public int getLocalPort() {
    return delegate.getLocalPort();
  }

  @Override
  public SocketAddress getRemoteSocketAddress() {
    return delegate.getRemoteSocketAddress();
  }

  @Override
  public SocketAddress getLocalSocketAddress() {
    return delegate.getLocalSocketAddress();
  }

  @Override
  public SocketChannel getChannel() {
    return delegate.getChannel();
  }

  @Override
  public void setTcpNoDelay(boolean on) throws SocketException {
    delegate.setTcpNoDelay(on);
  }

  @Override
  public boolean getTcpNoDelay() throws SocketException {
    return delegate.getTcpNoDelay();
  }

  @Override
  public void setSoLinger(boolean on, int linger) throws SocketException {
    delegate.setSoLinger(on, linger);
  }

  @Override
  public int getSoLinger() throws SocketException {
    return delegate.getSoLinger();
  }

  @Override
  public void sendUrgentData(int data) throws IOException {
    delegate.sendUrgentData(data);
  }

  @Override
  public void setOOBInline(boolean on) throws SocketException {
    delegate.setOOBInline(on);
  }

  @Override
  public boolean getOOBInline() throws SocketException {
    return delegate.getOOBInline();
  }

  @Override
  public synchronized void setSoTimeout(int timeout) throws SocketException {
    delegate.setSoTimeout(timeout);
  }

  @Override
  public synchronized int getSoTimeout() throws SocketException {
    return delegate.getSoTimeout();
  }

  @Override
  public synchronized void setSendBufferSize(int size) throws SocketException {
    delegate.setSendBufferSize(size);
  }

  @Override
  public synchronized int getSendBufferSize() throws SocketException {
    return delegate.getSendBufferSize();
  }

  @Override
  public synchronized void setReceiveBufferSize(int size) throws SocketException {
    delegate.setReceiveBufferSize(size);
  }

  @Override
  public synchronized int getReceiveBufferSize() throws SocketException {
    return delegate.getReceiveBufferSize();
  }

  @Override
  public void setKeepAlive(boolean on) throws SocketException {
    delegate.setKeepAlive(on);
  }

  @Override
  public boolean getKeepAlive() throws SocketException {
    return delegate.getKeepAlive();
  }

  @Override
  public void setTrafficClass(int tc) throws SocketException {
    delegate.setTrafficClass(tc);
  }

  @Override
  public int getTrafficClass() throws SocketException {
    return delegate.getTrafficClass();
  }

  @Override
  public void setReuseAddress(boolean on) throws SocketException {
    delegate.setReuseAddress(on);
  }

  @Override
  public boolean getReuseAddress() throws SocketException {
    return delegate.getReuseAddress();
  }

  @Override
  public void shutdownInput() throws IOException {
    delegate.shutdownInput();
  }

  @Override
  public void shutdownOutput() throws IOException {
    delegate.shutdownOutput();
  }

  @Override
  public boolean isConnected() {
    return delegate.isConnected();
  }

  @Override
  public boolean isBound() {
    return delegate.isBound();
  }

  @Override
  public boolean isClosed() {
    return delegate.isClosed();
  }

  @Override
  public boolean isInputShutdown() {
    return delegate.isInputShutdown();
  }

  @Override
  public boolean isOutputShutdown() {
    return delegate.isOutputShutdown();
  }

  @Override
  public void setPerformancePreferences(int connectionTime, int latency, int bandwidth) {
    delegate.setPerformancePreferences(connectionTime, latency, bandwidth);
  }

  @Override
  public String toString() {
    return delegate.toString();
  }

  /** Accumulates a byte count and flushes it to a metric in batches. */
  private static final class ByteCounter {

    private final LongConsumer sink;
    private final AtomicLong pending = new AtomicLong();
    private volatile long lastFlushNanos = System.nanoTime();

    ByteCounter(LongConsumer sink) {
      this.sink = sink;
    }

    void add(long count) {
      if (count <= 0) {
        return;
      }
      pending.addAndGet(count);
      if (System.nanoTime() - lastFlushNanos >= FLUSH_INTERVAL_NANOS) {
        flush();
      }
    }

    void flush() {
      // getAndSet means a concurrent flush hands the whole outstanding count to exactly one caller.
      long count = pending.getAndSet(0);
      lastFlushNanos = System.nanoTime();
      if (count > 0) {
        sink.accept(count);
      }
    }
  }

  private static final class CountingInputStream extends FilterInputStream {

    private final ByteCounter counter;

    CountingInputStream(InputStream in, ByteCounter counter) {
      super(in);
      this.counter = counter;
    }

    @Override
    public int read() throws IOException {
      int b = in.read();
      if (b != -1) {
        counter.add(1);
      }
      return b;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
      int n = in.read(buf, off, len);
      counter.add(n);
      return n;
    }

    @Override
    public long skip(long n) throws IOException {
      long skipped = in.skip(n);
      counter.add(skipped);
      return skipped;
    }

    @Override
    public void close() throws IOException {
      try {
        in.close();
      } finally {
        counter.flush();
      }
    }
  }

  private static final class CountingOutputStream extends FilterOutputStream {

    private final ByteCounter counter;

    CountingOutputStream(OutputStream out, ByteCounter counter) {
      super(out);
      this.counter = counter;
    }

    @Override
    public void write(int b) throws IOException {
      out.write(b);
      counter.add(1);
    }

    @Override
    public void write(byte[] buf, int off, int len) throws IOException {
      // FilterOutputStream's implementation writes a byte at a time; go straight to the delegate.
      out.write(buf, off, len);
      counter.add(len);
    }

    @Override
    public void close() throws IOException {
      try {
        super.close();
      } finally {
        counter.flush();
      }
    }
  }
}
