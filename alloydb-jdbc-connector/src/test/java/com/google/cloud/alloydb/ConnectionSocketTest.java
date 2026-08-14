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

import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.Security;
import javax.net.ssl.SSLContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ConnectionSocketTest {

  private Provider originalBcJsse;

  @Before
  public void snapshotProviders() {
    originalBcJsse = Security.getProvider("BCJSSE");
    Security.removeProvider("BCJSSE");
    ConnectionSocket.resetProviderCache();
  }

  @After
  public void restoreProviders() {
    Security.removeProvider("BCJSSE");
    if (originalBcJsse != null) {
      Security.addProvider(originalBcJsse);
    }
    ConnectionSocket.resetProviderCache();
  }

  @Test
  public void getSslContextInstance_defaultFallback() throws NoSuchAlgorithmException {
    SSLContext context = ConnectionSocket.getSslContextInstance();

    // Should fallback to the default JDK provider (usually SunJSSE)
    assertThat(context).isNotNull();
    assertThat(context.getProvider().getName()).isNotEqualTo("BCJSSE");
  }

  @Test
  public void getSslContextInstance_prefersBouncyCastle() throws NoSuchAlgorithmException {
    Provider mockBcJsse = new MockSslProvider("BCJSSE");
    Security.addProvider(mockBcJsse);

    SSLContext context = ConnectionSocket.getSslContextInstance();

    assertThat(context).isNotNull();
    assertThat(context.getProvider().getName()).isEqualTo("BCJSSE");
  }

  @Test
  public void getSslContextInstance_picksUpProviderRegisteredAfterFirstCall()
      throws NoSuchAlgorithmException {
    // First call: no BCJSSE registered, expect default provider.
    SSLContext first = ConnectionSocket.getSslContextInstance();
    assertThat(first.getProvider().getName()).isNotEqualTo("BCJSSE");

    // Application registers BCJSSE after the connector has already been used.
    Security.addProvider(new MockSslProvider("BCJSSE"));

    // Subsequent call should now route through BCJSSE.
    SSLContext second = ConnectionSocket.getSslContextInstance();
    assertThat(second.getProvider().getName()).isEqualTo("BCJSSE");
  }

  @Test
  public void getSslContextInstance_fallsBackWhenCachedProviderRemoved()
      throws NoSuchAlgorithmException {
    // Register BCJSSE and prime the detection cache.
    Security.addProvider(new MockSslProvider("BCJSSE"));
    SSLContext cached = ConnectionSocket.getSslContextInstance();
    assertThat(cached.getProvider().getName()).isEqualTo("BCJSSE");

    // BCJSSE goes away (e.g., explicit unregister).
    Security.removeProvider("BCJSSE");

    // Next call must catch NoSuchProviderException, reset the cache, and fall back.
    SSLContext fallback = ConnectionSocket.getSslContextInstance();
    assertThat(fallback.getProvider().getName()).isNotEqualTo("BCJSSE");
  }

  // --- JCA Provider Mocks ---

  public static class MockSslProvider extends Provider {
    @SuppressWarnings("deprecation") // Use compatible legacy constructor
    public MockSslProvider(String name) {
      super(name, 1.0, "Mock SSL Provider for connector testing");
      put("SSLContext.TLSv1.3", MockSslContextSpi.class.getName());
    }
  }

  public static class MockSslContextSpi extends javax.net.ssl.SSLContextSpi {
    public MockSslContextSpi() {}

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
