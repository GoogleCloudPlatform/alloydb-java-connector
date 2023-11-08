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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.stream.Collectors;

public class ConnectionConfig {
  public static final String ALLOYDB_INSTANCE_NAME = "alloydbInstanceName";
  public static final String ALLOYDB_TARGET_PRINCIPAL = "alloydbTargetPrincipal";
  public static final String ALLOYDB_DELEGATES = "alloydbDelegates";
  public static final String ALLOYDB_NAMED_CONNECTOR = "alloydbNamedConnector";
  public static final String ALLOYDB_ADMIN_SERVICE_ENDPOINT = "alloydbAdminServiceEndpoint";
  public static final String DEFAULT_NAMED_CONNECTION = "default";
  private final InstanceName instanceName;
  private final String namedConnector;
  private final ConnectorConfig connectorConfig;

  /** Create a new ConnectionConfig from the well known JDBC Connection properties. */
  public static ConnectionConfig fromConnectionProperties(Properties props) {
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

    return new ConnectionConfig(
        instanceName,
        namedConnector,
        new ConnectorConfig.Builder()
            .withTargetPrincipal(targetPrincipal)
            .withDelegates(delegates)
            .withAdminServiceEndpoint(adminServiceEndpoint)
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
      InstanceName instanceName, String namedConnector, ConnectorConfig connectorConfig) {
    this.instanceName = instanceName;
    this.namedConnector = namedConnector;
    this.connectorConfig = connectorConfig;
  }

  /** Creates a new instance of the ConnectionConfig with an updated connectorConfig. */
  public ConnectionConfig withConnectorConfig(ConnectorConfig config) {
    return new ConnectionConfig(instanceName, namedConnector, config);
  }

  public InstanceName getInstanceName() {
    return instanceName;
  }

  public String getNamedConnector() {
    if (namedConnector != null && !namedConnector.isEmpty()) {
      return namedConnector;
    }

    // Build the connection name with the properties that make the
    // connection unique. Returns "default" if all properties are null.
    List<String> attrs = new ArrayList<String>();
    attrs.add(DEFAULT_NAMED_CONNECTION);
    attrs.add(connectorConfig.getTargetPrincipal());
    attrs.addAll(connectorConfig.getDelegates());
    attrs.add(connectorConfig.getAdminServiceEndpoint());
    return attrs.stream().filter(Objects::nonNull).collect(Collectors.joining("+"));
  }

  public ConnectorConfig getConnectorConfig() {
    return connectorConfig;
  }

  /** The builder for the ConnectionConfig. */
  public static class Builder {
    private InstanceName instanceName;
    private String namedConnector;
    private ConnectorConfig connectorConfig = new ConnectorConfig.Builder().build();

    public Builder withInstanceName(InstanceName instanceName) {
      this.instanceName = instanceName;
      return this;
    }

    public Builder withNamedConnector(String namedConnector) {
      this.namedConnector = namedConnector;
      return this;
    }

    public Builder withConnectorConfig(ConnectorConfig connectorConfig) {
      this.connectorConfig = connectorConfig;
      return this;
    }

    public ConnectionConfig build() {
      return new ConnectionConfig(instanceName, namedConnector, connectorConfig);
    }
  }
}
