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

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;

class DefaultAccessTokenSupplier implements AccessTokenSupplier {

  public static final String ALLOYDB_LOGIN_SCOPE = "https://www.googleapis.com/auth/alloydb.login";
  private final CredentialFactory credentialFactory;

  /**
   * Creates an instance with default retry settings.
   *
   * @param tokenSource the token source that produces auth tokens.
   */
  DefaultAccessTokenSupplier(CredentialFactory tokenSource) {
    this.credentialFactory = tokenSource;
  }

  /**
   * Returns an access token value, refreshing if the credentials are expired.
   *
   * @return the access token value.
   * @throws IOException if there is an error attempting to refresh the token
   */
  @Override
  public String getTokenValue() throws IOException {
    if (credentialFactory == null) {
      return null;
    }

    GoogleCredentials credentials =
        credentialFactory.getCredentials().createScoped(ALLOYDB_LOGIN_SCOPE);

    try {
      credentials.refreshIfExpired();
    } catch (IllegalStateException e) {
      throw new IllegalStateException("Error refreshing credentials " + credentials, e);
    }
    if (credentials.getAccessToken() == null
        || "".equals(credentials.getAccessToken().getTokenValue())) {
      String errorMessage = "Access Token has length of zero";
      throw new IllegalStateException(errorMessage);
    }

    validateAccessTokenExpiration(credentials.getAccessToken());
    return credentials.getAccessToken().getTokenValue();
  }

  private void validateAccessTokenExpiration(AccessToken accessToken) {
    Date expirationTimeDate = accessToken.getExpirationTime();

    if (expirationTimeDate != null) {
      Instant expirationTime = expirationTimeDate.toInstant();
      Instant now = Instant.now();

      // Is the token expired?
      if (expirationTime.isBefore(now) || expirationTime.equals(now)) {
        DateTimeFormatter formatter = DateTimeFormatter.ISO_INSTANT.withZone(ZoneId.of("UTC"));
        String nowFormat = formatter.format(now);
        String expirationFormat = formatter.format(expirationTime);
        String errorMessage =
            "Access Token expiration time is in the past. Now = "
                + nowFormat
                + " Expiration = "
                + expirationFormat;
        throw new IllegalStateException(errorMessage);
      }
    }
  }
}
