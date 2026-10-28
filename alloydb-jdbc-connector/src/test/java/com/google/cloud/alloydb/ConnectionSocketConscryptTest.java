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
import static org.junit.Assume.assumeFalse;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.security.KeyStore.PasswordProtection;
import java.security.KeyStore.PrivateKeyEntry;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import org.conscrypt.Conscrypt;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Exercises the Conscrypt code path with the real provider and a real TLS 1.3 handshake.
 *
 * <p>Two things here are security critical and so are tested rather than assumed. The connector
 * pairs a Conscrypt {@link SSLContext} with a Conscrypt trust manager and a key manager from the
 * JRE's default provider, and relies on {@code setEndpointIdentificationAlgorithm("HTTPS")} to
 * verify the server's identity: a certificate that does not match the name being connected to must
 * fail the handshake. And the point of selecting Conscrypt at all is post-quantum key exchange, so
 * one test pins the client to the hybrid group and proves a handshake still completes.
 */
public class ConnectionSocketConscryptTest {

  private static final byte[] LOOPBACK = new byte[] {127, 0, 0, 1};
  private static final String LOOPBACK_ADDRESS = "127.0.0.1";
  private static final String HYBRID_GROUP = "X25519MLKEM768";

  // JEP 527 ships ML-KEM in SunJSSE from JDK 27 on, so from there the JRE's provider is no longer
  // a peer that lacks the hybrid group.
  private static final boolean JRE_PROVIDER_HAS_ML_KEM = specificationVersion() >= 27;

  private Provider conscrypt;
  private TlsServer server;
  private TlsServer mismatchedServer;

  @Before
  public void setUp() throws Exception {
    NativeImage.assumeConscryptIsLoadable();
    conscrypt = ConnectionSocket.selectProvider(TlsProvider.CONSCRYPT);

    server = new TlsServer(TestCertificates.INSTANCE.getServerCertificate(), null);
    server.start();
    mismatchedServer =
        new TlsServer(TestCertificates.INSTANCE.getMismatchedServerCertificate(), null);
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
  }

  /**
   * Asserted rather than assumed. CI runs this suite on every supported JDK, on platforms Conscrypt
   * publishes a native library for, and it is the only coverage that post-quantum key exchange
   * works on the older ones. An {@code assumeTrue} here would turn a Conscrypt that stopped loading
   * into a silently skipped suite and a green build.
   *
   * <p>On a platform Conscrypt does not support -- musl, 32-bit, s390x, ppc64le -- this suite
   * fails. That is the intended signal: the connector still falls back to a classical handshake
   * there, as docs/pqc.md describes, but nothing in this file can be verified. A native image is
   * the one exception, skipped above, because it cannot load the library by construction rather
   * than because something regressed.
   */
  @Test
  public void conscryptIsAvailableOnThisPlatform() {
    NativeImage.assumeConscryptIsLoadable();

    assertThat(Conscrypt.isAvailable()).isTrue();
  }

  @Test
  public void conscryptServesTheSslContext() throws Exception {
    SSLContext context = SSLContext.getInstance("TLSv1.3", conscrypt);

    assertThat(context.getProvider().getName()).isEqualTo("Conscrypt");
  }

  /**
   * Conscrypt does not register a TrustManagerFactory on OpenJDK unless the provider is built with
   * {@code provideTrustManager(true)}, and registers it only under {@code PKIX}. The connector
   * depends on both, so both are asserted here rather than left to a handshake failure.
   */
  @Test
  public void conscryptProvidesAPkixTrustManagerFactory() {
    assertThat(conscrypt.getService("TrustManagerFactory", "PKIX")).isNotNull();
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
   * Endpoint identification must survive the switch to Conscrypt. The certificate here is signed by
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
    SSLSocket socket = clientSocket(mismatchedServer, TlsProvider.JDK, null);

    assertThrows(IOException.class, socket::startHandshake);

    socket.close();
  }

  /** The control: the JRE default provider accepts the same certificate the connector accepts. */
  @Test
  public void jdkProviderAcceptsTheMatchingCertificate() throws Exception {
    SSLSocket socket = clientSocket(server, TlsProvider.JDK, null);

    socket.startHandshake();

    assertThat(socket.getSession().getPeerCertificates()[0])
        .isEqualTo(TestCertificates.INSTANCE.getServerCertificate());
    socket.close();
  }

  @Test
  public void endpointIdentificationIsRetainedByConscrypt() throws Exception {
    SSLSocket socket = clientSocket(server);

    assertThat(socket.getSSLParameters().getEndpointIdentificationAlgorithm()).isEqualTo("HTTPS");

    socket.close();
  }

  /**
   * The reason this provider is offered at all: a post-quantum key exchange group can actually be
   * negotiated, on a JDK whose own provider has no ML-KEM.
   *
   * <p>The client offers {@link #HYBRID_GROUP} and nothing else, so a completed handshake can only
   * have agreed on it. There is no JSSE API for the negotiated group (see
   * https://bugs.openjdk.org/browse/JDK-8388519), which is why this is asserted by restricting the
   * offer rather than by reading it back off the session.
   */
  @Test
  public void postQuantumGroupIsNegotiated() throws Exception {
    TlsServer pqcServer =
        new TlsServer(TestCertificates.INSTANCE.getServerCertificate(), conscrypt);
    pqcServer.start();
    try {
      SSLSocket socket = clientSocket(pqcServer, TlsProvider.CONSCRYPT, HYBRID_GROUP);

      socket.startHandshake();

      assertThat(socket.getSession().getProtocol()).isEqualTo("TLSv1.3");
      socket.close();
    } finally {
      pqcServer.stop();
    }
  }

  /**
   * And the converse: a peer without ML-KEM cannot satisfy a client that requires it.
   *
   * <p>{@link #server} is served by the JRE's provider, which is what makes it a peer without
   * ML-KEM -- but only before JDK 27, where JEP 527 gives SunJSSE its own ML-KEM support. From
   * there this peer can satisfy the client and the premise no longer holds, so the test is skipped
   * rather than left to turn red the day JDK 27 joins the CI matrix.
   */
  @Test
  public void postQuantumOnlyClientFailsAgainstAPeerWithoutMlKem() throws Exception {
    assumeFalse(
        "JDK 27 and later negotiate ML-KEM natively (JEP 527), so this peer is not without it",
        JRE_PROVIDER_HAS_ML_KEM);
    SSLSocket socket = clientSocket(server, TlsProvider.CONSCRYPT, HYBRID_GROUP);

    assertThrows(IOException.class, socket::startHandshake);

    socket.close();
  }

  private SSLSocket clientSocket(TlsServer target) throws Exception {
    return clientSocket(target, TlsProvider.CONSCRYPT, null);
  }

  /**
   * Builds a client socket the way {@link ConnectionSocket} builds one: the selected provider's
   * SSLContext, a trust manager from that same provider, HTTPS endpoint identification and the
   * target address as the SNI name.
   */
  @SuppressWarnings("AddressSelection")
  private SSLSocket clientSocket(TlsServer target, TlsProvider tlsProvider, String namedGroup)
      throws Exception {
    Provider provider = ConnectionSocket.selectProvider(tlsProvider);
    SSLContext sslContext =
        provider == null
            ? SSLContext.getInstance("TLSv1.3")
            : SSLContext.getInstance("TLSv1.3", provider);
    sslContext.init(null, trustManagers(provider), new SecureRandom());

    SSLSocket socket = (SSLSocket) sslContext.getSocketFactory().createSocket();

    SSLParameters sslParameters = socket.getSSLParameters();
    sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
    sslParameters.setServerNames(Collections.singletonList(new SNIHostName(LOOPBACK_ADDRESS)));
    socket.setSSLParameters(sslParameters);
    if (namedGroup != null) {
      // Set after setSSLParameters, not before: on JDK 24 and later SSLParameters carries a
      // namedGroups field that Conscrypt reads back, so applying parameters afterwards would
      // reset the restriction to the default group list.
      Conscrypt.setNamedGroups(socket, new String[] {namedGroup});
    }
    socket.connect(new InetSocketAddress(LOOPBACK_ADDRESS, target.getPort()));

    return socket;
  }

  /** The JDK major version: 8 for Java 8, where the property reads "1.8", and 9+ verbatim. */
  private static int specificationVersion() {
    String version = System.getProperty("java.specification.version", "");
    String major = version.startsWith("1.") ? version.substring(2) : version;
    try {
      return Integer.parseInt(major);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static TrustManager[] trustManagers(Provider provider) throws Exception {
    KeyStore trustedKeyStore = KeyStore.getInstance(KeyStore.getDefaultType());
    trustedKeyStore.load(null, null);
    trustedKeyStore.setCertificateEntry(
        "rootCaCert", TestCertificates.INSTANCE.getRootCertificate());
    TrustManagerFactory trustManagerFactory =
        provider == null
            ? TrustManagerFactory.getInstance("X.509")
            : TrustManagerFactory.getInstance("PKIX", provider);
    trustManagerFactory.init(trustedKeyStore);
    return trustManagerFactory.getTrustManagers();
  }

  /**
   * A TLS 1.3 server on an ephemeral loopback port that completes handshakes and hangs up. Served
   * by the JRE's provider unless a provider is supplied, which is how a peer that offers ML-KEM is
   * stood up on a JDK that otherwise has none.
   */
  private static class TlsServer {

    private final AtomicInteger port = new AtomicInteger();
    private final X509Certificate certificate;
    private final Provider provider;
    private volatile SSLServerSocket serverSocket;
    private Thread thread;

    TlsServer(X509Certificate certificate, Provider provider) {
      this.certificate = certificate;
      this.provider = provider;
    }

    void start() throws Exception {
      CountDownLatch started = new CountDownLatch(1);
      AtomicReference<Throwable> startFailure = new AtomicReference<>();
      thread =
          new Thread(
              () -> {
                try {
                  SSLContext sslContext =
                      provider == null
                          ? SSLContext.getInstance("TLSv1.3")
                          : SSLContext.getInstance("TLSv1.3", provider);
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
                  if (port.get() == 0) {
                    // Nothing ever listened. Without this, getPort() hands back 0 and the test
                    // fails on connecting to port 0 rather than on whatever actually went wrong.
                    startFailure.set(e);
                  }
                  // Otherwise stop() closed the socket, which lands here and ends the thread.
                  started.countDown();
                }
              });
      thread.setDaemon(true);
      thread.start();
      started.await();
      if (startFailure.get() != null) {
        throw new IllegalStateException("The test TLS server failed to start.", startFailure.get());
      }
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
