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
import static org.junit.Assert.fail;

import com.google.cloud.alloydb.v1alpha.InstanceName;
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
    final String iamAuthN = "true";
    final String wantQuotaProject = "myNewProject";
    final String ipType = "PUBLIC";
    final String refreshStrategy = "REFRESH_AHEAD";

    Properties props = new Properties();
    props.setProperty(ConnectionConfig.ALLOYDB_INSTANCE_NAME, INSTANCE_NAME);
    props.setProperty(ConnectionConfig.ALLOYDB_NAMED_CONNECTOR, wantNamedConnector);
    props.setProperty(ConnectionConfig.ALLOYDB_TARGET_PRINCIPAL, wantTargetPrincipal);
    props.setProperty(ConnectionConfig.ALLOYDB_DELEGATES, delegates);
    props.setProperty(ConnectionConfig.ALLOYDB_ADMIN_SERVICE_ENDPOINT, wantAdminServiceEndpoint);
    props.setProperty(ConnectionConfig.ALLOYDB_GOOGLE_CREDENTIALS_PATH, wantPath);
    props.setProperty(ConnectionConfig.ENABLE_IAM_AUTH_PROPERTY, iamAuthN);
    props.setProperty(ConnectionConfig.ALLOYDB_QUOTA_PROJECT, wantQuotaProject);
    props.setProperty(ConnectionConfig.ALLOYDB_IP_TYPE, ipType);
    props.setProperty(ConnectionConfig.ALLOYDB_REFRESH_STRATEGY, refreshStrategy);

    ConnectionConfig config = ConnectionConfig.fromConnectionProperties(props);

    assertThat(config.getInstanceName().toString()).isEqualTo(INSTANCE_NAME);
    assertThat(config.getNamedConnector()).isEqualTo(wantNamedConnector);
    assertThat(config.getConnectorConfig().getTargetPrincipal()).isEqualTo(wantTargetPrincipal);
    assertThat(config.getConnectorConfig().getDelegates()).isEqualTo(wantDelegates);
    assertThat(config.getConnectorConfig().getAdminServiceEndpoint())
        .isEqualTo(wantAdminServiceEndpoint);
    assertThat(config.getConnectorConfig().getGoogleCredentialsPath()).isEqualTo(wantPath);
    assertThat(config.getConnectorConfig().getQuotaProject()).isEqualTo(wantQuotaProject);
    assertThat(config.getAuthType()).isEqualTo(AuthType.IAM);
    assertThat(config.getIpType()).isEqualTo(IpType.PUBLIC);
    assertThat(config.getConnectorConfig().getRefreshStrategy())
        .isEqualTo(RefreshStrategy.REFRESH_AHEAD);
  }

  @Test
  public void testConfigFromBuilder() {
    final InstanceName wantInstance = InstanceName.parse(INSTANCE_NAME);
    final String wantNamedConnector = "my-connection";
    final String wantTargetPrincipal = "test@example.com";
    final List<String> wantDelegates = Arrays.asList("test1@example.com", "test2@example.com");
    final String wantAdminServiceEndpoint = "alloydb.googleapis.com:443";
    final String wantPath = "my-path";
    final AuthType wantAuthType = AuthType.PASSWORD;
    final String wantQuotaProject = "myNewProject";
    final IpType ipType = IpType.PRIVATE;

    ConnectorConfig connectorConfig =
        new ConnectorConfig.Builder()
            .withTargetPrincipal(wantTargetPrincipal)
            .withDelegates(wantDelegates)
            .withAdminServiceEndpoint(wantAdminServiceEndpoint)
            .withGoogleCredentialsPath(wantPath)
            .withQuotaProject(wantQuotaProject)
            .build();

    ConnectionConfig config =
        new ConnectionConfig.Builder()
            .withInstanceName(wantInstance)
            .withNamedConnector(wantNamedConnector)
            .withConnectorConfig(connectorConfig)
            .withAuthType(wantAuthType)
            .withIpType(ipType)
            .build();

    assertThat(config.getInstanceName()).isEqualTo(wantInstance);
    assertThat(config.getNamedConnector()).isEqualTo(wantNamedConnector);
    assertThat(config.getConnectorConfig()).isSameInstanceAs(connectorConfig);
    assertThat(config.getConnectorConfig().getTargetPrincipal()).isEqualTo(wantTargetPrincipal);
    assertThat(config.getConnectorConfig().getDelegates()).isEqualTo(wantDelegates);
    assertThat(config.getConnectorConfig().getAdminServiceEndpoint())
        .isEqualTo(wantAdminServiceEndpoint);
    assertThat(config.getConnectorConfig().getGoogleCredentialsPath()).isEqualTo(wantPath);
    assertThat(config.getConnectorConfig().getQuotaProject()).isEqualTo(wantQuotaProject);
    assertThat(config.getAuthType()).isEqualTo(wantAuthType);
    assertThat(config.getIpType()).isEqualTo(IpType.PRIVATE);
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
    final String wantQuotaProject = "myNewProject";
    final IpType ipType = IpType.PUBLIC;

    ConnectorConfig cc =
        new ConnectorConfig.Builder()
            .withTargetPrincipal(wantTargetPrincipal)
            .withDelegates(wantDelegates)
            .withAdminServiceEndpoint(wantAdminServiceEndpoint)
            .withQuotaProject(wantQuotaProject)
            .build();

    ConnectionConfig config =
        new ConnectionConfig.Builder()
            .withInstanceName(wantInstance)
            .withNamedConnector(wantNamedConnector)
            .withConnectorConfig(cc)
            .withIpType(ipType)
            .build();

    assertThat(config.hashCode())
        .isEqualTo(Objects.hashCode(wantInstance, wantNamedConnector, ipType, cc));
  }

  @Test
  public void testInstanceName_withDomainScopedProject() {
    String projectName = "google.com:project";
    String instanceName =
        String.format(
            "projects/%s/locations/<REGION>/clusters/<CLUSTER>/instances/<INSTANCE>", projectName);
    Properties props = new Properties();
    props.setProperty(ConnectionConfig.ALLOYDB_INSTANCE_NAME, instanceName);

    ConnectionConfig config = ConnectionConfig.fromConnectionProperties(props);

    assertThat(config.getInstanceName().getProject()).isEqualTo(projectName);
  }

  @Test
  public void testInstanceName_withInvalidProject() {
    String instanceName = "projects///locations/<REGION>/clusters/<CLUSTER>/instances/<INSTANCE>";
    Properties props = new Properties();
    props.setProperty(ConnectionConfig.ALLOYDB_INSTANCE_NAME, instanceName);

    try {
      ConnectionConfig.fromConnectionProperties(props);
      fail();
    } catch (IllegalArgumentException e) {
      assertThat(e)
          .hasMessageThat()
          .contains(String.format("'%s' must have format", ConnectionConfig.ALLOYDB_INSTANCE_NAME));
    }
  }
}
