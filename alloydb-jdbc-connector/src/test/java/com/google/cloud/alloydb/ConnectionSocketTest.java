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

import java.security.Provider;
import org.junit.Test;

/**
 * Unit tests for JSSE provider selection.
 *
 * <p>These pass the provider in rather than resolving it, so that both the available and the
 * unavailable platform can be tested on any machine and without loading a native library. {@link
 * ConnectionSocketConscryptTest} exercises the same code path with the real Conscrypt provider and
 * a real handshake.
 */
public class ConnectionSocketTest {

  /** A platform where Conscrypt's native library will not load. */
  private static final Provider UNAVAILABLE = null;

  /** A JRE whose own JSSE provider has no ML-KEM, so AUTO has a gap to fill. */
  private static final int PRE_PQC_JRE = 21;

  /** A JRE that negotiates ML-KEM natively via JEP 527, so AUTO has no gap to fill. */
  private static final int PQC_JRE = 27;

  @Test
  public void selectProvider_jdkUsesTheJreDefault() {
    Provider provider =
        ConnectionSocket.selectProvider(
            TlsProvider.JDK, new StubSslProvider("Conscrypt"), PRE_PQC_JRE);

    // Null means the JRE's default provider. JDK must ignore Conscrypt even when it is usable.
    assertThat(provider).isNull();
  }

  @Test
  public void selectProvider_autoPrefersConscrypt() {
    Provider conscrypt = new StubSslProvider("Conscrypt");

    Provider provider = ConnectionSocket.selectProvider(TlsProvider.AUTO, conscrypt, PRE_PQC_JRE);

    assertThat(provider).isSameInstanceAs(conscrypt);
  }

  @Test
  public void selectProvider_autoFallsBackToTheJreProvider() {
    Provider provider = ConnectionSocket.selectProvider(TlsProvider.AUTO, UNAVAILABLE, PRE_PQC_JRE);

    assertThat(provider).isNull();
  }

  @Test
  public void selectProvider_autoFallsBackWhenProviderCannotServeTls13() {
    // Available, but offers no TLSv1.3 SSLContext service.
    Provider provider =
        ConnectionSocket.selectProvider(TlsProvider.AUTO, new EmptyProvider("X"), PRE_PQC_JRE);

    assertThat(provider).isNull();
  }

  @Test
  public void selectProvider_conscryptRequiredAndPresent() {
    Provider conscrypt = new StubSslProvider("Conscrypt");

    Provider provider =
        ConnectionSocket.selectProvider(TlsProvider.CONSCRYPT, conscrypt, PRE_PQC_JRE);

    assertThat(provider).isSameInstanceAs(conscrypt);
  }

  @Test
  public void selectProvider_conscryptRequiredButUnavailableFailsClosed() {
    UserConfigException ex =
        assertThrows(
            UserConfigException.class,
            () -> ConnectionSocket.selectProvider(TlsProvider.CONSCRYPT, UNAVAILABLE, PRE_PQC_JRE));

    assertThat(ex).hasMessageThat().contains("unavailable on this platform");
    assertThat(ex).hasMessageThat().contains(ConnectionConfig.ALLOYDB_TLS_PROVIDER);
  }

  @Test
  public void selectProvider_conscryptRequiredButNoTls13FailsClosed() {
    Provider noTls13 = new EmptyProvider("Conscrypt");

    UserConfigException ex =
        assertThrows(
            UserConfigException.class,
            () -> ConnectionSocket.selectProvider(TlsProvider.CONSCRYPT, noTls13, PRE_PQC_JRE));

    assertThat(ex).hasMessageThat().contains("cannot provide a TLSv1.3 SSLContext");
  }

  /**
   * A provider that serves the SSLContext but not the trust manager is no more usable than one that
   * serves neither: initializeTrustManager asks the same provider for a PKIX TrustManagerFactory,
   * and the resulting NoSuchAlgorithmException would surface as an opaque RuntimeException from
   * buildSocket rather than as a fallback or a user error.
   */
  @Test
  public void selectProvider_autoFallsBackWhenProviderHasNoTrustManagerFactory() {
    Provider provider =
        ConnectionSocket.selectProvider(
            TlsProvider.AUTO, new SslOnlyProvider("Conscrypt"), PRE_PQC_JRE);

    assertThat(provider).isNull();
  }

  @Test
  public void selectProvider_conscryptRequiredButNoTrustManagerFactoryFailsClosed() {
    Provider sslOnly = new SslOnlyProvider("Conscrypt");

    UserConfigException ex =
        assertThrows(
            UserConfigException.class,
            () -> ConnectionSocket.selectProvider(TlsProvider.CONSCRYPT, sslOnly, PRE_PQC_JRE));

    assertThat(ex).hasMessageThat().contains("cannot provide a PKIX TrustManagerFactory");
    assertThat(ex).hasMessageThat().contains(ConnectionConfig.ALLOYDB_TLS_PROVIDER);
  }

  /**
   * The gap-filling rule: AUTO exists to supply ML-KEM where the JRE cannot, so on a JRE that
   * negotiates it natively (JEP 527, JDK 27) AUTO stays on the default provider rather than
   * substituting a different TLS stack to deliver what the JRE already delivers.
   */
  @Test
  public void selectProvider_autoPrefersTheJreOnAJreWithItsOwnPqc() {
    Provider conscrypt = new StubSslProvider("Conscrypt");

    Provider provider = ConnectionSocket.selectProvider(TlsProvider.AUTO, conscrypt, PQC_JRE);

    assertThat(provider).isNull();
  }

  /**
   * And it must not even resolve Conscrypt there. Loading the native library is the expensive part
   * of this feature, so a JRE that needs no help must not pay for it. The supplier throwing is how
   * this test detects the load: a passing run never calls it.
   */
  @Test
  public void selectProvider_autoDoesNotResolveConscryptOnAJreWithItsOwnPqc() {
    Provider provider =
        ConnectionSocket.selectProvider(
            TlsProvider.AUTO,
            () -> {
              throw new AssertionError("Conscrypt must not be resolved on a JRE with its own PQC");
            },
            PQC_JRE);

    assertThat(provider).isNull();
  }

  /**
   * CONSCRYPT is an explicit choice rather than a gap to be filled, so the JRE's own support does
   * not override it. Keeping this true is also what lets Conscrypt be exercised on a new JRE.
   */
  @Test
  public void selectProvider_conscryptIsHonoredOnAJreWithItsOwnPqc() {
    Provider conscrypt = new StubSslProvider("Conscrypt");

    Provider provider = ConnectionSocket.selectProvider(TlsProvider.CONSCRYPT, conscrypt, PQC_JRE);

    assertThat(provider).isSameInstanceAs(conscrypt);
  }

  /** JDK is unaffected by the rule in either direction. */
  @Test
  public void selectProvider_jdkUsesTheJreDefaultOnAJreWithItsOwnPqc() {
    Provider provider =
        ConnectionSocket.selectProvider(TlsProvider.JDK, new StubSslProvider("Conscrypt"), PQC_JRE);

    assertThat(provider).isNull();
  }

  /** The boundary: the rule turns on exactly at the JDK that ships JEP 527, not before. */
  @Test
  public void selectProvider_autoUsesConscryptOnTheVersionBeforePqcLandsInTheJre() {
    Provider conscrypt = new StubSslProvider("Conscrypt");

    assertThat(ConnectionSocket.selectProvider(TlsProvider.AUTO, conscrypt, 26))
        .isSameInstanceAs(conscrypt);
    assertThat(ConnectionSocket.selectProvider(TlsProvider.AUTO, conscrypt, 27)).isNull();
  }

  /**
   * Java 8 reports "1.8" rather than "8", and it is in the supported matrix, so the parse is
   * covered directly rather than only through whichever JRE happens to run this suite.
   */
  @Test
  public void parseJreVersion_readsEveryShapeTheMatrixProduces() {
    assertThat(ConnectionSocket.parseJreVersion("1.8")).isEqualTo(8);
    assertThat(ConnectionSocket.parseJreVersion("11")).isEqualTo(11);
    assertThat(ConnectionSocket.parseJreVersion("21")).isEqualTo(21);
    assertThat(ConnectionSocket.parseJreVersion("25")).isEqualTo(25);
    assertThat(ConnectionSocket.parseJreVersion("27")).isEqualTo(27);
  }

  /** An unreadable version must read as "no native ML-KEM", so that AUTO still offers Conscrypt. */
  @Test
  public void parseJreVersion_unparseableReadsAsAJreWithoutPqc() {
    for (String version : new String[] {"", "garbage", "1.8.0_462", "21-ea"}) {
      assertThat(ConnectionSocket.parseJreVersion(version)).isLessThan(27);
    }
  }

  // --- JCA Provider stubs ---

  /** A provider offering both services the connector asks a non-default provider for. */
  public static class StubSslProvider extends Provider {
    @SuppressWarnings("deprecation") // The (String, double, String) constructor works on Java 8.
    public StubSslProvider(String name) {
      super(name, 1.0, "Stub SSL provider for connector testing");
      put("SSLContext.TLSv1.3", StubSslContextSpi.class.getName());
      put("TrustManagerFactory.PKIX", StubTrustManagerFactorySpi.class.getName());
    }
  }

  /**
   * A provider offering the SSLContext but no TrustManagerFactory, as Conscrypt does by default.
   */
  public static class SslOnlyProvider extends Provider {
    @SuppressWarnings("deprecation") // The (String, double, String) constructor works on Java 8.
    public SslOnlyProvider(String name) {
      super(name, 1.0, "Stub provider without a TrustManagerFactory service");
      put("SSLContext.TLSv1.3", StubSslContextSpi.class.getName());
    }
  }

  /** A provider that offers no services, standing in for one that cannot serve TLSv1.3. */
  public static class EmptyProvider extends Provider {
    @SuppressWarnings("deprecation") // The (String, double, String) constructor works on Java 8.
    public EmptyProvider(String name) {
      super(name, 1.0, "Stub provider without an SSLContext service");
    }
  }

  public static class StubTrustManagerFactorySpi extends javax.net.ssl.TrustManagerFactorySpi {
    public StubTrustManagerFactorySpi() {}

    @Override
    protected void engineInit(java.security.KeyStore ks) {}

    @Override
    protected void engineInit(javax.net.ssl.ManagerFactoryParameters spec) {}

    @Override
    protected javax.net.ssl.TrustManager[] engineGetTrustManagers() {
      return new javax.net.ssl.TrustManager[0];
    }
  }

  public static class StubSslContextSpi extends javax.net.ssl.SSLContextSpi {
    public StubSslContextSpi() {}

    @Override
    protected void engineInit(
        javax.net.ssl.KeyManager[] km,
        javax.net.ssl.TrustManager[] tm,
        java.security.SecureRandom sr) {}

    @Override
    protected javax.net.ssl.SSLSocketFactory engineGetSocketFactory() {
      return null;
    }

    @Override
    protected javax.net.ssl.SSLServerSocketFactory engineGetServerSocketFactory() {
      return null;
    }

    @Override
    protected javax.net.ssl.SSLEngine engineCreateSSLEngine() {
      return null;
    }

    @Override
    protected javax.net.ssl.SSLEngine engineCreateSSLEngine(String host, int port) {
      return null;
    }

    @Override
    protected javax.net.ssl.SSLSessionContext engineGetServerSessionContext() {
      return null;
    }

    @Override
    protected javax.net.ssl.SSLSessionContext engineGetClientSessionContext() {
      return null;
    }
  }
}
