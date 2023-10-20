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

import com.google.cloud.alloydb.v1beta.InstanceName;
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
    final String wantNamedConnection = "my-connection";
    final String wantTargetPrincipal = "test@example.com";
    final List<String> wantDelegates = Arrays.asList("test1@example.com", "test2@example.com");
    final String delegates = wantDelegates.stream().collect(Collectors.joining(","));
    final String wantAdminServiceEndpoint = "alloydb.googleapis.com:443";

    Properties props = new Properties();
    props.setProperty(ConnectionConfig.ALLOYDB_INSTANCE_NAME, INSTANCE_NAME);
    props.setProperty(ConnectionConfig.ALLOYDB_NAMED_CONNECTION, wantNamedConnection);
    props.setProperty(ConnectionConfig.ALLOYDB_TARGET_PRINCIPAL, wantTargetPrincipal);
    props.setProperty(ConnectionConfig.ALLOYDB_DELEGATES, delegates);
    props.setProperty(ConnectionConfig.ALLOYDB_ADMIN_SERVICE_ENDPOINT, wantAdminServiceEndpoint);

    ConnectionConfig config = ConnectionConfig.fromConnectionProperties(props);

    assertThat(config.getInstanceName().toString()).isEqualTo(INSTANCE_NAME);
    assertThat(config.getNamedConnection()).isEqualTo(wantNamedConnection);
    assertThat(config.getTargetPrincipal()).isEqualTo(wantTargetPrincipal);
    assertThat(config.getDelegates()).isEqualTo(wantDelegates);
    assertThat(config.getAdminServiceEndpoint()).isEqualTo(wantAdminServiceEndpoint);
  }

  @Test
  public void testConfigFromBuilder() {
    final InstanceName wantInstance = InstanceName.parse(INSTANCE_NAME);
    final String wantNamedConnection = "my-connection";
    final String wantTargetPrincipal = "test@example.com";
    final List<String> wantDelegates = Arrays.asList("test1@example.com", "test2@example.com");
    final String wantAdminServiceEndpoint = "alloydb.googleapis.com:443";

    ConnectionConfig config =
        new ConnectionConfig.Builder()
            .withInstanceName(wantInstance)
            .withNamedConnection(wantNamedConnection)
            .withTargetPrincipal(wantTargetPrincipal)
            .withDelegates(wantDelegates)
            .withAdminServiceEndpoint(wantAdminServiceEndpoint)
            .build();

    assertThat(config.getInstanceName()).isEqualTo(wantInstance);
    assertThat(config.getNamedConnection()).isEqualTo(wantNamedConnection);
    assertThat(config.getTargetPrincipal()).isEqualTo(wantTargetPrincipal);
    assertThat(config.getDelegates()).isEqualTo(wantDelegates);
    assertThat(config.getAdminServiceEndpoint()).isEqualTo(wantAdminServiceEndpoint);
  }
}
