/*
 * Copyright 2023 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
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

import com.google.auth.Credentials;
import com.google.auth.oauth2.ImpersonatedCredentials;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class ServiceAccountImpersonatingCredentialFactoryTest {

  @Test
  public void testImpersonatedCredentialsWithMultipleAccounts() {
    DefaultCredentialFactory factory = new DefaultCredentialFactory();
    Credentials credentials = factory.getCredentials();

    CredentialFactory impersonatedFactory =
        new ServiceAccountImpersonatingCredentialFactory(
            factory,
            "first@serviceaccount.com",
            Arrays.asList("third@serviceaccount.com", "second@serviceaccount.com"));

    // Test that the CredentialsFactory.create() works.
    Credentials impersonatedCredentials = impersonatedFactory.getCredentials();
    assertThat(impersonatedCredentials).isInstanceOf(ImpersonatedCredentials.class);

    // Test that CredentialsFactory.getCredentials() works.
    assertThat(impersonatedFactory.getCredentials()).isInstanceOf(ImpersonatedCredentials.class);
    ImpersonatedCredentials ic = (ImpersonatedCredentials) impersonatedFactory.getCredentials();
    assertThat(ic.getAccount()).isEqualTo("first@serviceaccount.com");
    assertThat(ic.getSourceCredentials()).isEqualTo(credentials);
  }

  @Test
  public void testImpersonatedCredentialsWithOneAccount() {
    DefaultCredentialFactory factory = new DefaultCredentialFactory();
    Credentials credentials = factory.getCredentials();

    CredentialFactory impersonatedFactory =
        new ServiceAccountImpersonatingCredentialFactory(factory, "first@serviceaccount.com", null);

    // Test that the CredentialsFactory.create() works.
    Credentials impersonatedCredentials = impersonatedFactory.getCredentials();
    assertThat(impersonatedCredentials).isInstanceOf(ImpersonatedCredentials.class);

    // Test that CredentialsFactory.getCredentials() works.
    assertThat(impersonatedFactory.getCredentials()).isInstanceOf(ImpersonatedCredentials.class);
    ImpersonatedCredentials ic = (ImpersonatedCredentials) impersonatedFactory.getCredentials();
    assertThat(ic.getAccount()).isEqualTo("first@serviceaccount.com");
    assertThat(ic.getSourceCredentials()).isEqualTo(credentials);
  }

  @Test
  public void testEmptyDelegatesThrowsIllegalArgumentException() {
    DefaultCredentialFactory factory = new DefaultCredentialFactory();
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          new ServiceAccountImpersonatingCredentialFactory(factory, null, Collections.emptyList());
        });
  }
}
