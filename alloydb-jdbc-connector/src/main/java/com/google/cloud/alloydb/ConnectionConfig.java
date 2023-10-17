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
import java.util.Properties;

public class ConnectionConfig {
  public static final String ALLOYDB_INSTANCE_NAME = "alloydbInstanceName";
  private final InstanceName instanceName;

  /** Create a new ConnectionConfig from the well known JDBC Connection properties. */
  public static ConnectionConfig fromConnectionProperties(Properties props) {
    final String instanceNameStr = props.getProperty(ALLOYDB_INSTANCE_NAME, "");
    final InstanceName instanceName = InstanceName.parse(instanceNameStr);

    return new ConnectionConfig(instanceName);
  }

  private ConnectionConfig(InstanceName instanceName) {
    this.instanceName = instanceName;
  }

  public InstanceName getInstanceName() {
    return instanceName;
  }

  /** The builder for the ConnectionConfig. */
  public static class Builder {
    private InstanceName instanceName;

    public Builder withInstanceName(InstanceName instanceName) {
      this.instanceName = instanceName;
      return this;
    }

    public ConnectionConfig build() {
      return new ConnectionConfig(instanceName);
    }
  }
}
