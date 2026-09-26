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

import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
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

  private InstrumentedSocket newSocket(AtomicLong nanoClock) throws IOException {
    return new InstrumentedSocket(
        delegate, recorder, TelemetryAttributes.forConnection(false), nanoClock::get);
  }

  /**
   * Reads and discards everything the peer receives, so a write larger than the socket's send
   * buffer cannot block the test.
   */
  private Thread startPeerDrain() {
    Thread drain =
        new Thread(
            () -> {
              try {
                InputStream peerIn = peer.getInputStream();
                byte[] buf = new byte[8192];
                while (peerIn.read(buf) != -1) {
                  // Discard.
                }
              } catch (IOException ignored) {
                // The socket closed; the test is done reading.
              }
            });
    drain.setDaemon(true);
    drain.start();
    return drain;
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
  public void testBytesAreCountedAndFlushedOnClose() throws IOException {
    InstrumentedSocket socket = newSocket();
    OutputStream out = socket.getOutputStream();

    out.write(new byte[100]);
    out.write(7);
    out.flush();

    byte[] buf = new byte[3];
    peer.getOutputStream().write(buf);
    peer.getOutputStream().flush();
    InputStream in = socket.getInputStream();
    ByteStreams.readFully(in, buf);

    // Well under the flush threshold, so nothing has reached the recorder yet.
    assertThat(recorder.bytesTx.get()).isEqualTo(0);
    assertThat(recorder.bytesRx.get()).isEqualTo(0);

    socket.close();

    assertThat(recorder.bytesTx.get()).isEqualTo(101);
    assertThat(recorder.bytesRx.get()).isEqualTo(3);
  }

  @Test
  public void testLargeTransferIsCountedInFull() throws IOException {
    InstrumentedSocket socket = newSocket();
    OutputStream out = socket.getOutputStream();

    startPeerDrain();

    for (int i = 0; i < 16; i++) {
      out.write(new byte[8 * 1024]);
    }
    out.flush();
    socket.close();

    // Batching must not lose bytes, however many flushes the interval happened to trigger.
    assertThat(recorder.bytesTx.get()).isEqualTo(128L * 1024);
  }

  @Test
  public void testClosedConnectionRecordedExactlyOnce() throws IOException {
    InstrumentedSocket socket = newSocket();

    socket.close();
    socket.close();

    assertThat(recorder.closedConnections.get()).isEqualTo(1);
    assertThat(delegate.isClosed()).isTrue();
  }

  /**
   * The flush interval is only consulted every so many calls, so a connection that moves a lot of
   * data in few calls has to be flushed on volume instead. Without that, a pooled connection can
   * report nothing for as long as it stays open, which is hours.
   */
  @Test
  public void testLargeWriteIsReportedWithoutWaitingForClose() throws IOException {
    // A clock that never advances: reaching the byte threshold must flush on its own.
    InstrumentedSocket socket = newSocket(new AtomicLong());
    OutputStream out = socket.getOutputStream();
    startPeerDrain();

    out.write(new byte[128 * 1024]);
    out.flush();

    assertThat(recorder.bytesTx.get()).isEqualTo(128 * 1024);
  }

  @Test
  public void testTrickleIsFlushedOnceTheIntervalElapses() throws IOException {
    AtomicLong clock = new AtomicLong();
    InstrumentedSocket socket = newSocket(clock);
    OutputStream out = socket.getOutputStream();

    for (int i = 0; i < 64; i++) {
      out.write(1);
    }
    out.flush();

    // Enough calls to read the clock, but no time has passed, so the bytes are still batched.
    assertThat(recorder.bytesTx.get()).isEqualTo(0);

    clock.addAndGet(TimeUnit.SECONDS.toNanos(6));
    for (int i = 0; i < 64; i++) {
      out.write(1);
    }
    out.flush();

    assertThat(recorder.bytesTx.get()).isEqualTo(128);
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
  public void testSslAccessorsDelegate() throws IOException {
    SSLSocket sslDelegate = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
    InstrumentedSocket socket =
        new InstrumentedSocket(sslDelegate, recorder, TelemetryAttributes.forConnection(false));

    assertThat(socket).isInstanceOf(SSLSocket.class);
    assertThat(socket.getSupportedCipherSuites())
        .isEqualTo(sslDelegate.getSupportedCipherSuites());
    assertThat(socket.getSupportedProtocols()).isEqualTo(sslDelegate.getSupportedProtocols());

    closeQuietly(sslDelegate);
  }

  /**
   * SSLSocket implements getSSLParameters()/setSSLParameters() in terms of the individual getters
   * and setters, so an undelegated wrapper appears to work while silently dropping everything an
   * SSLParameters carries beyond cipher suites, protocols, and the client-auth flags -- including
   * the endpoint identification algorithm and SNI names ConnectionSocket sets.
   */
  @Test
  public void testSslParametersDelegate() throws IOException {
    SSLSocket sslDelegate = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
    InstrumentedSocket socket =
        new InstrumentedSocket(sslDelegate, recorder, TelemetryAttributes.forConnection(false));

    SSLParameters params = socket.getSSLParameters();
    params.setEndpointIdentificationAlgorithm("HTTPS");
    params.setServerNames(Collections.singletonList(new SNIHostName("alloydb.example.com")));
    socket.setSSLParameters(params);

    assertThat(sslDelegate.getSSLParameters().getEndpointIdentificationAlgorithm())
        .isEqualTo("HTTPS");
    assertThat(sslDelegate.getSSLParameters().getServerNames())
        .containsExactly(new SNIHostName("alloydb.example.com"));
    assertThat(socket.getSSLParameters().getEndpointIdentificationAlgorithm()).isEqualTo("HTTPS");
    assertThat(socket.getSSLParameters().getServerNames())
        .containsExactly(new SNIHostName("alloydb.example.com"));

    closeQuietly(sslDelegate);
  }

  @Test
  public void testClosingInputStreamClosesSocketAndRecordsClosedConnection() throws IOException {
    InstrumentedSocket socket = newSocket();
    InputStream in = socket.getInputStream();

    in.close();

    assertThat(delegate.isClosed()).isTrue();
    assertThat(recorder.closedConnections.get()).isEqualTo(1);
  }

  @Test
  public void testCloseDuringConcurrentWriteDoesNotLoseBytes() throws Exception {
    InstrumentedSocket socket = newSocket();
    OutputStream out = socket.getOutputStream();
    AtomicLong written = new AtomicLong();
    CountDownLatch started = new CountDownLatch(1);

    Thread writer =
        new Thread(
            () -> {
              try {
                for (int i = 0; i < 1_000_000; i++) {
                  started.countDown();
                  out.write(1);
                  written.incrementAndGet();
                }
              } catch (IOException ignored) {
                // The socket closed out from under this thread; that's the point of the test.
              }
            });
    writer.start();
    started.await();
    socket.close();
    writer.join();

    assertThat(recorder.bytesTx.get()).isEqualTo(written.get());
  }

  private static final class RecordingMetricRecorder implements MetricRecorder {

    final AtomicLong bytesRx = new AtomicLong();
    final AtomicLong bytesTx = new AtomicLong();
    final AtomicInteger closedConnections = new AtomicInteger();

    @Override
    public boolean isEnabled() {
      return true;
    }

    @Override
    public void shutdown() {}

    @Override
    public void recordDialCount(TelemetryAttributes attrs) {}

    @Override
    public void recordDialLatency(double latencyMs) {}

    @Override
    public void recordOpenConnection(TelemetryAttributes attrs) {}

    @Override
    public void recordClosedConnection(TelemetryAttributes attrs) {
      closedConnections.incrementAndGet();
    }

    @Override
    public void recordBytesRx(long count) {
      bytesRx.addAndGet(count);
    }

    @Override
    public void recordBytesTx(long count) {
      bytesTx.addAndGet(count);
    }

    @Override
    public void recordRefreshCount(TelemetryAttributes attrs) {}
  }
}
