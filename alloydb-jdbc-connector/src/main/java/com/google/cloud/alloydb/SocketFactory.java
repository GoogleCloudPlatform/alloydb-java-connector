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
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Properties;

@SuppressWarnings("unused") // Used indirectly through the Postgres Driver
public class SocketFactory extends javax.net.SocketFactory {

  private static final String ALLOYDB_INSTANCE_NAME = "alloydbInstanceName";
  private final InstanceName instanceName;
  private final Connector connector;

  public SocketFactory(Properties properties) {
    String instanceName = properties.getProperty(ALLOYDB_INSTANCE_NAME);
    this.instanceName = InstanceName.parse(instanceName);
    this.connector = ConnectorRegistry.INSTANCE.getConnector();
  }

  @Override
  public Socket createSocket() throws IOException {
    return this.connector.connect(instanceName);
  }

  /*
   * Everything below must be overridden by the subclass, but is otherwise unused.
   */
  @Override
  public Socket createSocket(String host, int port) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Socket createSocket(String host, int port, InetAddress localHost, int localPort) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Socket createSocket(InetAddress host, int port) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Socket createSocket(
      InetAddress address, int port, InetAddress localAddress, int localPort) {
    throw new UnsupportedOperationException();
  }
}
