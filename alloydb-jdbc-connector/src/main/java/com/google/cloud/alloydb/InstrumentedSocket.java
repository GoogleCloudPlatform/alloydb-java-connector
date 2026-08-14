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
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;

/**
 * A thin Socket wrapper that returns instrumented input/output streams to track bytes
 * sent/received and records a closed connection on close.
 */
class InstrumentedSocket extends Socket {

  private final Socket delegate;
  private final MetricRecorder metricRecorder;
  private final TelemetryAttributes attrs;
  private boolean closed;

  InstrumentedSocket(Socket delegate, MetricRecorder metricRecorder, TelemetryAttributes attrs) {
    this.delegate = delegate;
    this.metricRecorder = metricRecorder;
    this.attrs = attrs;
  }

  @Override
  public InputStream getInputStream() throws IOException {
    return new FilterInputStream(delegate.getInputStream()) {
      @Override
      public int read() throws IOException {
        int b = in.read();
        if (b != -1) {
          metricRecorder.recordBytesRx(1, attrs);
        }
        return b;
      }

      @Override
      public int read(byte[] buf, int off, int len) throws IOException {
        int n = in.read(buf, off, len);
        if (n > 0) {
          metricRecorder.recordBytesRx(n, attrs);
        }
        return n;
      }
    };
  }

  @Override
  public OutputStream getOutputStream() throws IOException {
    return new FilterOutputStream(delegate.getOutputStream()) {
      @Override
      public void write(int b) throws IOException {
        out.write(b);
        metricRecorder.recordBytesTx(1, attrs);
      }

      @Override
      public void write(byte[] buf, int off, int len) throws IOException {
        out.write(buf, off, len);
        metricRecorder.recordBytesTx(len, attrs);
      }
    };
  }

  @Override
  public synchronized void close() throws IOException {
    if (!closed) {
      closed = true;
      metricRecorder.recordClosedConnection(attrs);
    }
    delegate.close();
  }

  @Override
  public boolean isConnected() {
    return delegate.isConnected();
  }

  @Override
  public boolean isClosed() {
    return delegate.isClosed();
  }

  @Override
  public boolean isBound() {
    return delegate.isBound();
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
  public SocketAddress getRemoteSocketAddress() {
    return delegate.getRemoteSocketAddress();
  }

  @Override
  public SocketAddress getLocalSocketAddress() {
    return delegate.getLocalSocketAddress();
  }

  @Override
  public void setTcpNoDelay(boolean on) throws SocketException {
    delegate.setTcpNoDelay(on);
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
  public void setKeepAlive(boolean on) throws SocketException {
    delegate.setKeepAlive(on);
  }

  @Override
  public void shutdownInput() throws IOException {
    delegate.shutdownInput();
  }

  @Override
  public void shutdownOutput() throws IOException {
    delegate.shutdownOutput();
  }
}
