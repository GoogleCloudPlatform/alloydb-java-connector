/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
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

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.security.KeyStore.PasswordProtection;
import java.security.KeyStore.PrivateKeyEntry;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Exercises the BCJSSE code path with the real Bouncy Castle providers and a real TLS 1.3
 * handshake.
 *
 * <p>The connector hands a BCJSSE SSLContext key and trust managers built by the <em>default</em>
 * JSSE provider, and relies on {@code setEndpointIdentificationAlgorithm("HTTPS")} to verify the
 * server's identity. That mix is security critical, so it is tested rather than assumed: a
 * certificate that does not match the name being connected to must fail the handshake under BCJSSE
 * exactly as it does under the JRE default provider.
 *
 * <p>These tests do not assert that a post-quantum group was negotiated. The peer here is the JRE's
 * own TLS server, which offers no ML-KEM group before JDK 27, so no hybrid group can be agreed. See
 * docs/pqc.md for how to confirm the negotiated group against a real AlloyDB instance.
 */
public class ConnectionSocketBouncyCastleTest {

  private static final String BC_JSSE = "BCJSSE";
  private static final byte[] LOOPBACK = new byte[] {127, 0, 0, 1};
  private static final String LOOPBACK_ADDRESS = "127.0.0.1";

  private TlsServer server;
  private TlsServer mismatchedServer;
  private ProviderSnapshot bcJsse;
  private ProviderSnapshot bcJce;

  @Before
  public void setUp() throws Exception {
    bcJsse = ProviderSnapshot.of(BC_JSSE);
    bcJce = ProviderSnapshot.of(BouncyCastleProvider.PROVIDER_NAME);

    // Pass the BC JCE provider explicitly: without it BCJSSE picks up SunJCE's ML-KEM
    // implementation on JDK 24+ and disables the ML-KEM named groups. See bc-java issue #2252.
    BouncyCastleProvider bcProvider = new BouncyCastleProvider();
    Security.addProvider(bcProvider);
    Security.addProvider(new BouncyCastleJsseProvider(bcProvider));

    server = new TlsServer(TestCertificates.INSTANCE.getServerCertificate());
    server.start();
    mismatchedServer = new TlsServer(TestCertificates.INSTANCE.getMismatchedServerCertificate());
    mismatchedServer.start();
  }

  @After
  public void tearDown() {
    if (server != null) {
      server.stop();
    }
    if (mismatchedServer != null) {
      mismatchedServer.stop();
    }
    bcJsse.restore();
    bcJce.restore();
  }

  @Test
  public void bouncyCastleServesTheSslContext() throws Exception {
    SSLContext context = ConnectionSocket.getSslContextInstance(TlsProvider.BOUNCY_CASTLE);

    assertThat(context.getProvider().getName()).isEqualTo(BC_JSSE);
  }

  @Test
  public void handshakeSucceedsWithMatchingCertificate() throws Exception {
    SSLSocket socket = clientSocket(server);

    socket.startHandshake();

    assertThat(socket.getSession().getProtocol()).isEqualTo("TLSv1.3");
    assertThat(socket.getSession().getPeerCertificates()[0])
        .isEqualTo(TestCertificates.INSTANCE.getServerCertificate());
    socket.close();
  }

  /**
   * Endpoint identification must survive the switch to BCJSSE. The certificate here is signed by
   * the CA the client trusts, so only the identity check can reject it.
   */
  @Test
  public void handshakeFailsWhenCertificateDoesNotMatchTheAddress() throws Exception {
    SSLSocket socket = clientSocket(mismatchedServer);

    assertThrows(IOException.class, socket::startHandshake);

    socket.close();
  }

  /** The control: the JRE default provider rejects the same certificate. */
  @Test
  public void jdkProviderAlsoFailsWhenCertificateDoesNotMatchTheAddress() throws Exception {
    SSLSocket socket = clientSocket(mismatchedServer, TlsProvider.JDK);

    assertThrows(IOException.class, socket::startHandshake);

    socket.close();
  }

  /** The control: the JRE default provider accepts the same certificate the connector accepts. */
  @Test
  public void jdkProviderAcceptsTheMatchingCertificate() throws Exception {
    SSLSocket socket = clientSocket(server, TlsProvider.JDK);

    socket.startHandshake();

    assertThat(socket.getSession().getPeerCertificates()[0])
        .isEqualTo(TestCertificates.INSTANCE.getServerCertificate());
    socket.close();
  }

  @Test
  public void endpointIdentificationIsRetainedByBouncyCastle() throws Exception {
    SSLSocket socket = clientSocket(server);

    assertThat(socket.getSSLParameters().getEndpointIdentificationAlgorithm()).isEqualTo("HTTPS");

    socket.close();
  }

  /**
   * Builds a client socket the way {@link ConnectionSocket} builds one: a BCJSSE SSLContext, a
   * trust manager from the default provider, HTTPS endpoint identification and the target address
   * as the SNI name.
   *
   * <p>Setting the SNI name is what makes identity verification work here. The JRE trust manager
   * checks the SNI name first and only falls back to the socket's peer host, which BCJSSE leaves
   * null. A mismatch therefore surfaces as "Hostname or IP address is undefined" from the fallback
   * rather than as a name mismatch -- confusing, but it fails closed, and dropping the SNI name
   * would make every handshake fail that way.
   */
  private SSLSocket clientSocket(TlsServer target) throws Exception {
    return clientSocket(target, TlsProvider.BOUNCY_CASTLE);
  }

  @SuppressWarnings("AddressSelection")
  private SSLSocket clientSocket(TlsServer target, TlsProvider tlsProvider) throws Exception {
    SSLContext sslContext = ConnectionSocket.getSslContextInstance(tlsProvider);
    sslContext.init(null, trustManagers(), new SecureRandom());

    SSLSocket socket = (SSLSocket) sslContext.getSocketFactory().createSocket();

    SSLParameters sslParameters = socket.getSSLParameters();
    sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
    sslParameters.setServerNames(Collections.singletonList(new SNIHostName(LOOPBACK_ADDRESS)));
    socket.setSSLParameters(sslParameters);
    socket.connect(new InetSocketAddress(LOOPBACK_ADDRESS, target.getPort()));

    return socket;
  }

  private static TrustManager[] trustManagers() throws Exception {
    KeyStore trustedKeyStore = KeyStore.getInstance(KeyStore.getDefaultType());
    trustedKeyStore.load(null, null);
    trustedKeyStore.setCertificateEntry(
        "rootCaCert", TestCertificates.INSTANCE.getRootCertificate());
    TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance("X.509");
    trustManagerFactory.init(trustedKeyStore);
    return trustManagerFactory.getTrustManagers();
  }

  /** A TLS 1.3 server on an ephemeral loopback port that completes handshakes and hangs up. */
  private static class TlsServer {

    private final AtomicInteger port = new AtomicInteger();
    private final X509Certificate certificate;
    private volatile SSLServerSocket serverSocket;
    private Thread thread;

    TlsServer(X509Certificate certificate) {
      this.certificate = certificate;
    }

    void start() throws Exception {
      CountDownLatch started = new CountDownLatch(1);
      thread =
          new Thread(
              () -> {
                try {
                  SSLContext sslContext = SSLContext.getInstance("TLSv1.3");
                  sslContext.init(keyManagers(certificate), null, new SecureRandom());
                  serverSocket =
                      (SSLServerSocket)
                          sslContext
                              .getServerSocketFactory()
                              .createServerSocket(0, 5, InetAddress.getByAddress(LOOPBACK));
                  port.set(serverSocket.getLocalPort());
                  started.countDown();
                  for (; ; ) {
                    try (SSLSocket socket = (SSLSocket) serverSocket.accept()) {
                      socket.startHandshake();
                    } catch (IOException e) {
                      // A client that rejects the certificate drops the connection mid-handshake.
                      // Keep serving the next one.
                    }
                  }
                } catch (Exception e) {
                  started.countDown();
                  // The socket is closed on stop(), which lands here and ends the thread.
                }
              });
      thread.setDaemon(true);
      thread.start();
      started.await();
    }

    int getPort() {
      return port.get();
    }

    void stop() {
      try {
        if (serverSocket != null) {
          serverSocket.close();
        }
      } catch (IOException e) {
        // Nothing useful to do while tearing down.
      }
      thread.interrupt();
    }

    private static KeyManager[] keyManagers(X509Certificate certificate) throws Exception {
      KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
      keyStore.load(null, null);
      PrivateKeyEntry entry =
          new PrivateKeyEntry(
              TestCertificates.INSTANCE.getServerKey().getPrivate(),
              new Certificate[] {certificate});
      keyStore.setEntry("serverCert", entry, new PasswordProtection(new char[0]));
      KeyManagerFactory keyManagerFactory =
          KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      keyManagerFactory.init(keyStore, new char[0]);
      return keyManagerFactory.getKeyManagers();
    }
  }
}
