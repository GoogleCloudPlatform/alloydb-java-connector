/*
 * Copyright 2023 Google LLC
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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;

public class DefaultAccessTokenSupplierTest {
  private final Instant now = Instant.now();
  private final Instant past = now.plus(-1, ChronoUnit.HOURS);
  private final Instant future = now.plus(1, ChronoUnit.HOURS);

  private AtomicInteger refreshCounter;

  @Before
  public void setup() throws IOException {
    refreshCounter = new AtomicInteger();
  }

  @Test
  public void testEmptyTokenOnEmptyCredentials() throws IOException {
    DefaultAccessTokenSupplier supplier = new DefaultAccessTokenSupplier(null);
    assertThat(supplier.getTokenValue()).isEqualTo(null);
  }

  @Test
  public void testWithValidToken() throws Exception {
    // Google credentials can be refreshed
    GoogleCredentials googleCredentials =
        new GoogleCredentials(
            GoogleCredentials.newBuilder()
                .setAccessToken(new AccessToken("my-token", Date.from(future)))) {
          @Override
          public AccessToken refreshAccessToken() throws IOException {
            refreshCounter.incrementAndGet();
            return super.refreshAccessToken();
          }
        };

    DefaultAccessTokenSupplier supplier =
        new DefaultAccessTokenSupplier(new GoogleCredentialsFactory(googleCredentials));
    String token = supplier.getTokenValue();

    assertThat(token).isEqualTo("my-token");
    assertThat(refreshCounter.get()).isEqualTo(0);
  }

  @Test
  public void testThrowsOnExpiredTokenRefreshNotSupported() {
    GoogleCredentials expiredGoogleCredentials =
        new GoogleCredentials(
            GoogleCredentials.newBuilder()
                .setAccessToken(new AccessToken("my-expired-token", Date.from(past)))) {
          @Override
          public AccessToken refreshAccessToken() throws IOException {
            refreshCounter.incrementAndGet();
            return super.refreshAccessToken();
          }
        };

    DefaultAccessTokenSupplier supplier =
        new DefaultAccessTokenSupplier(new GoogleCredentialsFactory(expiredGoogleCredentials));
    IllegalStateException ex = assertThrows(IllegalStateException.class, supplier::getTokenValue);
    assertThat(ex).hasMessageThat().contains("Error refreshing credentials");
    assertThat(refreshCounter.get()).isEqualTo(1);
  }

  @Test
  public void testThrowsOnExpiredTokenRefreshStillExpired() {

    GoogleCredentials refreshGetsExpiredToken =
        new GoogleCredentials(
            GoogleCredentials.newBuilder()
                .setAccessToken(new AccessToken("my-expired-token", Date.from(past)))) {
          @Override
          public AccessToken refreshAccessToken() {
            refreshCounter.incrementAndGet();
            return new AccessToken("my-still-expired-token", Date.from(past));
          }
        };

    DefaultAccessTokenSupplier supplier =
        new DefaultAccessTokenSupplier(new GoogleCredentialsFactory(refreshGetsExpiredToken));
    IllegalStateException ex = assertThrows(IllegalStateException.class, supplier::getTokenValue);
    assertThat(ex).hasMessageThat().contains("expiration time is in the past");
    assertThat(refreshCounter.get()).isEqualTo(1);
  }

  @Test
  public void testValidOnRefreshSucceeded() throws Exception {
    GoogleCredentials refreshableCredentials =
        new GoogleCredentials(
            GoogleCredentials.newBuilder()
                .setAccessToken(new AccessToken("my-expired-token", Date.from(past)))) {
          @Override
          public AccessToken refreshAccessToken() {
            refreshCounter.incrementAndGet();
            return new AccessToken("my-refreshed-token", Date.from(future));
          }
        };

    DefaultAccessTokenSupplier supplier =
        new DefaultAccessTokenSupplier(new GoogleCredentialsFactory(refreshableCredentials));
    String token = supplier.getTokenValue();

    assertThat(token).isEqualTo("my-refreshed-token");

    assertThat(refreshCounter.get()).isEqualTo(1);
  }

  @Test
  public void throwsErrorForEmptyAccessToken() {
    GoogleCredentials creds =
        new GoogleCredentials(
            GoogleCredentials.newBuilder()
                .setAccessToken(new AccessToken("", Date.from(future)))) {};
    DefaultAccessTokenSupplier supplier =
        new DefaultAccessTokenSupplier(new GoogleCredentialsFactory(creds));
    RuntimeException ex = assertThrows(RuntimeException.class, supplier::getTokenValue);

    assertThat(ex).hasMessageThat().contains("Access Token has length of zero");
  }

  @Test
  public void throwsErrorForExpiredAccessToken() {
    GoogleCredentials refreshableCredentials =
        new GoogleCredentials(
            GoogleCredentials.newBuilder()
                .setAccessToken(new AccessToken("my-expired-token", Date.from(past)))) {
          @Override
          public AccessToken refreshAccessToken() {
            refreshCounter.incrementAndGet();
            return new AccessToken("my-refreshed-token", Date.from(past));
          }
        };

    DefaultAccessTokenSupplier supplier =
        new DefaultAccessTokenSupplier(new GoogleCredentialsFactory(refreshableCredentials));
    RuntimeException ex = assertThrows(RuntimeException.class, supplier::getTokenValue);

    assertThat(ex).hasMessageThat().contains("Access Token expiration time is in the past");
  }

  private static class GoogleCredentialsFactory implements CredentialFactory {
    private final GoogleCredentials credentials;

    private GoogleCredentialsFactory(GoogleCredentials credentials) {
      this.credentials = credentials;
    }

    @Override
    public GoogleCredentials getCredentials() {
      return credentials;
    }
  }
}
