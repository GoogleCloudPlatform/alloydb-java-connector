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

import com.google.common.base.Throwables;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketOption;
import java.nio.channels.SocketChannel;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Socket wrapper that records the connection as open while the driver holds it and as closed once
 * it is closed.
 *
 * <p>The delegate {@link Connector#connect} wraps is the {@code SSLSocket} that {@link
 * ConnectionSocket#connect} returns, but this wrapper is a plain {@link Socket}: the connector
 * hands connections back as {@code Socket} (see {@link SocketFactory#createSocket()}), and the
 * Postgres driver treats them as such -- when it is asked for TLS of its own it layers its SSL
 * socket over whatever socket it was given rather than casting. Being a {@code Socket} keeps the
 * whole TLS surface an {@code SSLSocket} subclass would have to delegate, and the ALPN methods
 * added in Java 9 that it could not, out of this class entirely.
 *
 * <p>The superclass is constructed via its own no-arg constructor, which leaves it unconnected and
 * without a file descriptor; any method not delegated here would silently operate on that empty
 * instance instead of the real connection, and would make it allocate a descriptor that nothing
 * ever closes. Every method of {@code Socket} is therefore delegated, including the three option
 * methods added in Java 9 -- see {@link #setOption} for how, given that this module compiles
 * against the Java 8 API.
 *
 * <p>Byte counts are accumulated locally and flushed to the {@link MetricRecorder} in batches.
 * Recording every read and write directly would put an attribute-set lookup and an allocation on
 * the socket's hot path, which for a byte-at-a-time caller would mean one metric update per byte.
 * {@link Tracker} flushes what has accumulated on a fixed interval, so batching costs accuracy only
 * within that interval.
 */
class InstrumentedSocket extends Socket {

  private static final Logger logger = LoggerFactory.getLogger(InstrumentedSocket.class);

  /**
   * How many bytes may sit unreported before a flush happens without waiting for the next periodic
   * flush. {@link Tracker} bounds how long a byte can go unreported; this bounds how many bytes a
   * connection moving data in bulk holds on to in between, for the price of one comparison per
   * call.
   */
  private static final long FLUSH_BYTE_THRESHOLD = 64 * 1024;

  private final Socket delegate;
  private final MetricRecorder metricRecorder;
  private final TelemetryAttributes attrs;
  private final ByteCounter rx;
  private final ByteCounter tx;
  private final Tracker tracker;
  private final Bookkeeping bookkeeping;

  private InputStream inputStream;
  private OutputStream outputStream;

  /**
   * Whether {@link #close()} has run. Not guarded by this socket's monitor: the accessors below
   * hold that monitor, and the JDK is careful never to hold it across a close for exactly that
   * reason -- an {@code SSLSocket} close writes close_notify to the peer, which a wedged connection
   * can block for as long as the write timeout allows.
   */
  private final AtomicBoolean closed = new AtomicBoolean();

  /**
   * Wraps {@code delegate}, counts the connection as open, and registers it with {@code tracker} so
   * that its byte counts are reported while it is open.
   */
  InstrumentedSocket(
      Socket delegate, MetricRecorder metricRecorder, TelemetryAttributes attrs, Tracker tracker) {
    super();
    this.delegate = delegate;
    this.metricRecorder = metricRecorder;
    this.attrs = attrs;
    this.rx = new ByteCounter(metricRecorder::recordBytesRx);
    this.tx = new ByteCounter(metricRecorder::recordBytesTx);
    this.tracker = tracker;
    metricRecorder.recordOpenConnection(attrs);
    this.bookkeeping = tracker.register(rx, tx);
  }

  @Override
  public synchronized InputStream getInputStream() throws IOException {
    // Socket throws once it is closed, whether or not a stream was handed out earlier, so the
    // memoized stream must not outlive the connection.
    checkNotClosed();
    // Socket.getInputStream() is expected to return the same stream on every call.
    if (inputStream == null) {
      inputStream = new CountingInputStream(delegate.getInputStream(), rx);
    }
    return inputStream;
  }

  @Override
  public synchronized OutputStream getOutputStream() throws IOException {
    checkNotClosed();
    if (outputStream == null) {
      outputStream = new CountingOutputStream(delegate.getOutputStream(), tx);
    }
    return outputStream;
  }

  private void checkNotClosed() throws SocketException {
    if (closed.get()) {
      throw new SocketException("Socket is closed");
    }
  }

  // Socket#close() was synchronized in the Java 8 API this module compiles against, and the JDK
  // dropped that in Java 9 for the same reason the `closed` field above does not use this socket's
  // monitor: a close that blocks must not block the accessors.
  @SuppressWarnings("UnsynchronizedOverridesSynchronized")
  @Override
  public void close() throws IOException {
    boolean firstClose = closed.compareAndSet(false, true);
    try {
      delegate.close();
    } finally {
      if (firstClose) {
        // Nothing periodic should keep flushing a connection that is gone.
        tracker.unregister(bookkeeping);
        recordClose();
      }
    }
  }

  /**
   * Reports the connection's final byte counts and its close, exactly once however the caller
   * closed it.
   *
   * <p>The close is recorded before the counters are flushed. This is the connection's only chance
   * to report it -- nothing will try again -- and a counter whose flush throws must not take the
   * close down with it, or the connection stays counted as open for the life of the process.
   */
  private void recordClose() {
    try {
      metricRecorder.recordClosedConnection(attrs);
    } catch (RuntimeException e) {
      // A recorder that fails must not become a failure of Socket.close(). The caller's close
      // succeeded, and this runs from close()'s finally block, where an exception would replace
      // whatever the delegate's own close reported.
      logger.debug("Failed to report the close of a connection.", e);
    }
    rx.close();
    tx.close();
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
    // One byte leaves the connection this way too, so it belongs in the count. Added after the
    // send, so a socket that refuses urgent data -- SSLSocket does -- counts nothing.
    tx.add(1);
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
    // Nothing will ever be read again, so flush now rather than leave these bytes to a flush that
    // only another read would trigger.
    rx.flush();
  }

  @Override
  public void shutdownOutput() throws IOException {
    delegate.shutdownOutput();
    tx.flush();
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
   * Socket#setOption, Socket#getOption and Socket#supportedOptions arrived in Java 9. This module
   * compiles against the Java 8 API (maven.compiler.release=8, from google-cloud-shared-config),
   * so the three methods below cannot carry @Override and cannot name the delegate's copy of
   * themselves -- but the JVM resolves a virtual call by name and descriptor, not by what the
   * compiler could see, so they do override the real thing on every runtime that has it, which is
   * the only kind of runtime that can call them. Reflection bridges the rest. Socket options are
   * configured a handful of times per connection and never on the I/O path, so the cost does not
   * matter; being left out does, because then a caller's setOption() would quietly configure the
   * empty superclass instead of the real connection, and leak the file descriptor that answering
   * from the superclass allocates.
   */

  private static final Method SET_OPTION =
      socketMethod("setOption", SocketOption.class, Object.class);
  private static final Method GET_OPTION = socketMethod("getOption", SocketOption.class);
  private static final Method SUPPORTED_OPTIONS = socketMethod("supportedOptions");

  private static Method socketMethod(String name, Class<?>... parameterTypes) {
    try {
      return Socket.class.getMethod(name, parameterTypes);
    } catch (NoSuchMethodException e) {
      // Java 8, where the method does not exist and so nothing can call the override below.
      return null;
    }
  }

  public <T> Socket setOption(SocketOption<T> name, T value) throws IOException {
    invokeOnDelegate(SET_OPTION, name, value);
    // Socket#setOption returns the socket so that calls can be chained. That has to stay this
    // wrapper; handing back the delegate would let a chained call escape the instrumentation.
    return this;
  }

  @SuppressWarnings("unchecked")
  public <T> T getOption(SocketOption<T> name) throws IOException {
    return (T) invokeOnDelegate(GET_OPTION, name);
  }

  @SuppressWarnings("unchecked")
  public Set<SocketOption<?>> supportedOptions() {
    try {
      return (Set<SocketOption<?>>) invokeOnDelegate(SUPPORTED_OPTIONS);
    } catch (IOException e) {
      // Socket#supportedOptions does not declare IOException, and the delegate cannot throw one.
      throw new AssertionError(e);
    }
  }

  private Object invokeOnDelegate(Method method, Object... args) throws IOException {
    if (method == null) {
      throw new UnsupportedOperationException();
    }
    try {
      return method.invoke(delegate, args);
    } catch (IllegalAccessException e) {
      // Socket is a public class in an exported package, so its public methods are accessible.
      throw new AssertionError(e);
    } catch (InvocationTargetException e) {
      Throwables.throwIfInstanceOf(e.getCause(), IOException.class);
      Throwables.throwIfUnchecked(e.getCause());
      throw new AssertionError(e.getCause());
    }
  }

  /**
   * Accumulates a byte count and flushes it to a metric in batches. Nothing here reads a clock: a
   * {@link Tracker} calls {@link #flush()} on the interval instead, which keeps even a
   * System.nanoTime() call off the per-read-and-write path and makes the interval something the
   * counter actually obeys rather than samples.
   */
  static final class ByteCounter {

    private final LongConsumer sink;

    // Guarded by "this", so add(), flush(), and close() are mutually exclusive.
    private long pending;
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
      if (pending >= FLUSH_BYTE_THRESHOLD) {
        flush();
      }
    }

    synchronized void flush() {
      long count = pending;
      if (count <= 0) {
        return;
      }
      pending = 0;
      try {
        sink.accept(count);
      } catch (RuntimeException e) {
        // A recorder that fails must not reach the caller. This runs on the read and write path,
        // where an unchecked exception would break a connection that is working and reach the
        // driver as something it cannot turn into a SQLException, and from shutdownInput() and
        // shutdownOutput(), where the operation it would be reported against has already
        // succeeded. The count goes back into `pending` rather than being dropped: the bytes
        // really were transferred -- on the read side they are already off the socket -- so the
        // next flush should report them.
        pending += count;
        logger.debug("Failed to report the byte counts of a connection.", e);
      }
    }

    /** The terminal flush. Called once per connection, however the connection ended. */
    synchronized void close() {
      closed = true;
      flush();
    }
  }

  /**
   * The open connections of one {@link Connector}, and the thing that has to happen to them
   * periodically rather than on the I/O path: their byte counts have to reach the recorder while
   * they are still open.
   *
   * <p>The Go connector runs a reporting ticker per connection. A thread per connection would be
   * far too expensive here, so {@link #tick()} serves every connection a connector has open and
   * runs on the executor the connector already owns.
   */
  static final class Tracker {

    /**
     * How often {@link #tick()} is expected to run, and therefore the longest a transferred byte
     * goes unreported, bar the shortcut in {@link InstrumentedSocket#FLUSH_BYTE_THRESHOLD}.
     */
    static final long FLUSH_INTERVAL_MILLIS = TimeUnit.SECONDS.toMillis(5);

    /**
     * The sockets that are open, by way of the bookkeeping that outlives them. Holding the
     * bookkeeping rather than the socket keeps this set from pinning a connection the application
     * abandoned without closing, which would never be removed from it.
     */
    private final Set<Bookkeeping> open = ConcurrentHashMap.newKeySet();

    Bookkeeping register(ByteCounter rx, ByteCounter tx) {
      Bookkeeping registered = new Bookkeeping(rx, tx);
      open.add(registered);
      return registered;
    }

    void unregister(Bookkeeping bookkeeping) {
      open.remove(bookkeeping);
    }

    /**
     * Flushes every open connection's byte counts. Must not throw: it runs on a fixed-rate
     * schedule, which a single escaping exception would cancel for the life of the connector.
     */
    void tick() {
      for (Bookkeeping bookkeeping : open) {
        try {
          bookkeeping.flush();
        } catch (RuntimeException e) {
          logger.debug("Failed to report the byte counts of an open connection.", e);
        }
      }
    }
  }

  /** What a connection has left to report, held apart from the socket so as not to pin it. */
  static final class Bookkeeping {

    private final ByteCounter rx;
    private final ByteCounter tx;

    Bookkeeping(ByteCounter rx, ByteCounter tx) {
      this.rx = rx;
      this.tx = tx;
    }

    void flush() {
      rx.flush();
      tx.flush();
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
