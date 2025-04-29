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

import com.google.common.io.BaseEncoding;
import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.CertIOException;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.PKCS10CertificationRequestBuilder;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;

/**
 * Provides certificates for use in test that mimic AlloyDB's use of certificates. There are four
 * certificates:
 *
 * <ol>
 *   <li>A self-signed root CA certificate
 *   <li>An intermediate CA certificate, signed by the root CA
 *   <li>A Proxy server certificate signed by the root CA
 *   <li>An ephemeral certificate signed by the intermediate CA
 * </ol>
 */
enum TestCertificates {
  INSTANCE;

  private final String SHA_256_WITH_RSA = "SHA256WithRSA";

  @SuppressWarnings("ImmutableEnumChecker")
  private final X500Name ROOT_CERT_SUBJECT = new X500Name("CN=root.alloydb");

  @SuppressWarnings("ImmutableEnumChecker")
  private final X500Name INTERMEDIATE_CERT_SUBJECT = new X500Name("CN=client.alloydb");

  private final Instant ONE_HOUR_FROM_NOW = Instant.now().plus(1, ChronoUnit.HOURS);

  @SuppressWarnings("ImmutableEnumChecker")
  private final X509Certificate rootCertificate;

  @SuppressWarnings("ImmutableEnumChecker")
  private final X509Certificate intermediateCertificate;

  @SuppressWarnings("ImmutableEnumChecker")
  private final X509Certificate serverCertificate;

  @SuppressWarnings("ImmutableEnumChecker")
  private final KeyPair intermediateKeyPair;

  @SuppressWarnings("ImmutableEnumChecker")
  private final KeyPair serverKeyPair;

  @SuppressWarnings("ImmutableEnumChecker")
  private final KeyPair clientKeyPair;

  TestCertificates() {

    KeyPair rootKeyPair = RsaKeyPairGenerator.generateKeyPair();
    intermediateKeyPair = RsaKeyPairGenerator.generateKeyPair();
    serverKeyPair = RsaKeyPairGenerator.generateKeyPair();
    clientKeyPair = RsaKeyPairGenerator.generateKeyPair();

    try {
      this.rootCertificate = buildRootCertificate(rootKeyPair);

      Instant ONE_YEAR_FROM_NOW = Instant.now().plus(365, ChronoUnit.DAYS);
      this.intermediateCertificate =
          buildSignedCertificate(
              INTERMEDIATE_CERT_SUBJECT,
              intermediateKeyPair.getPublic(),
              ROOT_CERT_SUBJECT,
              rootKeyPair.getPrivate(),
              ONE_YEAR_FROM_NOW);
      String DEFAULT_INSTANCE_ID = "00000000-0000-0000-0000-000000000000";
      String DEFAULT_SERVER_NAME = String.format("%s.server.alloydb", DEFAULT_INSTANCE_ID);
      X500Name SERVER_CERT_SUBJECT = new X500Name("CN=" + DEFAULT_SERVER_NAME);
      this.serverCertificate =
          buildSignedCertificate(
              SERVER_CERT_SUBJECT,
              serverKeyPair.getPublic(),
              ROOT_CERT_SUBJECT,
              rootKeyPair.getPrivate(),
              ONE_YEAR_FROM_NOW);
    } catch (OperatorCreationException | CertificateException | IOException e) {
      throw new RuntimeException(e);
    }
  }

  /** Returns the ephemeral client certificate as signed by the intermediate certificate. */
  public X509Certificate getEphemeralCertificate(PublicKey connectorPublicKey, Instant notAfter)
      throws CertificateException, OperatorCreationException, CertIOException {
    return buildSignedCertificate(
        new X500Name("CN=connector"),
        connectorPublicKey,
        INTERMEDIATE_CERT_SUBJECT,
        this.intermediateKeyPair.getPrivate(),
        notAfter);
  }

  /** Returns the server-side proxy key pair. */
  public KeyPair getServerKey() {
    return serverKeyPair;
  }

  /** Returns the client key pair. */
  public KeyPair getClientKey() {
    return clientKeyPair;
  }

  /** Returns the server-side proxy certificate. */
  public X509Certificate getServerCertificate() {
    return serverCertificate;
  }

  /** Returns the intermediate CA certificate used to sign ephemeral certificates. */
  public X509Certificate getIntermediateCertificate() {
    return intermediateCertificate;
  }

  /** Returns the root CA certificate */
  public X509Certificate getRootCertificate() {
    return rootCertificate;
  }

  /** Returns the PEM encoded intermediate CA certificate */
  public String getIntermediateCertificateStr() throws CertificateEncodingException {
    return getPemForCert(intermediateCertificate);
  }

  /** Returns the PEM encoded root CA certificate */
  public String getRootCertificateStr() throws CertificateEncodingException {
    return getPemForCert(rootCertificate);
  }

  /** Returns the PEM encoded client certificate */
  public String getClientCertificateStr()
      throws CertificateException, OperatorCreationException, CertIOException {
    return getPemForCert(getEphemeralCertificate(clientKeyPair.getPublic(), ONE_HOUR_FROM_NOW));
  }

  /** Returns the PEM encoded certificate. */
  private String getPemForCert(X509Certificate certificate) throws CertificateEncodingException {
    StringBuilder sb = new StringBuilder();
    String PEM_HEADER = "-----BEGIN CERTIFICATE-----";
    sb.append(PEM_HEADER).append("\n");
    int PEM_LINE_LENGTH = 64;
    String base64Key =
        BaseEncoding.base64().withSeparator("\n", PEM_LINE_LENGTH).encode(certificate.getEncoded());
    sb.append(base64Key).append("\n");
    String PEM_FOOTER = "-----END CERTIFICATE-----";
    sb.append(PEM_FOOTER).append("\n");
    return sb.toString();
  }

  /** Creates a certificate with the given subject and signed by the root CA cert. */
  private X509Certificate buildSignedCertificate(
      X500Name subject,
      PublicKey subjectPublicKey,
      X500Name certificateIssuer,
      PrivateKey issuerPrivateKey,
      Instant notAfter)
      throws OperatorCreationException, CertIOException, CertificateException {
    PKCS10CertificationRequestBuilder pkcs10CertificationRequestBuilder =
        new JcaPKCS10CertificationRequestBuilder(subject, subjectPublicKey);
    JcaContentSignerBuilder contentSignerBuilder = new JcaContentSignerBuilder(SHA_256_WITH_RSA);
    ContentSigner csrContentSigner = contentSignerBuilder.build(issuerPrivateKey);
    PKCS10CertificationRequest csr = pkcs10CertificationRequestBuilder.build(csrContentSigner);

    X509v3CertificateBuilder certificateBuilder =
        new X509v3CertificateBuilder(
            certificateIssuer,
            new BigInteger(Long.toString(new SecureRandom().nextLong())),
            Date.from(Instant.now()),
            Date.from(notAfter),
            csr.getSubject(),
            csr.getSubjectPublicKeyInfo());

    certificateBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
    certificateBuilder.addExtension(
        Extension.keyUsage,
        false,
        new KeyUsage(KeyUsage.cRLSign | KeyUsage.keyCertSign | KeyUsage.digitalSignature));
    String PRIVATE_IP = "127.0.0.2";
    String DNS_NAME = "localhost";
    certificateBuilder.addExtension(
        Extension.subjectAlternativeName,
        false,
        new DERSequence(
            new ASN1Encodable[] {
              new GeneralName(GeneralName.dNSName, DNS_NAME),
              new GeneralName(GeneralName.iPAddress, PRIVATE_IP)
            }));

    X509CertificateHolder certificateHolder = certificateBuilder.build(csrContentSigner);
    return new JcaX509CertificateConverter().getCertificate(certificateHolder);
  }

  /** Creates a self-signed certificate to serve as a root CA */
  private X509Certificate buildRootCertificate(KeyPair rootKeyPair)
      throws OperatorCreationException, CertificateException, IOException {
    JcaX509v3CertificateBuilder certificateBuilder =
        new JcaX509v3CertificateBuilder(
            ROOT_CERT_SUBJECT, // issuer is self
            new BigInteger(Long.toString(new SecureRandom().nextLong())),
            Date.from(Instant.now()),
            Date.from(Instant.now().plus(365, ChronoUnit.DAYS)),
            ROOT_CERT_SUBJECT,
            rootKeyPair.getPublic());

    certificateBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
    certificateBuilder.addExtension(
        Extension.keyUsage, false, new KeyUsage(KeyUsage.cRLSign | KeyUsage.keyCertSign));

    ContentSigner contentSigner =
        new JcaContentSignerBuilder(SHA_256_WITH_RSA).build(rootKeyPair.getPrivate());

    X509CertificateHolder x509CertificateHolder = certificateBuilder.build(contentSigner);
    return new JcaX509CertificateConverter().getCertificate(x509CertificateHolder);
  }
}
