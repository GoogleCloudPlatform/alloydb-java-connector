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

import com.google.cloud.alloydb.v1beta.InstanceName;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

public class ConnectionConfig {
  public static final String ALLOYDB_INSTANCE_NAME = "alloydbInstanceName";
  public static final String ALLOYDB_TARGET_PRINCIPAL_PROPERTY = "alloydbTargetPrincipal";
  public static final String ALLOYDB_DELEGATES_PROPERTY = "alloydbDelegates";
  private final InstanceName instanceName;
  private final String targetPrincipal;
  private final List<String> delegates;

  /** Create a new ConnectionConfig from the well known JDBC Connection properties. */
  public static ConnectionConfig fromConnectionProperties(Properties props) {
    final String instanceNameStr = props.getProperty(ALLOYDB_INSTANCE_NAME, "");
    final InstanceName instanceName = InstanceName.parse(instanceNameStr);
    final String targetPrincipal =
        props.getProperty(ConnectionConfig.ALLOYDB_TARGET_PRINCIPAL_PROPERTY);
    final String delegatesStr = props.getProperty(ConnectionConfig.ALLOYDB_DELEGATES_PROPERTY);
    final List<String> delegates;
    if (delegatesStr != null && !delegatesStr.isEmpty()) {
      delegates = Arrays.asList(delegatesStr.split(","));
    } else {
      delegates = Collections.emptyList();
    }

    return new ConnectionConfig(instanceName, targetPrincipal, delegates);
  }

  private ConnectionConfig(
      InstanceName instanceName, String targetPrincipal, List<String> delegates) {
    this.instanceName = instanceName;
    this.targetPrincipal = targetPrincipal;
    this.delegates = delegates;
  }

  public InstanceName getInstanceName() {
    return instanceName;
  }

  public String getTargetPrincipal() {
    return targetPrincipal;
  }

  public List<String> getDelegates() {
    return delegates;
  }

  /** The builder for the ConnectionConfig. */
  public static class Builder {
    private InstanceName instanceName;
    private String targetPrincipal;
    private List<String> delegates;

    public Builder withInstanceName(InstanceName instanceName) {
      this.instanceName = instanceName;
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

    public ConnectionConfig build() {
      return new ConnectionConfig(instanceName, targetPrincipal, delegates);
    }
  }
}
