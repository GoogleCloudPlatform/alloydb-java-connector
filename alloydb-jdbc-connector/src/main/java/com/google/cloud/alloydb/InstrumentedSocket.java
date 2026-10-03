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
import java.util.concurrent.atomic.AtomicBoolean;
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
 */
class InstrumentedSocket extends Socket {

  private static final Logger logger = LoggerFactory.getLogger(InstrumentedSocket.class);

  private final Socket delegate;
  private final MetricRecorder metricRecorder;
  private final TelemetryAttributes attrs;

  private InputStream inputStream;
  private OutputStream outputStream;

  /**
   * Whether {@link #close()} has run. Not guarded by this socket's monitor: the accessors below
   * hold that monitor, and the JDK is careful never to hold it across a close for exactly that
   * reason -- an {@code SSLSocket} close writes close_notify to the peer, which a wedged connection
   * can block for as long as the write timeout allows.
   */
  private final AtomicBoolean closed = new AtomicBoolean();

  /** Wraps {@code delegate} and counts the connection as open. */
  InstrumentedSocket(Socket delegate, MetricRecorder metricRecorder, TelemetryAttributes attrs) {
    super();
    this.delegate = delegate;
    this.metricRecorder = metricRecorder;
    this.attrs = attrs;
    metricRecorder.recordOpenConnection(attrs);
  }

  @Override
  public synchronized InputStream getInputStream() throws IOException {
    // Socket throws once it is closed, whether or not a stream was handed out earlier, so the
    // memoized stream must not outlive the connection.
    checkNotClosed();
    // Socket.getInputStream() is expected to return the same stream on every call.
    if (inputStream == null) {
      inputStream = new ClosingInputStream(delegate.getInputStream());
    }
    return inputStream;
  }

  @Override
  public synchronized OutputStream getOutputStream() throws IOException {
    checkNotClosed();
    if (outputStream == null) {
      outputStream = new ClosingOutputStream(delegate.getOutputStream());
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
        recordClose();
      }
    }
  }

  /** Reports the close of the connection, exactly once however the caller closed it. */
  private void recordClose() {
    try {
      metricRecorder.recordClosedConnection(attrs);
    } catch (RuntimeException e) {
      // A recorder that fails must not become a failure of Socket.close(). The caller's close
      // succeeded, and this runs from close()'s finally block, where an exception would replace
      // whatever the delegate's own close reported.
      logger.debug("Failed to report the close of a connection.", e);
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
   * Routes a stream close back through {@link InstrumentedSocket#close()}.
   *
   * <p>{@code Socket.close()} is documented to close the socket's streams too, and callers take the
   * converse for granted. Closing the delegate's stream directly would skip the closed-connection
   * bookkeeping, so the connection would stay counted as open for the life of the process.
   */
  private final class ClosingInputStream extends FilterInputStream {

    ClosingInputStream(InputStream in) {
      super(in);
    }

    @Override
    public void close() throws IOException {
      InstrumentedSocket.this.close();
    }
  }

  private final class ClosingOutputStream extends FilterOutputStream {

    ClosingOutputStream(OutputStream out) {
      super(out);
    }

    @Override
    public void write(byte[] buf, int off, int len) throws IOException {
      // FilterOutputStream's implementation writes a byte at a time; go straight to the delegate.
      out.write(buf, off, len);
    }

    @Override
    public void close() throws IOException {
      InstrumentedSocket.this.close();
    }
  }
}
