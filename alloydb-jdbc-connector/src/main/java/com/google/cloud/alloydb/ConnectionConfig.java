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

import com.google.cloud.alloydb.v1alpha.InstanceName;
import com.google.common.base.Preconditions;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

class ConnectionConfig {
  public static final String ALLOYDB_INSTANCE_NAME = "alloydbInstanceName";
  public static final String ALLOYDB_TARGET_PRINCIPAL = "alloydbTargetPrincipal";
  public static final String ALLOYDB_DELEGATES = "alloydbDelegates";
  public static final String ALLOYDB_NAMED_CONNECTOR = "alloydbNamedConnector";
  public static final String ALLOYDB_ADMIN_SERVICE_ENDPOINT = "alloydbAdminServiceEndpoint";
  public static final String ALLOYDB_GOOGLE_CREDENTIALS_PATH = "alloydbGoogleCredentialsPath";
  public static final String ALLOYDB_QUOTA_PROJECT = "alloydbQuotaProject";
  public static final String ENABLE_IAM_AUTH_PROPERTY = "alloydbEnableIAMAuth";
  public static final String ALLOYDB_IP_TYPE = "alloydbIpType";
  public static final String ALLOYDB_REFRESH_STRATEGY = "alloydbRefreshStrategy";
  public static final AuthType DEFAULT_AUTH_TYPE = AuthType.PASSWORD;
  public static final IpType DEFAULT_IP_TYPE = IpType.PRIVATE;
  private final InstanceName instanceName;
  private final String namedConnector;
  private final ConnectorConfig connectorConfig;
  private final AuthType authType;
  private final IpType ipType;

  /** Create a new ConnectionConfig from the well known JDBC Connection properties. */
  static ConnectionConfig fromConnectionProperties(Properties props) {
    validateProperties(props);
    final String instanceNameStr = props.getProperty(ALLOYDB_INSTANCE_NAME, "");
    final InstanceName instanceName = InstanceName.parse(instanceNameStr);
    final String namedConnector = props.getProperty(ALLOYDB_NAMED_CONNECTOR);
    final String adminServiceEndpoint = props.getProperty(ALLOYDB_ADMIN_SERVICE_ENDPOINT);
    final String targetPrincipal = props.getProperty(ALLOYDB_TARGET_PRINCIPAL);
    final String delegatesStr = props.getProperty(ALLOYDB_DELEGATES);
    final List<String> delegates;
    if (delegatesStr != null && !delegatesStr.isEmpty()) {
      delegates = Arrays.asList(delegatesStr.split(","));
    } else {
      delegates = Collections.emptyList();
    }
    final String googleCredentialsPath = props.getProperty(ALLOYDB_GOOGLE_CREDENTIALS_PATH);
    final AuthType authType =
        Boolean.parseBoolean(props.getProperty(ENABLE_IAM_AUTH_PROPERTY))
            ? AuthType.IAM
            : AuthType.PASSWORD;
    final String quotaProject = props.getProperty(ALLOYDB_QUOTA_PROJECT);
    IpType ipType = IpType.PRIVATE;
    if (props.getProperty(ALLOYDB_IP_TYPE) != null) {
      ipType = IpType.valueOf(props.getProperty(ALLOYDB_IP_TYPE).toUpperCase(Locale.getDefault()));
    }
    RefreshStrategy refreshStrategy = RefreshStrategy.REFRESH_AHEAD;
    if (props.getProperty(ALLOYDB_REFRESH_STRATEGY) != null) {
      refreshStrategy =
          RefreshStrategy.valueOf(
              props.getProperty(ALLOYDB_REFRESH_STRATEGY).toUpperCase(Locale.getDefault()));
    }

    return new ConnectionConfig(
        instanceName,
        namedConnector,
        authType,
        ipType,
        new ConnectorConfig.Builder()
            .withTargetPrincipal(targetPrincipal)
            .withDelegates(delegates)
            .withAdminServiceEndpoint(adminServiceEndpoint)
            .withGoogleCredentialsPath(googleCredentialsPath)
            .withQuotaProject(quotaProject)
            .withRefreshStrategy(refreshStrategy)
            .build());
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ConnectionConfig)) {
      return false;
    }
    ConnectionConfig config = (ConnectionConfig) o;
    return Objects.equals(instanceName, config.instanceName)
        && Objects.equals(namedConnector, config.namedConnector)
        && Objects.equals(ipType, config.ipType)
        && Objects.equals(connectorConfig, config.connectorConfig);
  }

  @Override
  public int hashCode() {
    return Objects.hash(instanceName, namedConnector, ipType, connectorConfig);
  }

  private static void validateProperties(Properties props) {
    final String instanceNameStr = props.getProperty(ALLOYDB_INSTANCE_NAME, "");
    Preconditions.checkArgument(
        InstanceName.isParsableFrom(instanceNameStr),
        "'%s' must have format: projects/<PROJECT>/locations/<REGION>/clusters/<CLUSTER>/instances/<INSTANCE>",
        ALLOYDB_INSTANCE_NAME);
  }

  private ConnectionConfig(
      InstanceName instanceName,
      String namedConnector,
      AuthType authType,
      IpType ipType,
      ConnectorConfig connectorConfig) {
    this.instanceName = instanceName;
    this.namedConnector = namedConnector;
    this.connectorConfig = connectorConfig;
    this.authType = authType;
    this.ipType = ipType;
  }

  /** Creates a new instance of the ConnectionConfig with an updated connectorConfig. */
  ConnectionConfig withConnectorConfig(ConnectorConfig config) {
    return new ConnectionConfig(instanceName, namedConnector, authType, ipType, config);
  }

  InstanceName getInstanceName() {
    return instanceName;
  }

  String getNamedConnector() {
    return namedConnector;
  }

  ConnectorConfig getConnectorConfig() {
    return connectorConfig;
  }

  AuthType getAuthType() {
    return authType;
  }

  IpType getIpType() {
    return ipType;
  }

  /** The builder for the ConnectionConfig. */
  static class Builder {
    private InstanceName instanceName;
    private String namedConnector;
    private ConnectorConfig connectorConfig = new ConnectorConfig.Builder().build();
    private AuthType authType = DEFAULT_AUTH_TYPE;
    private IpType ipType = DEFAULT_IP_TYPE;

    Builder withInstanceName(InstanceName instanceName) {
      this.instanceName = instanceName;
      return this;
    }

    Builder withNamedConnector(String namedConnector) {
      this.namedConnector = namedConnector;
      return this;
    }

    Builder withConnectorConfig(ConnectorConfig connectorConfig) {
      this.connectorConfig = connectorConfig;
      return this;
    }

    public Builder withAuthType(AuthType authType) {
      this.authType = authType;
      return this;
    }

    public Builder withIpType(IpType ipType) {
      this.ipType = ipType;
      return this;
    }

    ConnectionConfig build() {
      return new ConnectionConfig(instanceName, namedConnector, authType, ipType, connectorConfig);
    }
  }
}
