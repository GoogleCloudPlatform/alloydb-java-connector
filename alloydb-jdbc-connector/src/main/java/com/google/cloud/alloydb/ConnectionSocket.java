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
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ConnectionSocket {

  private static final Logger logger = LoggerFactory.getLogger(ConnectionSocket.class);
  private static final String TLS_1_3 = "TLSv1.3";
  private static final String X_509 = "X.509";
  private static final String ROOT_CA_CERT = "rootCaCert";
  private static final String CLIENT_CERT = "clientCert";
  private static final String USER_AGENT = "alloydb-java-connector/" + Version.VERSION;
  private static final int IO_TIMEOUT_MS = 30000;
  private static final int SERVER_SIDE_PROXY_PORT = 5433;
  private final ConnectionInfo connectionInfo;
  private final ConnectionConfig connectionConfig;
  private final KeyPair clientConnectorKeyPair;
  private final AccessTokenSupplier accessTokenSupplier;

  ConnectionSocket(
      ConnectionInfo connectionInfo,
      ConnectionConfig connectionConfig,
      KeyPair clientConnectorKeyPair,
      AccessTokenSupplier accessTokenSupplier) {
    this.connectionInfo = connectionInfo;
    this.connectionConfig = connectionConfig;
    this.clientConnectorKeyPair = clientConnectorKeyPair;
    this.accessTokenSupplier = accessTokenSupplier;
  }

  Socket connect() throws IOException {
    SSLSocket socket =
        buildSocket(
            connectionInfo.getCaCertificate(),
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

    // The metadata exchange must occur after the TLS connection is established
    // to avoid leaking sensitive information.
    metadataExchange(socket);

    return socket;
  }

  private SSLSocket buildSocket(
      X509Certificate caCertificate,
      List<X509Certificate> certificateChain,
      PrivateKey privateKey) {
    try {
      // First initialize a KeyManager with the ephemeral certificate
      // (including the chain of trust to the root CA cert) and the connector's private key.
      KeyManager[] keyManagers = initializeKeyManager(certificateChain, privateKey);

      // Next, initialize a TrustManager with the root CA certificate.
      TrustManager[] trustManagers = initializeTrustManager(caCertificate);

      // Now, create a TLS 1.3 SSLContext initialized with the KeyManager and the TrustManager,
      // and create the SSL Socket.
      SSLContext sslContext = SSLContext.getInstance(TLS_1_3);
      sslContext.init(keyManagers, trustManagers, new SecureRandom());
      return (SSLSocket) sslContext.getSocketFactory().createSocket();
    } catch (GeneralSecurityException | IOException ex) {
      throw new RuntimeException("Unable to create an SSL Context for the instance.", ex);
    }
  }

  private TrustManager[] initializeTrustManager(X509Certificate caCertificate)
      throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException {
    KeyStore trustedKeyStore = KeyStore.getInstance(KeyStore.getDefaultType());
    trustedKeyStore.load(
        null, // don't load the key store from an input stream
        null // there is no password
        );
    trustedKeyStore.setCertificateEntry(ROOT_CA_CERT, caCertificate);
    TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(X_509);
    trustManagerFactory.init(trustedKeyStore);
    return trustManagerFactory.getTrustManagers();
  }

  private KeyManager[] initializeKeyManager(
      List<X509Certificate> certificateChain, PrivateKey privateKey)
      throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException,
          UnrecoverableKeyException {
    KeyStore clientAuthenticationKeyStore = KeyStore.getInstance(KeyStore.getDefaultType());
    clientAuthenticationKeyStore.load(
        null, // don't load the key store from an input stream
        null // there is no password
        );
    List<Certificate> chain = new ArrayList<>();
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
            .setUserAgent(USER_AGENT)
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

    // Parse the response and raise a RuntimeException if it is not OK.
    MetadataExchangeResponse response = MetadataExchangeResponse.parseFrom(respData);
    if (response == null || !response.getResponseCode().equals(ResponseCode.OK)) {
      throw new RuntimeException(
          response != null ? response.getError() : "Metadata exchange response is null.");
    }

    logger.debug("Metadata exchange completed successfully.");
  }
}
