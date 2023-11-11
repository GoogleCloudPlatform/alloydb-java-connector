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

import com.google.cloud.alloydb.v1.InstanceName;
import com.google.common.base.Objects;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;
import org.junit.Test;

public class ConnectionConfigTest {

  private static final String INSTANCE_NAME =
      "projects/<PROJECT>/locations/<REGION>/clusters/<CLUSTER>/instances/<INSTANCE>";

  @Test
  public void testConfigFromProps() {
    final String wantNamedConnector = "my-connection";
    final String wantTargetPrincipal = "test@example.com";
    final List<String> wantDelegates = Arrays.asList("test1@example.com", "test2@example.com");
    final String delegates = wantDelegates.stream().collect(Collectors.joining(","));
    final String wantAdminServiceEndpoint = "alloydb.googleapis.com:443";
    final String wantPath = "my-path";

    Properties props = new Properties();
    props.setProperty(ConnectionConfig.ALLOYDB_INSTANCE_NAME, INSTANCE_NAME);
    props.setProperty(ConnectionConfig.ALLOYDB_NAMED_CONNECTOR, wantNamedConnector);
    props.setProperty(ConnectionConfig.ALLOYDB_TARGET_PRINCIPAL, wantTargetPrincipal);
    props.setProperty(ConnectionConfig.ALLOYDB_DELEGATES, delegates);
    props.setProperty(ConnectionConfig.ALLOYDB_ADMIN_SERVICE_ENDPOINT, wantAdminServiceEndpoint);
    props.setProperty(ConnectionConfig.ALLOYDB_GOOGLE_CREDENTIALS_PATH, wantPath);

    ConnectionConfig config = ConnectionConfig.fromConnectionProperties(props);

    assertThat(config.getInstanceName().toString()).isEqualTo(INSTANCE_NAME);
    assertThat(config.getNamedConnector()).isEqualTo(wantNamedConnector);
    assertThat(config.getConnectorConfig().getTargetPrincipal()).isEqualTo(wantTargetPrincipal);
    assertThat(config.getConnectorConfig().getDelegates()).isEqualTo(wantDelegates);
    assertThat(config.getConnectorConfig().getAdminServiceEndpoint())
        .isEqualTo(wantAdminServiceEndpoint);
    assertThat(config.getConnectorConfig().getGoogleCredentialsPath()).isEqualTo(wantPath);
  }

  @Test
  public void testConfigFromBuilder() {
    final InstanceName wantInstance = InstanceName.parse(INSTANCE_NAME);
    final String wantNamedConnector = "my-connection";
    final String wantTargetPrincipal = "test@example.com";
    final List<String> wantDelegates = Arrays.asList("test1@example.com", "test2@example.com");
    final String wantAdminServiceEndpoint = "alloydb.googleapis.com:443";
    final String wantPath = "my-path";

    ConnectorConfig connectorConfig =
        new ConnectorConfig.Builder()
            .withTargetPrincipal(wantTargetPrincipal)
            .withDelegates(wantDelegates)
            .withAdminServiceEndpoint(wantAdminServiceEndpoint)
            .withGoogleCredentialsPath(wantPath)
            .build();

    ConnectionConfig config =
        new ConnectionConfig.Builder()
            .withInstanceName(wantInstance)
            .withNamedConnector(wantNamedConnector)
            .withConnectorConfig(connectorConfig)
            .build();

    assertThat(config.getInstanceName()).isEqualTo(wantInstance);
    assertThat(config.getNamedConnector()).isEqualTo(wantNamedConnector);
    assertThat(config.getConnectorConfig()).isSameInstanceAs(connectorConfig);
    assertThat(config.getConnectorConfig().getTargetPrincipal()).isEqualTo(wantTargetPrincipal);
    assertThat(config.getConnectorConfig().getDelegates()).isEqualTo(wantDelegates);
    assertThat(config.getConnectorConfig().getAdminServiceEndpoint())
        .isEqualTo(wantAdminServiceEndpoint);
    assertThat(config.getConnectorConfig().getGoogleCredentialsPath()).isEqualTo(wantPath);
  }

  @Test
  public void testWithConnectorConfig() {
    final InstanceName wantInstance = InstanceName.parse(INSTANCE_NAME);
    final String wantNamedConnector = "my-connection";

    ConnectorConfig connectorConfig = new ConnectorConfig.Builder().build();

    ConnectionConfig config =
        new ConnectionConfig.Builder()
            .withInstanceName(wantInstance)
            .withNamedConnector(wantNamedConnector)
            .build();

    assertThat(config.getInstanceName()).isEqualTo(wantInstance);
    assertThat(config.getNamedConnector()).isEqualTo(wantNamedConnector);
    assertThat(config.getConnectorConfig()).isNotSameInstanceAs(connectorConfig);

    ConnectionConfig newConfig = config.withConnectorConfig(connectorConfig);

    assertThat(newConfig).isNotSameInstanceAs(config);
    assertThat(newConfig.getInstanceName()).isEqualTo(wantInstance);
    assertThat(newConfig.getNamedConnector()).isEqualTo(wantNamedConnector);
    assertThat(newConfig.getConnectorConfig()).isSameInstanceAs(connectorConfig);
  }

  @Test
  public void testEqual_withInstanceNameEqual() {
    ConnectionConfig k1 =
        new ConnectionConfig.Builder().withInstanceName(InstanceName.parse(INSTANCE_NAME)).build();
    ConnectionConfig k2 =
        new ConnectionConfig.Builder().withInstanceName(InstanceName.parse(INSTANCE_NAME)).build();

    assertThat(k1).isEqualTo(k2);
    assertThat(k1.hashCode()).isEqualTo(k2.hashCode());
  }

  @Test
  public void testNotEqual_withInstanceNameNotEqual() {
    ConnectionConfig k1 =
        new ConnectionConfig.Builder().withInstanceName(InstanceName.parse(INSTANCE_NAME)).build();
    ConnectionConfig k2 =
        new ConnectionConfig.Builder()
            .withInstanceName(InstanceName.parse(INSTANCE_NAME + "diff"))
            .build();

    assertThat(k1).isNotEqualTo(k2);
    assertThat(k1.hashCode()).isNotEqualTo(k2.hashCode());
  }

  @Test
  public void testEqual_withNamedConnectorEqual() {
    ConnectionConfig k1 =
        new ConnectionConfig.Builder().withNamedConnector("my-connection").build();
    ConnectionConfig k2 =
        new ConnectionConfig.Builder().withNamedConnector("my-connection").build();

    assertThat(k1).isEqualTo(k2);
    assertThat(k1.hashCode()).isEqualTo(k2.hashCode());
  }

  @Test
  public void testNotEqual_withNamedConnectorNotEqual() {
    ConnectionConfig k1 =
        new ConnectionConfig.Builder().withNamedConnector("my-connection").build();
    ConnectionConfig k2 =
        new ConnectionConfig.Builder().withNamedConnector("new-connection").build();

    assertThat(k1).isNotEqualTo(k2);
    assertThat(k1.hashCode()).isNotEqualTo(k2.hashCode());
  }

  @Test
  public void testEqual_withConnectorConfigEqual() {
    ConnectionConfig k1 =
        new ConnectionConfig.Builder()
            .withConnectorConfig(new ConnectorConfig.Builder().build())
            .build();
    ConnectionConfig k2 =
        new ConnectionConfig.Builder()
            .withConnectorConfig(new ConnectorConfig.Builder().build())
            .build();

    assertThat(k1).isEqualTo(k2);
    assertThat(k1.hashCode()).isEqualTo(k2.hashCode());
  }

  @Test
  public void testNotEqual_withConnectorConfigNotEqual() {
    ConnectionConfig k1 =
        new ConnectionConfig.Builder()
            .withConnectorConfig(new ConnectorConfig.Builder().build())
            .build();
    ConnectionConfig k2 =
        new ConnectionConfig.Builder()
            .withConnectorConfig(
                new ConnectorConfig.Builder().withTargetPrincipal("test@example.com").build())
            .build();

    assertThat(k1).isNotEqualTo(k2);
    assertThat(k1.hashCode()).isNotEqualTo(k2.hashCode());
  }

  @Test
  public void testHashCode() {
    final String wantTargetPrincipal = "test@example.com";
    final List<String> wantDelegates = Arrays.asList("test1@example.com", "test2@example.com");
    final String wantAdminServiceEndpoint = "alloydb.googleapis.com:443";
    final InstanceName wantInstance = InstanceName.parse(INSTANCE_NAME);
    final String wantNamedConnector = "my-connection";

    ConnectorConfig cc =
        new ConnectorConfig.Builder()
            .withTargetPrincipal(wantTargetPrincipal)
            .withDelegates(wantDelegates)
            .withAdminServiceEndpoint(wantAdminServiceEndpoint)
            .build();

    ConnectionConfig config =
        new ConnectionConfig.Builder()
            .withInstanceName(wantInstance)
            .withNamedConnector(wantNamedConnector)
            .withConnectorConfig(cc)
            .build();

    assertThat(config.hashCode()).isEqualTo(Objects.hashCode(wantInstance, wantNamedConnector, cc));
  }
}
