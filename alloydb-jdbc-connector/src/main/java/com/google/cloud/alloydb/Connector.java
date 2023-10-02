/*
 * Copyright 2023 Google LLC
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

import com.google.cloud.alloydb.v1.InstanceName;
import com.google.common.base.Objects;
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
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

class Connector {

  private static final String TLS_1_3 = "TLSv1.3";
  private static final String X_509 = "X.509";
  private static final double RATE_LIMIT_PER_SEC = 1.0 / 30.0;
  private static final int SERVER_SIDE_PROXY_PORT = 5433;
  private static final String ROOT_CA_CERT = "rootCaCert";
  private static final String CLIENT_CERT = "clientCert";
  private final ScheduledExecutorService executor;
  private final ConnectionInfoRepository connectionInfoRepo;
  private final KeyPair clientConnectorKeyPair;
  private final ConnectionInfoCacheFactory connectionInfoCacheFactory;
  private final ConcurrentHashMap<InstanceName, ConnectionInfoCache> instances;

  Connector(
      ScheduledExecutorService executor,
      ConnectionInfoRepository connectionInfoRepo,
      KeyPair clientConnectorKeyPair,
      ConnectionInfoCacheFactory connectionInfoCacheFactory,
      ConcurrentHashMap<InstanceName, ConnectionInfoCache> instances) {
    this.executor = executor;
    this.connectionInfoRepo = connectionInfoRepo;
    this.clientConnectorKeyPair = clientConnectorKeyPair;
    this.connectionInfoCacheFactory = connectionInfoCacheFactory;
    this.instances = instances;
  }

  private static SSLSocket buildSocket(
      X509Certificate clientCertificate,
      List<X509Certificate> certificateChain,
      PrivateKey privateKey) {
    try {
      // First initialize a KeyManager with the ephemeral certificate
      // (including the chain of trust to the root CA cert) and the connector's private key.
      KeyManager[] keyManagers =
          initializeKeyManager(clientCertificate, certificateChain, privateKey);

      // Next, initialize a TrustManager with the root CA certificate.
      TrustManager[] trustManagers = initializeTrustManager(certificateChain);

      // Now, create a TLS 1.3 SSLContext initialized with the KeyManager and the TrustManager,
      // and create the SSL Socket.
      SSLContext sslContext = SSLContext.getInstance(TLS_1_3);
      sslContext.init(keyManagers, trustManagers, new SecureRandom());
      return (SSLSocket) sslContext.getSocketFactory().createSocket();
    } catch (GeneralSecurityException | IOException ex) {
      throw new RuntimeException("Unable to create an SSL Context for the instance.", ex);
    }
  }

  private static TrustManager[] initializeTrustManager(List<X509Certificate> certificateChain)
      throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException {
    KeyStore trustedKeyStore = KeyStore.getInstance(KeyStore.getDefaultType());
    trustedKeyStore.load(
        null, // don't load the key store from an input stream
        null // there is no password
        );
    trustedKeyStore.setCertificateEntry(
        ROOT_CA_CERT,
        certificateChain.get(certificateChain.size() - 1) // root CA cert is last in the chain
        );
    TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(X_509);
    trustManagerFactory.init(trustedKeyStore);
    return trustManagerFactory.getTrustManagers();
  }

  private static KeyManager[] initializeKeyManager(
      X509Certificate clientCertificate,
      List<X509Certificate> certificateChain,
      PrivateKey privateKey)
      throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException,
          UnrecoverableKeyException {
    KeyStore clientAuthenticationKeyStore = KeyStore.getInstance(KeyStore.getDefaultType());
    clientAuthenticationKeyStore.load(
        null, // don't load the key store from an input stream
        null // there is no password
        );
    List<Certificate> chain = new ArrayList<>();
    chain.add(clientCertificate);
    chain.addAll(certificateChain);
    Certificate[] chainArray = chain.toArray(new Certificate[] {});
    PrivateKeyEntry privateKeyEntry = new PrivateKeyEntry(privateKey, chainArray);
    clientAuthenticationKeyStore.setEntry(
        CLIENT_CERT, privateKeyEntry, new PasswordProtection(new char[0]) /* no password */);
    KeyManagerFactory keyManagerFactory =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keyManagerFactory.init(clientAuthenticationKeyStore, new char[0] /* no password */);
    return keyManagerFactory.getKeyManagers();
  }

  Socket connect(InstanceName instanceName) throws IOException {
    ConnectionInfoCache connectionInfoCache =
        instances.computeIfAbsent(
            instanceName,
            k -> {
              DefaultRateLimiter rateLimiter = new DefaultRateLimiter(RATE_LIMIT_PER_SEC);
              return connectionInfoCacheFactory.create(
                  this.executor,
                  this.connectionInfoRepo,
                  instanceName,
                  this.clientConnectorKeyPair,
                  new RefreshCalculator(),
                  rateLimiter);
            });

    ConnectionInfo connectionInfo = connectionInfoCache.getConnectionInfo();

    try {
      SSLSocket socket =
          buildSocket(
              connectionInfo.getClientCertificate(),
              connectionInfo.getCertificateChain(),
              this.clientConnectorKeyPair.getPrivate());

      // Use the instance's IP address as a HostName.
      SSLParameters sslParameters = socket.getSSLParameters();
      sslParameters.setServerNames(
          Collections.singletonList(new SNIHostName(connectionInfo.getIpAddress())));

      socket.setKeepAlive(true);
      socket.setTcpNoDelay(true);
      socket.connect(new InetSocketAddress(connectionInfo.getIpAddress(), SERVER_SIDE_PROXY_PORT));
      socket.startHandshake();
      return socket;
    } catch (IOException e) {
      connectionInfoCache.forceRefresh();
      // The Socket methods above will throw an IOException or a SocketException (subclass of
      // IOException). Catch that exception, trigger a refresh, and then throw it again so
      // the caller sees the problem, but the connector will have a refreshed certificate on the
      // next invocation.
      throw e;
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Connector)) {
      return false;
    }
    Connector that = (Connector) o;
    return Objects.equal(executor, that.executor)
        && Objects.equal(connectionInfoRepo, that.connectionInfoRepo)
        && Objects.equal(clientConnectorKeyPair, that.clientConnectorKeyPair)
        && Objects.equal(connectionInfoCacheFactory, that.connectionInfoCacheFactory)
        && Objects.equal(instances, that.instances);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(
        executor,
        connectionInfoRepo,
        clientConnectorKeyPair,
        connectionInfoCacheFactory,
        instances);
  }
}
