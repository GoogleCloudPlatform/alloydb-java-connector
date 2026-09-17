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

import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.Security;
import javax.net.ssl.SSLContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for JSSE provider selection. These use a stub provider so that they run identically on
 * every supported JDK. {@link ConnectionSocketBouncyCastleTest} exercises the same code path with
 * the real Bouncy Castle provider and a real handshake.
 */
public class ConnectionSocketTest {

  private static final String BC_PROVIDER = "BCJSSE";

  private ProviderSnapshot bcJsse;

  @Before
  public void snapshotProviders() {
    bcJsse = ProviderSnapshot.of(BC_PROVIDER);
    Security.removeProvider(BC_PROVIDER);
  }

  @After
  public void restoreProviders() {
    bcJsse.restore();
  }

  @Test
  public void getSslContextInstance_autoFallsBackToTheJreProvider()
      throws NoSuchAlgorithmException {
    SSLContext context = ConnectionSocket.getSslContextInstance(TlsProvider.AUTO);

    // Should fall back to the default JDK provider (usually SunJSSE).
    assertThat(context).isNotNull();
    assertThat(context.getProvider().getName()).isNotEqualTo(BC_PROVIDER);
  }

  @Test
  public void getSslContextInstance_prefersBouncyCastle() throws NoSuchAlgorithmException {
    Security.addProvider(new StubSslProvider(BC_PROVIDER));

    SSLContext context = ConnectionSocket.getSslContextInstance(TlsProvider.AUTO);

    assertThat(context.getProvider().getName()).isEqualTo(BC_PROVIDER);
  }

  @Test
  public void getSslContextInstance_picksUpProviderRegisteredAfterFirstCall()
      throws NoSuchAlgorithmException {
    // First call: no BCJSSE registered, expect default provider.
    SSLContext first = ConnectionSocket.getSslContextInstance(TlsProvider.AUTO);
    assertThat(first.getProvider().getName()).isNotEqualTo(BC_PROVIDER);

    // Application registers BCJSSE after the connector has already been used.
    Security.addProvider(new StubSslProvider(BC_PROVIDER));

    // Subsequent call should now route through BCJSSE.
    SSLContext second = ConnectionSocket.getSslContextInstance(TlsProvider.AUTO);
    assertThat(second.getProvider().getName()).isEqualTo(BC_PROVIDER);
  }

  @Test
  public void getSslContextInstance_fallsBackWhenProviderRemoved() throws NoSuchAlgorithmException {
    Security.addProvider(new StubSslProvider(BC_PROVIDER));
    SSLContext bc = ConnectionSocket.getSslContextInstance(TlsProvider.AUTO);
    assertThat(bc.getProvider().getName()).isEqualTo(BC_PROVIDER);

    // BCJSSE goes away (e.g., explicit unregister).
    Security.removeProvider(BC_PROVIDER);

    SSLContext fallback = ConnectionSocket.getSslContextInstance(TlsProvider.AUTO);
    assertThat(fallback.getProvider().getName()).isNotEqualTo(BC_PROVIDER);
  }

  @Test
  public void getSslContextInstance_fallsBackWhenProviderCannotServeTls13()
      throws NoSuchAlgorithmException {
    // Registered under the BCJSSE name, but offers no TLSv1.3 SSLContext service.
    Security.addProvider(new EmptyProvider(BC_PROVIDER));

    SSLContext context = ConnectionSocket.getSslContextInstance(TlsProvider.AUTO);

    assertThat(context.getProvider().getName()).isNotEqualTo(BC_PROVIDER);
  }

  @Test
  public void getSslContextInstance_jdkIgnoresBouncyCastle() throws NoSuchAlgorithmException {
    Security.addProvider(new StubSslProvider(BC_PROVIDER));

    SSLContext context = ConnectionSocket.getSslContextInstance(TlsProvider.JDK);

    assertThat(context.getProvider().getName()).isNotEqualTo(BC_PROVIDER);
  }

  @Test
  public void getSslContextInstance_bouncyCastleRequiredAndPresent()
      throws NoSuchAlgorithmException {
    Security.addProvider(new StubSslProvider(BC_PROVIDER));

    SSLContext context = ConnectionSocket.getSslContextInstance(TlsProvider.BOUNCY_CASTLE);

    assertThat(context.getProvider().getName()).isEqualTo(BC_PROVIDER);
  }

  @Test
  public void getSslContextInstance_bouncyCastleRequiredButMissingFailsClosed() {
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () -> ConnectionSocket.getSslContextInstance(TlsProvider.BOUNCY_CASTLE));

    assertThat(ex).hasMessageThat().contains("is not registered");
    assertThat(ex).hasMessageThat().contains(ConnectionConfig.ALLOYDB_TLS_PROVIDER);
  }

  @Test
  public void getSslContextInstance_bouncyCastleRequiredButNoTls13FailsClosed() {
    Security.addProvider(new EmptyProvider(BC_PROVIDER));

    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () -> ConnectionSocket.getSslContextInstance(TlsProvider.BOUNCY_CASTLE));

    assertThat(ex).hasMessageThat().contains("cannot provide a TLSv1.3");
  }

  // --- JCA Provider stubs ---

  /** A provider that answers SSLContext.getInstance("TLSv1.3") and nothing else. */
  public static class StubSslProvider extends Provider {
    @SuppressWarnings("deprecation") // The (String, double, String) constructor works on Java 8.
    public StubSslProvider(String name) {
      super(name, 1.0, "Stub SSL provider for connector testing");
      put("SSLContext.TLSv1.3", StubSslContextSpi.class.getName());
    }
  }

  /** A provider that offers no services, standing in for a BCJSSE that cannot serve TLSv1.3. */
  public static class EmptyProvider extends Provider {
    @SuppressWarnings("deprecation") // The (String, double, String) constructor works on Java 8.
    public EmptyProvider(String name) {
      super(name, 1.0, "Stub provider without an SSLContext service");
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
