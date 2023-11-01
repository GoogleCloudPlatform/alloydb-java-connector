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
  public static final String ALLOYDB_NAMED_CONNECTION = "alloydbNamedConnection";
  public static final String ALLOYDB_ADMIN_SERVICE_ENDPOINT = "alloydbAdminServiceEndpoint";
  public static final String DEFAULT_NAMED_CONNECTION = "default";
  private final InstanceName instanceName;
  private final String namedConnection;
  private final String adminServiceEndpoint;
  private final String targetPrincipal;
  private final List<String> delegates;

  /** Create a new ConnectionConfig from the well known JDBC Connection properties. */
  public static ConnectionConfig fromConnectionProperties(Properties props) {
    final String instanceNameStr = props.getProperty(ALLOYDB_INSTANCE_NAME, "");
    final InstanceName instanceName = InstanceName.parse(instanceNameStr);
    final String namedConnection = props.getProperty(ConnectionConfig.ALLOYDB_NAMED_CONNECTION);
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
        instanceName, namedConnection, targetPrincipal, delegates, adminServiceEndpoint);
  }

  private ConnectionConfig(
      InstanceName instanceName,
      String namedConnection,
      String targetPrincipal,
      List<String> delegates,
      String adminServiceEndpoint) {
    this.instanceName = instanceName;
    this.namedConnection = namedConnection;
    this.targetPrincipal = targetPrincipal;
    this.delegates = (delegates != null) ? delegates : Collections.emptyList();
    this.adminServiceEndpoint = adminServiceEndpoint;
  }

  public InstanceName getInstanceName() {
    return instanceName;
  }

  public String getNamedConnection() {
    if (namedConnection != null && !namedConnection.isEmpty()) {
      return namedConnection;
    }

    // Build the connection name with the properties that make the
    // connection unique. Returns "default" if all properties are null.
    List<String> attrs = new ArrayList<String>();
    attrs.add(DEFAULT_NAMED_CONNECTION);
    attrs.add(targetPrincipal);
    attrs.addAll(delegates);
    attrs.add(adminServiceEndpoint);
    return attrs.stream().filter(Objects::nonNull).collect(Collectors.joining("+"));
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

  /** The builder for the ConnectionConfig. */
  public static class Builder {
    private InstanceName instanceName;
    private String namedConnection;
    private String adminServiceEndpoint;
    private String targetPrincipal;
    private List<String> delegates;

    public Builder withInstanceName(InstanceName instanceName) {
      this.instanceName = instanceName;
      return this;
    }

    public Builder withNamedConnection(String namedConnection) {
      this.namedConnection = namedConnection;
      return this;
    }

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

    public ConnectionConfig build() {
      return new ConnectionConfig(
          instanceName, namedConnection, targetPrincipal, delegates, adminServiceEndpoint);
    }
  }
}
