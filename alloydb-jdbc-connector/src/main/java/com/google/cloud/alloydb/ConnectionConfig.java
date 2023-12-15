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

import com.google.cloud.alloydb.v1.InstanceName;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

class ConnectionConfig {
  public static final String ALLOYDB_INSTANCE_NAME = "alloydbInstanceName";
  public static final String ALLOYDB_TARGET_PRINCIPAL = "alloydbTargetPrincipal";
  public static final String ALLOYDB_DELEGATES = "alloydbDelegates";
  public static final String ALLOYDB_NAMED_CONNECTOR = "alloydbNamedConnector";
  public static final String ALLOYDB_ADMIN_SERVICE_ENDPOINT = "alloydbAdminServiceEndpoint";
  public static final String ALLOYDB_GOOGLE_CREDENTIALS_PATH = "alloydbGoogleCredentialsPath";
  public static final String ENABLE_IAM_AUTH_PROPERTY = "enableIamAuth";
  public static final AuthType DEFAULT_AUTH_TYPE = AuthType.PASSWORD;
  private final InstanceName instanceName;
  private final String namedConnector;
  private final ConnectorConfig connectorConfig;
  private final AuthType authType;

  /** Create a new ConnectionConfig from the well known JDBC Connection properties. */
  static ConnectionConfig fromConnectionProperties(Properties props) {
    final String instanceNameStr = props.getProperty(ALLOYDB_INSTANCE_NAME, "");
    final InstanceName instanceName = InstanceName.parse(instanceNameStr);
    final String namedConnector = props.getProperty(ConnectionConfig.ALLOYDB_NAMED_CONNECTOR);
    final String adminServiceEndpoint =
        props.getProperty(ConnectionConfig.ALLOYDB_ADMIN_SERVICE_ENDPOINT);
    final String targetPrincipal = props.getProperty(ConnectionConfig.ALLOYDB_TARGET_PRINCIPAL);
    final String delegatesStr = props.getProperty(ConnectionConfig.ALLOYDB_DELEGATES);
    final List<String> delegates;
    if (delegatesStr != null && !delegatesStr.isEmpty()) {
      delegates = Arrays.asList(delegatesStr.split(","));
    } else {
      delegates = Collections.emptyList();
    }
    final String googleCredentialsPath =
        props.getProperty(ConnectionConfig.ALLOYDB_GOOGLE_CREDENTIALS_PATH);
    final AuthType authType =
        Boolean.parseBoolean(props.getProperty(ConnectionConfig.ENABLE_IAM_AUTH_PROPERTY))
            ? AuthType.IAM
            : AuthType.PASSWORD;

    return new ConnectionConfig(
        instanceName,
        namedConnector,
        authType,
        new ConnectorConfig.Builder()
            .withTargetPrincipal(targetPrincipal)
            .withDelegates(delegates)
            .withAdminServiceEndpoint(adminServiceEndpoint)
            .withGoogleCredentialsPath(googleCredentialsPath)
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
        && Objects.equals(connectorConfig, config.connectorConfig);
  }

  @Override
  public int hashCode() {
    return Objects.hash(instanceName, namedConnector, connectorConfig);
  }

  private ConnectionConfig(
      InstanceName instanceName,
      String namedConnector,
      AuthType authType,
      ConnectorConfig connectorConfig) {
    this.instanceName = instanceName;
    this.namedConnector = namedConnector;
    this.connectorConfig = connectorConfig;
    this.authType = authType;
  }

  /** Creates a new instance of the ConnectionConfig with an updated connectorConfig. */
  ConnectionConfig withConnectorConfig(ConnectorConfig config) {
    return new ConnectionConfig(instanceName, namedConnector, authType, config);
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

  /** The builder for the ConnectionConfig. */
  static class Builder {
    private InstanceName instanceName;
    private String namedConnector;
    private ConnectorConfig connectorConfig = new ConnectorConfig.Builder().build();
    private AuthType authType = DEFAULT_AUTH_TYPE;

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

    ConnectionConfig build() {
      return new ConnectionConfig(instanceName, namedConnector, authType, connectorConfig);
    }
  }
}
