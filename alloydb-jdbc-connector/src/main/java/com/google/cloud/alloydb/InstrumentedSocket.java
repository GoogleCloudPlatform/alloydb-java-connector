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
import java.nio.channels.SocketChannel;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;
import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;

/**
 * A Socket wrapper that counts the bytes sent and received on the connection and records the
 * connection as closed when it is closed.
 *
 * <p>This extends {@link SSLSocket} rather than the plain {@link Socket} the delegate field's
 * static type would suggest. {@link Connector#connect} always wraps a real {@code SSLSocket} (see
 * {@link ConnectionSocket#connect}), and callers are entitled to cast the result back to one, as
 * the integration tests do. Every method of {@code SSLSocket} available at this module's Java 8
 * source level (and the {@code Socket} methods it inherits) is delegated; the handful of
 * ALPN-related {@code SSLSocket} methods added in Java 9 are not part of that API surface and so
 * cannot be overridden here. The superclass is constructed via its own no-arg constructor, which
 * allocates an unconnected, otherwise-unused SocketImpl of its own; any method that is not
 * delegated here would silently operate on that empty instance instead of the real connection.
 *
 * <p>Byte counts are accumulated locally and flushed to the {@link MetricRecorder} in batches.
 * Recording every read and write directly would put an attribute-set lookup and an allocation on
 * the socket's hot path, which for a byte-at-a-time caller would mean one metric update per byte.
 */
class InstrumentedSocket extends SSLSocket {

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
    super();
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
        rx.close();
        tx.close();
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

  /*
   * SSLSocket-specific delegation. The delegate is always an SSLSocket in production (see the
   * class javadoc), so the cast below is safe there; it is deferred to each call site, rather
   * than performed once in the constructor, so unit tests that only exercise the plain Socket
   * surface above can still build an InstrumentedSocket around an ordinary Socket.
   */

  @Override
  public String[] getSupportedCipherSuites() {
    return ((SSLSocket) delegate).getSupportedCipherSuites();
  }

  @Override
  public String[] getEnabledCipherSuites() {
    return ((SSLSocket) delegate).getEnabledCipherSuites();
  }

  @Override
  public void setEnabledCipherSuites(String[] suites) {
    ((SSLSocket) delegate).setEnabledCipherSuites(suites);
  }

  @Override
  public String[] getSupportedProtocols() {
    return ((SSLSocket) delegate).getSupportedProtocols();
  }

  @Override
  public String[] getEnabledProtocols() {
    return ((SSLSocket) delegate).getEnabledProtocols();
  }

  @Override
  public void setEnabledProtocols(String[] protocols) {
    ((SSLSocket) delegate).setEnabledProtocols(protocols);
  }

  @Override
  public SSLSession getSession() {
    return ((SSLSocket) delegate).getSession();
  }

  @Override
  public SSLSession getHandshakeSession() {
    return ((SSLSocket) delegate).getHandshakeSession();
  }

  @Override
  public void addHandshakeCompletedListener(HandshakeCompletedListener listener) {
    ((SSLSocket) delegate).addHandshakeCompletedListener(listener);
  }

  @Override
  public void removeHandshakeCompletedListener(HandshakeCompletedListener listener) {
    ((SSLSocket) delegate).removeHandshakeCompletedListener(listener);
  }

  @Override
  public void startHandshake() throws IOException {
    ((SSLSocket) delegate).startHandshake();
  }

  @Override
  public void setUseClientMode(boolean mode) {
    ((SSLSocket) delegate).setUseClientMode(mode);
  }

  @Override
  public boolean getUseClientMode() {
    return ((SSLSocket) delegate).getUseClientMode();
  }

  @Override
  public void setNeedClientAuth(boolean need) {
    ((SSLSocket) delegate).setNeedClientAuth(need);
  }

  @Override
  public boolean getNeedClientAuth() {
    return ((SSLSocket) delegate).getNeedClientAuth();
  }

  @Override
  public void setWantClientAuth(boolean want) {
    ((SSLSocket) delegate).setWantClientAuth(want);
  }

  @Override
  public boolean getWantClientAuth() {
    return ((SSLSocket) delegate).getWantClientAuth();
  }

  @Override
  public void setEnableSessionCreation(boolean flag) {
    ((SSLSocket) delegate).setEnableSessionCreation(flag);
  }

  @Override
  public boolean getEnableSessionCreation() {
    return ((SSLSocket) delegate).getEnableSessionCreation();
  }

  /** Accumulates a byte count and flushes it to a metric in batches. */
  private static final class ByteCounter {

    /**
     * How many add() calls to let pass between clock reads. A byte-at-a-time caller would
     * otherwise force a System.nanoTime() call on every single-byte read()/write(int); sampling
     * the clock instead of checking it on every call keeps that overhead off the hot path, at the
     * cost of the flush interval being enforced loosely rather than exactly.
     */
    private static final int TIME_CHECK_EVERY_N_ADDS = 64;

    private final LongConsumer sink;

    // Guarded by "this", so add(), flush(), and close() are mutually exclusive.
    private long pending;
    private long lastFlushNanos = System.nanoTime();
    private int addsSinceTimeCheck;
    private boolean closed;

    ByteCounter(LongConsumer sink) {
      this.sink = sink;
    }

    synchronized void add(long count) {
      if (count <= 0) {
        return;
      }
      pending += count;
      if (closed) {
        // A read or write raced with close(): the terminal flush in close() may already have run
        // and will not run again, so this counter must flush itself immediately rather than let
        // these bytes sit in `pending` forever. Whichever of close()'s flush or this add() runs
        // last is guaranteed (by the shared monitor) to see `closed == true` and flush, so no
        // interleaving loses bytes.
        flush();
        return;
      }
      if (++addsSinceTimeCheck < TIME_CHECK_EVERY_N_ADDS) {
        return;
      }
      addsSinceTimeCheck = 0;
      if (System.nanoTime() - lastFlushNanos >= FLUSH_INTERVAL_NANOS) {
        flush();
      }
    }

    synchronized void flush() {
      long count = pending;
      pending = 0;
      lastFlushNanos = System.nanoTime();
      if (count > 0) {
        sink.accept(count);
      }
    }

    /** The terminal flush, called once from InstrumentedSocket.close(). */
    synchronized void close() {
      closed = true;
      flush();
    }
  }

  // A hand-rolled counting FilterInputStream/FilterOutputStream, rather than Guava's
  // com.google.common.io.CountingInputStream/CountingOutputStream (already a dependency of this
  // module). Both are declared `final`, so they cannot be subclassed to add the batched-flush
  // behavior this class needs, and they only expose a running total via getCount() with no way to
  // reset it per interval. Reusing them would mean wrapping them and computing a delta against a
  // saved checkpoint on every read/write, which is no simpler than counting the bytes directly
  // here.
  private final class CountingInputStream extends FilterInputStream {

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
      // Socket.close() is documented to close the socket's streams too; route back through the
      // outer close() instead of closing the delegate's stream directly, so the closed-connection
      // bookkeeping there (the `closed` guard, recordClosedConnection, counter flush) always runs
      // however the caller chooses to close the connection.
      InstrumentedSocket.this.close();
    }
  }

  private final class CountingOutputStream extends FilterOutputStream {

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
      InstrumentedSocket.this.close();
    }
  }
}
