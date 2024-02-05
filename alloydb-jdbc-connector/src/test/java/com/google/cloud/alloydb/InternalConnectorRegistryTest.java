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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.cloud.alloydb.v1alpha.InstanceName;
import java.io.IOException;
import java.util.Collections;
import java.util.Properties;
import org.junit.Before;
import org.junit.Test;

public class InternalConnectorRegistryTest {

  private static final String INSTANCE_NAME =
      "projects/<PROJECT>/locations/<REGION>/clusters/<CLUSTER>/instances/<INSTANCE>";

  @Before
  public void setUp() {
    InternalConnectorRegistry.INSTANCE.setCredentialFactoryProvider(
        new CredentialFactoryProvider(new StubCredentialFactory()));
  }

  @Test
  public void create_failOnInvalidInstanceName() throws IOException {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                InternalConnectorRegistry.INSTANCE.connect(
                    new ConnectionConfig.Builder()
                        .withInstanceName(InstanceName.parse("myProject"))
                        .build()));
    assertThat(ex)
        .hasMessageThat()
        .contains("InstanceName.parse: formattedString not in valid format");
  }

  @Test
  public void create_failOnEmptyTargetPrincipal() throws IOException, InterruptedException {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                InternalConnectorRegistry.INSTANCE.connect(
                    new ConnectionConfig.Builder()
                        .withInstanceName(InstanceName.parse(INSTANCE_NAME))
                        .withConnectorConfig(
                            new ConnectorConfig.Builder()
                                .withDelegates(
                                    Collections.singletonList(
                                        "delegate-service-principal@example.com"))
                                .build())
                        .build()));
    assertThat(ex.getMessage()).contains(ConnectionConfig.ALLOYDB_TARGET_PRINCIPAL);
  }

  @Test
  public void registerConnection_failWithDuplicateName() throws InterruptedException {
    // Register a Connection
    String namedConnector = "my-connection-name";
    ConnectorConfig configWithDetails = new ConnectorConfig.Builder().build();
    InternalConnectorRegistry.INSTANCE.register(namedConnector, configWithDetails);

    // Assert that you can't register a connection with a duplicate name
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> InternalConnectorRegistry.INSTANCE.register(namedConnector, configWithDetails));
    assertThat(ex)
        .hasMessageThat()
        .contains(String.format("Named connection %s exists.", namedConnector));
  }

  @Test
  public void registerConnection_failWithDuplicateNameAndDifferentConfig()
      throws InterruptedException {
    String namedConnector = "test-connection";
    ConnectorConfig config =
        new ConnectorConfig.Builder().withTargetPrincipal("joe@test.com").build();
    InternalConnectorRegistry.INSTANCE.register(namedConnector, config);

    ConnectorConfig config2 =
        new ConnectorConfig.Builder().withTargetPrincipal("jane@test.com").build();

    // Assert that you can't register a connection with a duplicate name
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> InternalConnectorRegistry.INSTANCE.register(namedConnector, config2));
    assertThat(ex)
        .hasMessageThat()
        .contains(String.format("Named connection %s exists.", namedConnector));
  }

  @Test
  public void closeNamedConnection_failWhenNotFound() throws InterruptedException {
    String namedConnector = "a-connection";
    // Assert that you can't close a connection that doesn't exist
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> InternalConnectorRegistry.INSTANCE.close(namedConnector));
    assertThat(ex)
        .hasMessageThat()
        .contains(String.format("Named connection %s does not exist.", namedConnector));
  }

  @Test
  public void connect_failOnClosedNamedConnection() throws InterruptedException {
    // Register a Connection
    String namedConnector = "my-connection";
    ConnectorConfig configWithDetails = new ConnectorConfig.Builder().build();
    InternalConnectorRegistry.INSTANCE.register(namedConnector, configWithDetails);

    // Close the named connection.
    InternalConnectorRegistry.INSTANCE.close(namedConnector);

    // Attempt and fail to connect using the cloudSqlNamedConnection connection property
    Properties connProps = new Properties();
    connProps.setProperty(ConnectionConfig.ALLOYDB_NAMED_CONNECTOR, namedConnector);
    connProps.setProperty(ConnectionConfig.ALLOYDB_INSTANCE_NAME, INSTANCE_NAME);
    ConnectionConfig nameOnlyConfig = ConnectionConfig.fromConnectionProperties(connProps);

    // Assert that no connection is possible because the connector is closed.
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> InternalConnectorRegistry.INSTANCE.connect(nameOnlyConfig));
    assertThat(ex)
        .hasMessageThat()
        .contains(String.format("Named connection %s does not exist.", namedConnector));
  }

  @Test
  public void connect_failOnUnknownNamedConnection() throws InterruptedException {
    // Attempt and fail to connect using the Named Connection not registered
    String namedConnector = "first-connection";
    Properties connProps = new Properties();
    connProps.setProperty(ConnectionConfig.ALLOYDB_NAMED_CONNECTOR, namedConnector);
    connProps.setProperty(ConnectionConfig.ALLOYDB_INSTANCE_NAME, INSTANCE_NAME);
    ConnectionConfig nameOnlyConfig = ConnectionConfig.fromConnectionProperties(connProps);
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> InternalConnectorRegistry.INSTANCE.connect(nameOnlyConfig));
    assertThat(ex)
        .hasMessageThat()
        .contains(String.format("Named connection %s does not exist.", namedConnector));
  }

  @Test
  public void connect_failOnNamedConnectionAfterResetInstance() throws InterruptedException {
    // Register a Connection
    String namedConnector = "this-connection";
    ConnectorConfig config = new ConnectorConfig.Builder().build();
    Properties connProps = new Properties();
    connProps.setProperty(ConnectionConfig.ALLOYDB_INSTANCE_NAME, INSTANCE_NAME);
    connProps.setProperty(ConnectionConfig.ALLOYDB_NAMED_CONNECTOR, namedConnector);
    ConnectionConfig nameOnlyConfig = ConnectionConfig.fromConnectionProperties(connProps);

    InternalConnectorRegistry.INSTANCE.register(namedConnector, config);

    InternalConnectorRegistry.INSTANCE.resetInstance();

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> InternalConnectorRegistry.INSTANCE.connect(nameOnlyConfig));
    assertThat(ex)
        .hasMessageThat()
        .contains(String.format("Named connection %s does not exist.", namedConnector));
  }
}
