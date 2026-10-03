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

import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketOption;
import java.net.StandardSocketOptions;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class InstrumentedSocketTest {

  private ServerSocket server;
  private Socket delegate;
  private Socket peer;
  private RecordingMetricRecorder recorder;
  private InstrumentedSocket.Tracker tracker;

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
    tracker = new InstrumentedSocket.Tracker();
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
    return new InstrumentedSocket(
        delegate, recorder, TelemetryAttributes.forConnection(false), tracker);
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
   * A connection that moves a lot of data in bulk should not hold a whole flush interval's worth of
   * bytes; the byte threshold reports them without waiting for the next tick.
   */
  @Test
  public void testLargeWriteIsReportedWithoutWaitingForTheNextFlush() throws IOException {
    InstrumentedSocket socket = newSocket();
    OutputStream out = socket.getOutputStream();
    startPeerDrain();

    out.write(new byte[128 * 1024]);
    out.flush();

    assertThat(recorder.bytesTx.get()).isEqualTo(128 * 1024);
  }

  /**
   * The counters are batched, so what a connection has transferred reaches the recorder on the
   * tracker's interval. A pooled connection can stay open for hours, and one that is both quiet and
   * low-volume reaches neither the byte threshold nor its close for just as long.
   */
  @Test
  public void testTrickleIsReportedOnTheNextFlush() throws IOException {
    InstrumentedSocket socket = newSocket();
    OutputStream out = socket.getOutputStream();

    out.write(new byte[10]);
    out.flush();
    peer.getOutputStream().write(new byte[4]);
    peer.getOutputStream().flush();
    ByteStreams.readFully(socket.getInputStream(), new byte[4]);

    // Far too little traffic to reach the byte threshold on its own.
    assertThat(recorder.bytesTx.get()).isEqualTo(0);
    assertThat(recorder.bytesRx.get()).isEqualTo(0);

    tracker.tick();

    assertThat(recorder.bytesTx.get()).isEqualTo(10);
    assertThat(recorder.bytesRx.get()).isEqualTo(4);

    // Already reported, so a later tick must not count them twice.
    tracker.tick();
    socket.close();

    assertThat(recorder.bytesTx.get()).isEqualTo(10);
    assertThat(recorder.bytesRx.get()).isEqualTo(4);
  }

  @Test
  public void testClosedConnectionIsNotFlushedAgain() throws IOException {
    InstrumentedSocket socket = newSocket();
    OutputStream out = socket.getOutputStream();

    out.write(new byte[10]);
    out.flush();
    socket.close();
    tracker.tick();

    assertThat(recorder.bytesTx.get()).isEqualTo(10);
    assertThat(recorder.closedConnections.get()).isEqualTo(1);
  }

  /**
   * An application that leaks a connection would otherwise leave it counted as open for the life of
   * the process, which makes the open-connection count useless to the applications most likely to
   * be reading it.
   */
  @Test
  public void testAbandonedSocketIsEventuallyReportedAsClosed() throws Exception {
    InstrumentedSocket socket = newSocket();
    socket.getOutputStream().write(new byte[10]);
    // Every reference the test holds, dropped without a close. The streams are not kept either:
    // they hold their socket, which would keep it from ever being collected.
    socket = null;

    // The JVM is under no obligation to collect anything on demand, so keep asking.
    long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (recorder.closedConnections.get() == 0 && System.nanoTime() < deadlineNanos) {
      System.gc();
      tracker.tick();
      Thread.sleep(20);
    }

    assertThat(recorder.closedConnections.get()).isEqualTo(1);
    // The bytes it transferred are reported too, rather than lost along with the socket.
    assertThat(recorder.bytesTx.get()).isEqualTo(10);
  }

  @Test
  public void testOpenConnectionIsRecordedOnceOnConstruction() throws IOException {
    newSocket();

    assertThat(recorder.openConnections.get()).isEqualTo(1);
    assertThat(recorder.closedConnections.get()).isEqualTo(0);
  }

  @Test
  public void testHalfCloseFlushesTheMatchingCounter() throws IOException {
    InstrumentedSocket socket = newSocket();
    OutputStream out = socket.getOutputStream();

    out.write(new byte[10]);
    out.flush();
    socket.shutdownOutput();

    // Nothing will be written again, so these bytes must not wait for a flush that only the next
    // write would otherwise trigger.
    assertThat(recorder.bytesTx.get()).isEqualTo(10);
  }

  @Test
  public void testHalfCloseFlushesTheMatchingCounterOnTheReadSide() throws IOException {
    InstrumentedSocket socket = newSocket();
    peer.getOutputStream().write(new byte[10]);
    peer.getOutputStream().flush();
    ByteStreams.readFully(socket.getInputStream(), new byte[10]);

    socket.shutdownInput();

    assertThat(recorder.bytesRx.get()).isEqualTo(10);
  }

  /**
   * A recorder that fails must not turn into a failure of Socket.close(), and above all must not
   * cost the connection its close: the close is reported once and never retried, so a connection
   * that misses it stays counted as open for the life of the process.
   */
  @Test
  public void testBrokenRecorderDoesNotFailCloseOrStrandTheOpenCount() throws IOException {
    InstrumentedSocket socket = newSocket();
    socket.getOutputStream().write(new byte[10]);
    recorder.failByteFlushes = true;

    socket.close();

    assertThat(recorder.closedConnections.get()).isEqualTo(1);
    assertThat(delegate.isClosed()).isTrue();
  }

  /**
   * The counters flush on the read and write path, where an unchecked exception from a recorder
   * would reach the driver as something it cannot turn into a SQLException, and would break a
   * connection that is working perfectly well.
   */
  @Test
  public void testBrokenRecorderDoesNotFailAWriteThatCrossesTheFlushThreshold() throws Exception {
    InstrumentedSocket socket = newSocket();
    Thread drain =
        new Thread(
            () -> {
              try {
                ByteStreams.exhaust(peer.getInputStream());
              } catch (IOException ignored) {
                // The peer went away; nothing useful to do.
              }
            });
    drain.setDaemon(true);
    drain.start();
    recorder.failByteFlushes = true;

    // Enough to cross the 64KiB threshold at which a write flushes the counter itself.
    socket.getOutputStream().write(new byte[128 * 1024]);

    // The bytes really were sent, so the failed flush puts them back rather than dropping them.
    recorder.failByteFlushes = false;
    tracker.tick();
    assertThat(recorder.bytesTx.get()).isEqualTo(128 * 1024);
  }

  /**
   * A half-close flushes the counter that will never be written to again, by which point the
   * delegate's own shutdown has already succeeded: a broken recorder must not report an operation
   * that happened as one that failed.
   */
  @Test
  public void testBrokenRecorderDoesNotFailAHalfClose() throws IOException {
    InstrumentedSocket socket = newSocket();
    socket.getOutputStream().write(new byte[10]);
    peer.getOutputStream().write(new byte[10]);
    ByteStreams.readFully(socket.getInputStream(), new byte[10]);
    recorder.failByteFlushes = true;

    socket.shutdownOutput();
    socket.shutdownInput();

    assertThat(delegate.isOutputShutdown()).isTrue();
    assertThat(delegate.isInputShutdown()).isTrue();

    // Neither half-close dropped the bytes it was flushing.
    recorder.failByteFlushes = false;
    tracker.tick();
    assertThat(recorder.bytesTx.get()).isEqualTo(10);
    assertThat(recorder.bytesRx.get()).isEqualTo(10);
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
}
