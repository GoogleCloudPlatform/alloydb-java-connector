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

/** Configure the AlloyDB JDBC Connector. */
public final class ConnectorRegistry {

  /**
   * Register a named connection so that it can later be referenced by name in a JDBC or R2DBC URL.
   *
   * @param name the named connection name.
   * @param config the full configuration of the connection.
   */
  public static void register(String name, ConnectorConfig config) {
    InternalConnectorRegistry.INSTANCE.register(name, config);
  }

  /**
   * Close a named connector. This will stop all background credential refresh processes. All future
   * attempts to connect via this named connection will fail.
   *
   * @param name the name of the connector to close.
   */
  public static void close(String name) {
    InternalConnectorRegistry.INSTANCE.close(name);
  }
}
