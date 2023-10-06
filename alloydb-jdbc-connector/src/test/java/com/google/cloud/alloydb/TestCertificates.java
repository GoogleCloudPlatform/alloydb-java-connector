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

import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
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
public class TestCertificates {

  private static final String DEFAULT_INSTANCE_ID = "00000000-0000-0000-0000-000000000000";
  private static final String DEFAULT_SERVER_NAME =
      String.format("%s.server.alloydb", DEFAULT_INSTANCE_ID);
  private static final String SHA_256_WITH_RSA = "SHA256WithRSA";
  private static final X500Name ROOT_CERT_SUBJECT = new X500Name("CN=root.alloydb");
  private static final X500Name INTERMEDIATE_CERT_SUBJECT = new X500Name("CN=client.alloydb");
  private static final X500Name SERVER_CERT_SUBJECT = new X500Name("CN=" + DEFAULT_SERVER_NAME);
  public static final Instant ONE_YEAR_FROM_NOW = Instant.now().plus(365, ChronoUnit.DAYS);

  private final X509Certificate rootCertificate;
  private final X509Certificate intermediateCertificate;
  private final X509Certificate serverCertificate;

  private final KeyPair intermediateKeyPair;

  public TestCertificates() throws CertificateException, IOException, OperatorCreationException {

    KeyPair rootKeyPair = RsaKeyPairGenerator.generateKeyPair();
    intermediateKeyPair = RsaKeyPairGenerator.generateKeyPair();
    KeyPair serverKeyPair = RsaKeyPairGenerator.generateKeyPair();

    this.rootCertificate = buildRootCertificate(rootKeyPair);
    this.intermediateCertificate =
        buildSignedCertificate(
            INTERMEDIATE_CERT_SUBJECT,
            intermediateKeyPair.getPublic(),
            ROOT_CERT_SUBJECT,
            rootKeyPair.getPrivate(),
            ONE_YEAR_FROM_NOW);
    this.serverCertificate =
        buildSignedCertificate(
            SERVER_CERT_SUBJECT,
            serverKeyPair.getPublic(),
            ROOT_CERT_SUBJECT,
            rootKeyPair.getPrivate(),
            ONE_YEAR_FROM_NOW);
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

  /** Returns the cluster CA certificate */
  public X509Certificate getCaCertificate() {
    return rootCertificate;
  }

  /** Returns the certificate chain */
  public List<X509Certificate> getCertificateChain(X509Certificate ephemeralCertificate) {
    return Arrays.asList(ephemeralCertificate, intermediateCertificate, rootCertificate);
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
        Extension.keyUsage, false, new KeyUsage(KeyUsage.cRLSign | KeyUsage.keyCertSign));

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
