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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketOption;
import java.net.StandardSocketOptions;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class InstrumentedSocketTest {

  private ServerSocket server;
  private Socket delegate;
  private Socket peer;
  private RecordingMetricRecorder recorder;

  @Before
  public void setUp() throws IOException {
    server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
    delegate = new Socket();
    delegate.connect(
        new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort()));
    peer = server.accept();
    recorder = new RecordingMetricRecorder();
    // Every socket here is one the connector only builds with telemetry turned on.
    recorder.enabled = true;
  }

  @After
  public void tearDown() throws IOException {
    closeQuietly(peer);
    closeQuietly(delegate);
    closeQuietly(server);
  }

  private static void closeQuietly(java.io.Closeable c) {
    try {
      if (c != null) {
        c.close();
      }
    } catch (IOException ignored) {
      // Nothing useful to do while tearing down a test.
    }
  }

  private InstrumentedSocket newSocket() throws IOException {
    return new InstrumentedSocket(delegate, recorder, TelemetryAttributes.forConnection(false));
  }

  /** Whether this runtime has the Socket option methods added in Java 9 at all. */
  private static boolean hasSocketOptionMethods() {
    try {
      Socket.class.getMethod("getOption", SocketOption.class);
      return true;
    } catch (NoSuchMethodException e) {
      return false;
    }
  }

  /**
   * InstrumentedSocket is constructed on a null SocketImpl, so any Socket method it fails to
   * delegate silently answers from an empty superclass instead of the real connection.
   */
  @Test
  public void testAccessorsDelegateRatherThanAnsweringFromTheEmptySuperclass() throws IOException {
    InstrumentedSocket socket = newSocket();

    assertThat(socket.getInetAddress()).isEqualTo(delegate.getInetAddress());
    assertThat(socket.getPort()).isEqualTo(delegate.getPort());
    assertThat(socket.getPort()).isNotEqualTo(0);
    assertThat(socket.getLocalAddress()).isEqualTo(delegate.getLocalAddress());
    assertThat(socket.getLocalPort()).isEqualTo(delegate.getLocalPort());
    assertThat(socket.getLocalPort()).isNotEqualTo(0);
    assertThat(socket.getRemoteSocketAddress()).isEqualTo(delegate.getRemoteSocketAddress());
    assertThat(socket.getLocalSocketAddress()).isEqualTo(delegate.getLocalSocketAddress());
    assertThat(socket.isConnected()).isTrue();
    assertThat(socket.isBound()).isTrue();
    assertThat(socket.isClosed()).isFalse();
  }

  @Test
  public void testSocketOptionsDelegate() throws IOException {
    InstrumentedSocket socket = newSocket();

    socket.setTcpNoDelay(true);
    assertThat(socket.getTcpNoDelay()).isTrue();
    assertThat(delegate.getTcpNoDelay()).isTrue();

    socket.setKeepAlive(true);
    assertThat(socket.getKeepAlive()).isTrue();

    socket.setSoTimeout(1234);
    assertThat(socket.getSoTimeout()).isEqualTo(1234);
    assertThat(delegate.getSoTimeout()).isEqualTo(1234);

    socket.setSoLinger(true, 5);
    assertThat(socket.getSoLinger()).isEqualTo(5);

    socket.setReceiveBufferSize(16 * 1024);
    assertThat(socket.getReceiveBufferSize()).isGreaterThan(0);
  }

  @Test
  public void testStreamsAreReturnedOnlyOnce() throws IOException {
    InstrumentedSocket socket = newSocket();

    assertThat(socket.getInputStream()).isSameInstanceAs(socket.getInputStream());
    assertThat(socket.getOutputStream()).isSameInstanceAs(socket.getOutputStream());
  }

  @Test
  public void testClosedConnectionRecordedExactlyOnce() throws IOException {
    InstrumentedSocket socket = newSocket();

    socket.close();
    socket.close();

    assertThat(recorder.closedConnections.get()).isEqualTo(1);
    assertThat(delegate.isClosed()).isTrue();
  }

  @Test
  public void testOpenConnectionIsRecordedOnceOnConstruction() throws IOException {
    newSocket();

    assertThat(recorder.openConnections.get()).isEqualTo(1);
    assertThat(recorder.closedConnections.get()).isEqualTo(0);
  }

  /**
   * Socket's option methods arrived in Java 9, so this module cannot compile against them -- but
   * they exist at runtime, where leaving them undelegated means a caller silently configures the
   * empty superclass instead of the real connection, and leaks a file descriptor doing it.
   */
  @Test
  public void testSocketOptionsAddedInJava9Delegate() throws IOException {
    assumeTrue(hasSocketOptionMethods());
    InstrumentedSocket socket = newSocket();

    assertThat(socket.setOption(StandardSocketOptions.SO_KEEPALIVE, true)).isSameInstanceAs(socket);
    assertThat(delegate.getKeepAlive()).isTrue();
    assertThat(socket.getOption(StandardSocketOptions.SO_KEEPALIVE)).isTrue();

    socket.setOption(StandardSocketOptions.TCP_NODELAY, true);
    assertThat(delegate.getTcpNoDelay()).isTrue();

    assertThat(socket.getOption(StandardSocketOptions.SO_RCVBUF))
        .isEqualTo(delegate.getReceiveBufferSize());
    // Socket#supportedOptions is invisible to this module too, so compare against what a real
    // connection has to support rather than against the delegate's own answer.
    assertThat(socket.supportedOptions())
        .containsAtLeast(StandardSocketOptions.SO_KEEPALIVE, StandardSocketOptions.TCP_NODELAY);
  }

  @Test
  public void testStreamsAreRefusedAfterClose() throws IOException {
    InstrumentedSocket socket = newSocket();
    // Hand out both streams first, so the memoized copies are what the assertions below reject.
    socket.getInputStream();
    socket.getOutputStream();

    socket.close();

    assertThrows(SocketException.class, socket::getInputStream);
    assertThrows(SocketException.class, socket::getOutputStream);
  }

  @Test
  public void testClosingInputStreamClosesSocketAndRecordsClosedConnection() throws IOException {
    InstrumentedSocket socket = newSocket();
    InputStream in = socket.getInputStream();

    in.close();

    assertThat(delegate.isClosed()).isTrue();
    assertThat(recorder.closedConnections.get()).isEqualTo(1);
  }
}
