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

import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.base.Objects;
import java.util.List;
import java.util.function.Supplier;

/**
 * ConnectorConfig is an immutable configuration value object that holds the entire configuration of
 * a AlloyDB Connector that may be used to connect to multiple AlloyDB Instances.
 */
public class ConnectorConfig {

  // go into ConnectorConfig
  private final String targetPrincipal;
  private final List<String> delegates;
  private final String adminServiceEndpoint;
  private final Supplier<GoogleCredentials> googleCredentialsSupplier;
  private final GoogleCredentials googleCredentials;
  private final String googleCredentialsPath;
  private final String quotaProject;
  private final RefreshStrategy refreshStrategy;

  private ConnectorConfig(
      String targetPrincipal,
      List<String> delegates,
      String adminServiceEndpoint,
      Supplier<GoogleCredentials> googleCredentialsSupplier,
      GoogleCredentials googleCredentials,
      String googleCredentialsPath,
      String quotaProject,
      RefreshStrategy refreshStrategy) {
    this.targetPrincipal = targetPrincipal;
    this.delegates = delegates;
    this.adminServiceEndpoint = adminServiceEndpoint;
    this.googleCredentialsSupplier = googleCredentialsSupplier;
    this.googleCredentials = googleCredentials;
    this.googleCredentialsPath = googleCredentialsPath;
    this.quotaProject = quotaProject;
    this.refreshStrategy = refreshStrategy;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ConnectorConfig)) {
      return false;
    }
    ConnectorConfig that = (ConnectorConfig) o;
    return Objects.equal(targetPrincipal, that.targetPrincipal)
        && Objects.equal(delegates, that.delegates)
        && Objects.equal(adminServiceEndpoint, that.adminServiceEndpoint)
        && Objects.equal(googleCredentialsSupplier, that.googleCredentialsSupplier)
        && Objects.equal(googleCredentials, that.googleCredentials)
        && Objects.equal(googleCredentialsPath, that.googleCredentialsPath)
        && Objects.equal(quotaProject, that.quotaProject)
        && Objects.equal(refreshStrategy, that.refreshStrategy);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(
        targetPrincipal,
        delegates,
        adminServiceEndpoint,
        googleCredentialsSupplier,
        googleCredentials,
        googleCredentialsPath,
        quotaProject,
        refreshStrategy);
  }

  public String getTargetPrincipal() {
    return targetPrincipal;
  }

  public List<String> getDelegates() {
    return delegates;
  }

  public String getAdminServiceEndpoint() {
    return adminServiceEndpoint;
  }

  public Supplier<GoogleCredentials> getGoogleCredentialsSupplier() {
    return googleCredentialsSupplier;
  }

  public GoogleCredentials getGoogleCredentials() {
    return googleCredentials;
  }

  public String getGoogleCredentialsPath() {
    return googleCredentialsPath;
  }

  public String getQuotaProject() {
    return quotaProject;
  }

  public RefreshStrategy getRefreshStrategy() {
    return refreshStrategy;
  }

  /** The builder for the ConnectionConfig. */
  public static class Builder {

    private String targetPrincipal;
    private List<String> delegates;
    private String adminServiceEndpoint;
    private Supplier<GoogleCredentials> googleCredentialsSupplier;
    private GoogleCredentials googleCredentials;
    private String googleCredentialsPath;
    private String quotaProject;
    private RefreshStrategy refreshStrategy;

    public Builder withTargetPrincipal(String targetPrincipal) {
      this.targetPrincipal = targetPrincipal;
      return this;
    }

    public Builder withDelegates(List<String> delegates) {
      this.delegates = delegates;
      return this;
    }

    public Builder withAdminServiceEndpoint(String adminServiceEndpoint) {
      this.adminServiceEndpoint = adminServiceEndpoint;
      return this;
    }

    public Builder withGoogleCredentialsSupplier(
        Supplier<GoogleCredentials> googleCredentialsSupplier) {
      this.googleCredentialsSupplier = googleCredentialsSupplier;
      return this;
    }

    public Builder withGoogleCredentials(GoogleCredentials googleCredentials) {
      this.googleCredentials = googleCredentials;
      return this;
    }

    public Builder withGoogleCredentialsPath(String googleCredentialsPath) {
      this.googleCredentialsPath = googleCredentialsPath;
      return this;
    }

    public Builder withQuotaProject(String quotaProject) {
      this.quotaProject = quotaProject;
      return this;
    }

    public Builder withRefreshStrategy(RefreshStrategy refreshStrategy) {
      this.refreshStrategy = refreshStrategy;
      return this;
    }

    /** Builds a new instance of {@code ConnectionConfig}. */
    public ConnectorConfig build() {
      // validate only one GoogleCredentials configuration field set
      int googleCredsCount = 0;
      if (googleCredentials != null) {
        googleCredsCount++;
      }
      if (googleCredentialsPath != null) {
        googleCredsCount++;
      }
      if (googleCredentialsSupplier != null) {
        googleCredsCount++;
      }
      if (googleCredsCount > 1) {
        throw new IllegalStateException(
            "Invalid configuration, more than one GoogleCredentials field has a value "
                + "(googleCredentials, googleCredentialsPath, googleCredentialsSupplier)");
      }

      return new ConnectorConfig(
          targetPrincipal,
          delegates,
          adminServiceEndpoint,
          googleCredentialsSupplier,
          googleCredentials,
          googleCredentialsPath,
          quotaProject,
          refreshStrategy);
    }
  }
}
