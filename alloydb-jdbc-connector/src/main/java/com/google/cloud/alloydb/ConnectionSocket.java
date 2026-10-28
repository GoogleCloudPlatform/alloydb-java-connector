/*
 * Copyright 2024 Google LLC
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

import com.google.cloud.alloydb.connectors.v1.MetadataExchangeRequest;
import com.google.cloud.alloydb.connectors.v1.MetadataExchangeResponse;
import com.google.cloud.alloydb.connectors.v1.MetadataExchangeResponse.ResponseCode;
import com.google.common.annotations.VisibleForTesting;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.KeyStore.PasswordProtection;
import java.security.KeyStore.PrivateKeyEntry;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import org.conscrypt.Conscrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ConnectionSocket {

  private static final Logger logger = LoggerFactory.getLogger(ConnectionSocket.class);
  private static final String TLS_1_3 = "TLSv1.3";
  private static final String PQC_DOC =
      "https://github.com/GoogleCloudPlatform/alloydb-java-connector/blob/main/docs/pqc.md";
  private static final String X_509 = "X.509";
  private static final String PKIX = "PKIX";
  private static final String ROOT_CA_CERT = "rootCaCert";
  private static final String CLIENT_CERT = "clientCert";
  private static final int IO_TIMEOUT_MS = 30000;
  private static final int SERVER_SIDE_PROXY_PORT = 5433;

  // Guards the one-time log line announcing which JSSE provider serves AlloyDB connections.
  // CAS rather than a plain write so that concurrent first connections log it exactly once.
  private static final AtomicBoolean CONSCRYPT_ANNOUNCED = new AtomicBoolean();
  private static final AtomicBoolean CONSCRYPT_INCOMPLETE_ANNOUNCED = new AtomicBoolean();
  private static final AtomicBoolean AUTO_FALLBACK_ANNOUNCED = new AtomicBoolean();
  private static final AtomicBoolean JRE_PQC_ANNOUNCED = new AtomicBoolean();

  // The JDK that first negotiates ML-KEM in its own JSSE provider, via JEP 527.
  private static final int FIRST_PQC_JRE = 27;
  private static final int JRE_VERSION = jreVersion();

  private final ConnectionInfo connectionInfo;
  private final ConnectionConfig connectionConfig;
  private final KeyPair clientConnectorKeyPair;
  private final AccessTokenSupplier accessTokenSupplier;
  private final String userAgents;

  ConnectionSocket(
      ConnectionInfo connectionInfo,
      ConnectionConfig connectionConfig,
      KeyPair clientConnectorKeyPair,
      AccessTokenSupplier accessTokenSupplier,
      String userAgents) {
    this.connectionInfo = connectionInfo;
    this.connectionConfig = connectionConfig;
    this.clientConnectorKeyPair = clientConnectorKeyPair;
    this.accessTokenSupplier = accessTokenSupplier;
    this.userAgents = userAgents;
  }

  Socket connect() throws IOException {
    SSLSocket socket =
        buildSocket(
            connectionInfo.getCaCertificate(),
            connectionInfo.getCertificateChain(),
            this.clientConnectorKeyPair.getPrivate());

    String address;
    switch (connectionConfig.getIpType()) {
      case PUBLIC:
        address = connectionInfo.getPublicIpAddress();
        break;
      case PSC:
        // DNS names always end with a period (.), so remove it.
        address = connectionInfo.getPscDnsName().replaceFirst("\\.$", "");
        break;
      default:
        address = connectionInfo.getIpAddress();
        break;
    }

    if (address == null || address.isEmpty()) {
      throw new UserConfigException(
          String.format(
              "Instance does not have an address matching type: %s", connectionConfig.getIpType()));
    }

    logger.debug(String.format("[%s] Connecting to instance.", address));

    SSLParameters sslParameters = socket.getSSLParameters();
    // Set HTTPS as the endpoint identification algorithm
    // in order to verify the identity of the certificate as
    // suggested at https://stackoverflow.com/a/17979954/927514
    sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
    sslParameters.setServerNames(Collections.singletonList(new SNIHostName(address)));

    socket.setSSLParameters(sslParameters);
    socket.setKeepAlive(true);
    socket.setTcpNoDelay(true);
    socket.connect(new InetSocketAddress(address, SERVER_SIDE_PROXY_PORT));

    try {
      socket.startHandshake();
    } catch (IOException e) {
      logger.debug("TLS handshake failed!");
      throw e;
    }

    logHandshake(address, socket);

    // The metadata exchange must occur after the TLS connection is established
    // to avoid leaking sensitive information.
    metadataExchange(socket);

    logger.debug(String.format("[%s] Connected to instance successfully.", address));

    return socket;
  }

  // There is no standard JSSE API for the key exchange group that was negotiated -- see
  // https://bugs.openjdk.org/browse/JDK-8388519 -- so the protocol and cipher suite are all this
  // can report. docs/pqc.md describes how to read the negotiated group out of the provider's own
  // handshake trace.
  private void logHandshake(String address, SSLSocket socket) {
    if (!logger.isDebugEnabled()) {
      return;
    }
    SSLSession session = socket.getSession();
    logger.debug(
        String.format(
            "[%s] TLS handshake complete: protocol = %s, cipher suite = %s.",
            address, session.getProtocol(), session.getCipherSuite()));
  }

  private SSLSocket buildSocket(
      X509Certificate caCertificate,
      List<X509Certificate> certificateChain,
      PrivateKey privateKey) {
    try {
      // Decide which JSSE provider serves this connection first. A null selection means the
      // JRE's default provider, and the trust manager follows the same choice.
      Provider provider = selectProvider(connectionConfig.getTlsProvider());

      // First initialize a KeyManager with the ephemeral certificate
      // (including the chain of trust to the root CA cert) and the connector's private key.
      KeyManager[] keyManagers = initializeKeyManager(certificateChain, privateKey);

      // Next, initialize a TrustManager with the root CA certificate.
      TrustManager[] trustManagers = initializeTrustManager(caCertificate, provider);

      // Now, create a TLS 1.3 SSLContext initialized with the KeyManager and the TrustManager,
      // and create the SSL Socket.
      SSLContext sslContext = sslContextInstance(provider);
      if (logger.isDebugEnabled()) {
        logger.debug(
            String.format(
                "[%s] Using the %s JSSE provider for the TLS connection.",
                connectionConfig.getInstanceName(), sslContext.getProvider().getName()));
      }
      sslContext.init(keyManagers, trustManagers, new SecureRandom());
      return (SSLSocket) sslContext.getSocketFactory().createSocket();
    } catch (GeneralSecurityException | IOException ex) {
      throw new RuntimeException("Unable to create an SSL Context for the instance.", ex);
    }
  }

  /**
   * Selects the JSSE provider that serves this connection, returning {@code null} for the JRE's
   * default provider.
   *
   * <p>Conscrypt is requested as a {@link Provider} instance rather than by name, so the JCA
   * registry is never consulted and nothing about the application's global provider list changes.
   *
   * @throws UserConfigException when {@code alloydbTlsProvider} requires Conscrypt and this
   *     platform cannot supply it. That fails identically on every attempt, so {@link Connector}
   *     records it as a user error and does not refresh the connection info.
   */
  @VisibleForTesting
  static Provider selectProvider(TlsProvider tlsProvider) {
    return selectProvider(tlsProvider, ConnectionSocket::conscryptProvider, JRE_VERSION);
  }

  @VisibleForTesting
  static Provider selectProvider(TlsProvider tlsProvider, Provider conscrypt, int jreVersion) {
    return selectProvider(tlsProvider, () -> conscrypt, jreVersion);
  }

  /**
   * The provider decision. Conscrypt arrives as a supplier because resolving it loads a native
   * library: nothing below calls {@code get()} until a connection is known to want it, so a {@code
   * JDK} connection -- or an {@code AUTO} one on a JRE that needs no help -- never loads it.
   */
  @VisibleForTesting
  static Provider selectProvider(
      TlsProvider tlsProvider, Supplier<Provider> conscryptSupplier, int jreVersion) {
    if (tlsProvider == TlsProvider.JDK) {
      return null;
    }

    // AUTO exists to fill the gap before JDK 27. On a JRE that negotiates ML-KEM itself (JEP 527)
    // there is no gap, so do not substitute a different TLS stack to deliver what the JRE already
    // delivers. CONSCRYPT is a deliberate choice rather than a gap to fill, and is honored on
    // every version -- which is also what keeps it usable for testing Conscrypt on a new JRE.
    if (tlsProvider == TlsProvider.AUTO && jreSupportsPqc(jreVersion)) {
      // At INFO, not DEBUG: this is the only trace that the version assumption was made. A JRE
      // reporting 27 or later that does not in fact negotiate ML-KEM -- one that ships JEP 527
      // disabled, or backports the version without the feature -- gets neither Conscrypt nor
      // post-quantum key exchange, and no handshake reports the group it settled on.
      if (JRE_PQC_ANNOUNCED.compareAndSet(false, true)) {
        logger.info(
            "{} is set to {} and this JRE reports Java {}, which negotiates post-quantum key "
                + "exchange natively (JEP 527), so AlloyDB connections use the default JSSE "
                + "provider and Conscrypt is not loaded. The Java version is the only signal "
                + "available here, because JSSE does not report which key exchange groups a "
                + "provider supports. Set {} to {} if this runtime does not negotiate ML-KEM. "
                + "See {}.",
            ConnectionConfig.ALLOYDB_TLS_PROVIDER,
            TlsProvider.AUTO,
            jreVersion,
            ConnectionConfig.ALLOYDB_TLS_PROVIDER,
            TlsProvider.CONSCRYPT,
            PQC_DOC);
      }
      return null;
    }

    Provider conscrypt = conscryptSupplier.get();
    boolean required = tlsProvider == TlsProvider.CONSCRYPT;
    if (conscrypt == null) {
      if (required) {
        throw conscryptUnavailable();
      }
      // AUTO asked for Conscrypt and did not get it, which means these connections are not
      // post-quantum. Say so once: a silent classical handshake is the failure mode this feature
      // is easiest to get wrong, and nothing else reports the negotiated key exchange group.
      if (AUTO_FALLBACK_ANNOUNCED.compareAndSet(false, true)) {
        logger.info(
            "{} is set to {}, but Conscrypt is unavailable on this platform. Using the default "
                + "JSSE provider, which means AlloyDB connections will not use post-quantum key "
                + "exchange. See {}.",
            ConnectionConfig.ALLOYDB_TLS_PROVIDER,
            TlsProvider.AUTO,
            PQC_DOC);
      }
      return null;
    }

    String missing = missingService(conscrypt);
    if (missing != null) {
      if (required) {
        throw new UserConfigException(
            String.format(
                "%s is set to %s, but Conscrypt cannot provide %s. See %s.",
                ConnectionConfig.ALLOYDB_TLS_PROVIDER, TlsProvider.CONSCRYPT, missing, PQC_DOC));
      }
      if (CONSCRYPT_INCOMPLETE_ANNOUNCED.compareAndSet(false, true)) {
        logger.warn(
            "Conscrypt is available but cannot provide {}. Falling back to the default JSSE "
                + "provider, which means AlloyDB connections will not use post-quantum key "
                + "exchange. See {}.",
            missing,
            PQC_DOC);
      }
      return null;
    }

    // Scoped to the connections that asked for Conscrypt rather than stated flatly, because the
    // provider is chosen per connection: an application can run one data source on Conscrypt and
    // another on the JRE's provider. Logged once per process, so it cannot name them -- the
    // per-connection line in buildSocket does, at DEBUG.
    if (CONSCRYPT_ANNOUNCED.compareAndSet(false, true)) {
      logger.info(
          "Conscrypt will serve the AlloyDB connections that request it with {}. Connections left "
              + "on the JRE's default provider are unaffected. Logged once per process; enable "
              + "DEBUG logging to see the provider chosen for each connection.",
          ConnectionConfig.ALLOYDB_TLS_PROVIDER);
    }
    return conscrypt;
  }

  /**
   * Whether this JRE's own JSSE provider negotiates post-quantum key exchange.
   *
   * <p>Decided by version because JSSE exposes no way to ask. {@code SSLParameters.getNamedGroups}
   * reports the groups that have been configured, not the ones a provider supports, and there is no
   * API for the negotiated group either -- see https://bugs.openjdk.org/browse/JDK-8388519. A JRE
   * that backported JEP 527, or that ships it disabled, is therefore judged wrong here; {@code
   * CONSCRYPT} and {@code JDK} both remain available to say so explicitly.
   */
  private static boolean jreSupportsPqc(int jreVersion) {
    return jreVersion >= FIRST_PQC_JRE;
  }

  private static int jreVersion() {
    return parseJreVersion(System.getProperty("java.specification.version", ""));
  }

  /**
   * Parses a {@code java.specification.version} value into a major version: 8 for Java 8, where the
   * property reads {@code 1.8}, and the number itself from Java 9 on.
   *
   * <p>An unparseable value yields 0, which reads as a JRE without ML-KEM. That is the safe
   * direction: {@code AUTO} then offers Conscrypt, so the outcome is post-quantum key exchange
   * rather than silently skipping it, and the cost is loading a native library this JRE may not
   * have needed.
   */
  @VisibleForTesting
  static int parseJreVersion(String version) {
    String major = version.startsWith("1.") ? version.substring(2) : version;
    try {
      return Integer.parseInt(major);
    } catch (NumberFormatException e) {
      logger.debug("Could not parse java.specification.version '{}'.", version);
      return 0;
    }
  }

  /**
   * Describes the first service a connection needs that this provider does not offer, or returns
   * {@code null} when it can serve one.
   *
   * <p>Both services are checked, not just the {@link SSLContext}. {@link #initializeTrustManager}
   * asks the same provider for a {@code PKIX} {@code TrustManagerFactory}, and a provider that
   * served one but not the other would fail {@link #buildSocket} with a bare {@code
   * RuntimeException} -- so {@code AUTO} would fail every connection instead of falling back, and
   * {@link Connector} would classify it as neither a user error nor a refresh-worthy one.
   */
  private static String missingService(Provider provider) {
    if (provider.getService("SSLContext", TLS_1_3) == null) {
      return String.format("a %s SSLContext", TLS_1_3);
    }
    if (provider.getService("TrustManagerFactory", PKIX) == null) {
      return String.format("a %s TrustManagerFactory", PKIX);
    }
    return null;
  }

  private static SSLContext sslContextInstance(Provider provider) throws NoSuchAlgorithmException {
    return provider == null
        ? SSLContext.getInstance(TLS_1_3)
        : SSLContext.getInstance(TLS_1_3, provider);
  }

  /** Returns Conscrypt's provider, or {@code null} when it cannot be used on this platform. */
  private static Provider conscryptProvider() {
    return ConscryptHolder.PROVIDER;
  }

  /**
   * Holds Conscrypt's provider, resolved once per JVM on first use.
   *
   * <p>Building it loads a native library, which is the expensive part of this feature, so this
   * class is not initialized until a connection actually asks for Conscrypt -- a {@code JDK}
   * connection never triggers it. Class initialization provides the exactly-once resolution and the
   * safe publication of its result without a lock on the steady-state path, so concurrent dials do
   * not serialize on it. Unlike a JCA registry lookup there is nothing to re-check: the provider is
   * an object this class owns rather than global state an application can change.
   */
  private static final class ConscryptHolder {

    static final Provider PROVIDER = newConscryptProvider();

    private ConscryptHolder() {}
  }

  private static Provider newConscryptProvider() {
    try {
      if (!Conscrypt.isAvailable()) {
        // Conscrypt publishes native libraries for a fixed set of platforms. Everywhere else --
        // musl, 32-bit, s390x, ppc64le -- the jar is present and the library will not load.
        logger.debug("Conscrypt's native library is not available on this platform.");
        return null;
      }
      // provideTrustManager: Conscrypt does not register a TrustManagerFactory on OpenJDK unless
      // asked, and its SSLContext needs its own trust manager. See initializeTrustManager.
      return Conscrypt.newProviderBuilder().provideTrustManager(true).build();
    } catch (LinkageError | RuntimeException e) {
      // Conscrypt is a declared dependency, so this is reachable only when an application has
      // excluded it. Degrade the way an unsupported platform does rather than failing the
      // connection, and let CONSCRYPT report it for applications that require the provider.
      logger.debug("Conscrypt could not be loaded: {}", e.toString());
      return null;
    }
  }

  private static UserConfigException conscryptUnavailable() {
    return new UserConfigException(
        String.format(
            "%s is set to %s, but Conscrypt is unavailable on this platform. Conscrypt publishes "
                + "native libraries for glibc Linux and macOS on x86-64 and ARM64, and for 64-bit "
                + "Windows; it cannot be used elsewhere. Unset %s to use the JRE default provider. "
                + "See %s.",
            ConnectionConfig.ALLOYDB_TLS_PROVIDER,
            TlsProvider.CONSCRYPT,
            ConnectionConfig.ALLOYDB_TLS_PROVIDER,
            PQC_DOC));
  }

  /**
   * Builds the trust manager that verifies the instance's server certificate, from the same JSSE
   * provider that serves the {@link SSLContext}.
   *
   * <p>The two cannot be mixed. Conscrypt hands the trust manager BoringSSL's {@code GENERIC}
   * authType, which the JRE's trust manager rejects outright with {@code Unknown authType:
   * GENERIC}, and a Conscrypt trust manager inside a JRE SSLContext reflects into {@code
   * sun.security.ssl} and fails on JDK 17 and later. Conscrypt also registers its factory only
   * under {@code PKIX}, with none of the JRE's {@code X.509} aliases.
   *
   * <p>Hostname verification survives the switch: Conscrypt runs its own RFC 2818 verifier against
   * the SNI name the connection sets, and rejects a certificate that does not match the address.
   * {@code ConnectionSocketConscryptTest} covers that.
   */
  private TrustManager[] initializeTrustManager(X509Certificate caCertificate, Provider provider)
      throws KeyStoreException,
          IOException,
          NoSuchAlgorithmException,
          CertificateException,
          NoSuchProviderException {
    KeyStore trustedKeyStore = KeyStore.getInstance(KeyStore.getDefaultType());
    trustedKeyStore.load(
        null, // don't load the key store from an input stream
        null // there is no password
        );
    trustedKeyStore.setCertificateEntry(ROOT_CA_CERT, caCertificate);
    TrustManagerFactory trustManagerFactory =
        provider == null
            ? TrustManagerFactory.getInstance(X_509)
            : TrustManagerFactory.getInstance(PKIX, provider);
    trustManagerFactory.init(trustedKeyStore);
    return trustManagerFactory.getTrustManagers();
  }

  private KeyManager[] initializeKeyManager(
      List<X509Certificate> certificateChain, PrivateKey privateKey)
      throws KeyStoreException,
          IOException,
          NoSuchAlgorithmException,
          CertificateException,
          UnrecoverableKeyException {
    KeyStore clientAuthenticationKeyStore = KeyStore.getInstance(KeyStore.getDefaultType());
    clientAuthenticationKeyStore.load(
        null, // don't load the key store from an input stream
        null // there is no password
        );
    List<Certificate> chain = new ArrayList<>(certificateChain);
    Certificate[] chainArray = chain.toArray(new Certificate[] {});
    PrivateKeyEntry privateKeyEntry = new PrivateKeyEntry(privateKey, chainArray);
    clientAuthenticationKeyStore.setEntry(
        CLIENT_CERT, privateKeyEntry, new PasswordProtection(new char[0]) /* no password */);
    KeyManagerFactory keyManagerFactory =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keyManagerFactory.init(clientAuthenticationKeyStore, new char[0] /* no password */);
    return keyManagerFactory.getKeyManagers();
  }

  // metadataExchange sends metadata about the connection prior to the database
  // protocol taking over. The exchange consists of the following steps:
  //
  //  1. Prepare a MetadataExchangeRequest including the IAM Principal's OAuth2
  //     token, the user agent, and the requested authentication type.
  //
  //  2. Write the size of the message as a big endian uint32 (4 bytes) to the
  //     server followed by the serialized bytes of message. The length does
  //     not include the initial four bytes.
  //
  //  3. Read a big endian uint32 (4 bytes) from the server. This is the
  //     MetadataExchangeResponse message length and does not include the
  //     initial four bytes.
  //
  //  4. Read the response using the message length in step 3. If the response
  //     is not OK, return the response's error. If there is no error, the
  //     metadata exchange has succeeded and the connection is complete.
  //
  // Subsequent interactions with the test server use the database protocol.
  private void metadataExchange(SSLSocket socket) throws IOException {

    logger.debug("Metadata exchange initiated.");

    MetadataExchangeRequest.AuthType authType = MetadataExchangeRequest.AuthType.DB_NATIVE;
    if (connectionConfig.getAuthType().equals(AuthType.IAM)) {
      authType = MetadataExchangeRequest.AuthType.AUTO_IAM;
    }

    String tokenValue = accessTokenSupplier.getTokenValue();
    MetadataExchangeRequest request =
        MetadataExchangeRequest.newBuilder()
            .setAuthType(authType)
            .setOauth2Token(tokenValue)
            .setUserAgent(userAgents)
            .build();

    // Write data to the server.
    DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    out.writeInt(request.getSerializedSize());
    out.write(request.toByteArray());
    out.flush();

    // Set timeout for read.
    socket.setSoTimeout(IO_TIMEOUT_MS);

    // Read data from the server.
    DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
    int respSize = in.readInt();
    byte[] respData = new byte[respSize];
    in.readFully(respData);

    // Clear the timeout.
    socket.setSoTimeout(0);

    // Parse the response and raise a MetadataExchangeException if it is not OK.
    MetadataExchangeResponse response = MetadataExchangeResponse.parseFrom(respData);
    if (response == null || !response.getResponseCode().equals(ResponseCode.OK)) {
      String error = response == null ? "no response" : response.getError();
      logger.debug(String.format("Metadata exchange failed: %s", error));

      throw new MetadataExchangeException(
          response != null ? response.getError() : "Metadata exchange response is null.");
    }

    logger.debug("Metadata exchange completed successfully.");
  }
}
