/*
 * Copyright 2024 Google LLC
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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.cloud.alloydb.connectors.v1.MetadataExchangeResponse;
import com.google.cloud.alloydb.connectors.v1.MetadataExchangeResponse.ResponseCode;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.security.KeyFactory;
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
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

class FakeSslServer {

  private static final String TLS_1_3 = "TLSv1.3";
  private static final String X_509 = "X.509";
  private static final String ROOT_CA_CERT = "rootCaCert";
  private static final int IO_TIMEOUT_MS = 30000;
  private String message;
  private Thread thread;

  FakeSslServer(String message) {
    this.message = message;
  }

  int start(final String ip) throws InterruptedException {
    final CountDownLatch countDownLatch = new CountDownLatch(1);
    final AtomicInteger pickedPort = new AtomicInteger();

    this.thread =
        new Thread(
            () -> {
              try {
                CertificateFactory certFactory = CertificateFactory.getInstance("X.509");

                PKCS8EncodedKeySpec keySpec =
                    new PKCS8EncodedKeySpec(
                        decodeBase64StripWhitespace(TestKeys.SERVER_CERT_PRIVATE_KEY));
                PrivateKey privateKey = KeyFactory.getInstance("RSA").generatePrivate(keySpec);

                final X509Certificate signingCaCert =
                    (X509Certificate)
                        certFactory.generateCertificate(
                            new ByteArrayInputStream(TestKeys.SIGNING_CA_CERT.getBytes(UTF_8)));

                KeyManager[] keyManagers =
                    initializeKeyManager(
                        new Certificate[] {
                          certFactory.generateCertificate(
                              new ByteArrayInputStream(TestKeys.SERVER_CERT.getBytes(UTF_8)))
                        },
                        privateKey);
                TrustManager[] trustManagers = initializeTrustManager(signingCaCert);

                SSLContext sslContext = SSLContext.getInstance(TLS_1_3);
                sslContext.init(keyManagers, trustManagers, new SecureRandom());
                SSLServerSocketFactory sslServerSocketFactory = sslContext.getServerSocketFactory();
                SSLServerSocket sslServerSocket =
                    (SSLServerSocket)
                        sslServerSocketFactory.createServerSocket(
                            5433, 5, InetAddress.getByName(ip));
                sslServerSocket.setNeedClientAuth(true);

                pickedPort.set(sslServerSocket.getLocalPort());
                countDownLatch.countDown();
                MetadataExchangeResponse response =
                    MetadataExchangeResponse.newBuilder().setResponseCode(ResponseCode.OK).build();
                for (; ; ) {
                  SSLSocket socket = (SSLSocket) sslServerSocket.accept();
                  socket.startHandshake();

                  // Metadata exchange.
                  socket.setSoTimeout(IO_TIMEOUT_MS);
                  DataInputStream in =
                      new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                  int reqSize = in.readInt();
                  byte[] reqData = new byte[reqSize];
                  in.readFully(reqData);
                  DataOutputStream out =
                      new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                  out.writeInt(response.getSerializedSize());
                  out.write(response.toByteArray());
                  out.flush();

                  // Send message to the client.
                  out.write(message.getBytes(UTF_8));
                  out.flush();
                  socket.close();
                }
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });
    this.thread.start();

    countDownLatch.await();

    return pickedPort.get();
  }

  void stop() {
    this.thread.interrupt();
  }

  private byte[] decodeBase64StripWhitespace(String b64) {
    return Base64.getDecoder().decode(b64.replaceAll("\\s", ""));
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

  private KeyManager[] initializeKeyManager(Certificate[] chainArray, PrivateKey privateKey)
      throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException,
          UnrecoverableKeyException {
    KeyStore clientAuthenticationKeyStore = KeyStore.getInstance(KeyStore.getDefaultType());
    clientAuthenticationKeyStore.load(
        null, // don't load the key store from an input stream
        null // there is no password
        );

    PrivateKeyEntry privateKeyEntry = new PrivateKeyEntry(privateKey, chainArray);
    clientAuthenticationKeyStore.setEntry(
        "serverCert", privateKeyEntry, new PasswordProtection(new char[0]) /* no password */);
    KeyManagerFactory keyManagerFactory =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keyManagerFactory.init(clientAuthenticationKeyStore, new char[0] /* no password */);
    return keyManagerFactory.getKeyManagers();
  }
}
