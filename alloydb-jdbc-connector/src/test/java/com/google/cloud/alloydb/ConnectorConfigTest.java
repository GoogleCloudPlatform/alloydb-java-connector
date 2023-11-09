/*
 * Copyright 2023 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.alloydb;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.base.Objects;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ConnectorConfigTest {
  @Test
  public void testConfigFromBuilder() {
    final String wantTargetPrincipal = "test@example.com";
    final List<String> wantDelegates = Arrays.asList("test1@example.com", "test2@example.com");
    final String wantAdminServiceEndpoint = "alloydb.googleapis.com:443";

    ConnectorConfig cc =
        new ConnectorConfig.Builder()
            .withTargetPrincipal(wantTargetPrincipal)
            .withDelegates(wantDelegates)
            .withAdminServiceEndpoint(wantAdminServiceEndpoint)
            .build();

    assertThat(cc.getTargetPrincipal()).isEqualTo(wantTargetPrincipal);
    assertThat(cc.getDelegates()).isEqualTo(wantDelegates);
    assertThat(cc.getAdminServiceEndpoint()).isEqualTo(wantAdminServiceEndpoint);
  }

  @Test
  public void testNotEqual_withAdminServiceEndpointNotEqual() {
    ConnectorConfig k1 =
        new ConnectorConfig.Builder()
            .withAdminServiceEndpoint("alloydb.googleapis.com:443")
            .build();
    ConnectorConfig k2 =
        new ConnectorConfig.Builder()
            .withAdminServiceEndpoint("private-alloydb.googleapis.com:443")
            .build();

    assertThat(k1).isNotEqualTo(k2);
    assertThat(k1.hashCode()).isNotEqualTo(k2.hashCode());
  }

  @Test
  public void testEqual_withAdminServiceEndpointEqual() {
    ConnectorConfig k1 =
        new ConnectorConfig.Builder()
            .withAdminServiceEndpoint("alloydb.googleapis.com:443")
            .build();
    ConnectorConfig k2 =
        new ConnectorConfig.Builder()
            .withAdminServiceEndpoint("alloydb.googleapis.com:443")
            .build();

    assertThat(k1).isEqualTo(k2);
    assertThat(k1.hashCode()).isEqualTo(k2.hashCode());
  }

  @Test
  public void testNotEqual_withTargetPrincipalNotEqual() {
    ConnectorConfig k1 =
        new ConnectorConfig.Builder().withTargetPrincipal("joe@example.com").build();
    ConnectorConfig k2 =
        new ConnectorConfig.Builder().withTargetPrincipal("steve@example.com").build();

    assertThat(k1).isNotEqualTo(k2);
    assertThat(k1.hashCode()).isNotEqualTo(k2.hashCode());
  }

  @Test
  public void testEqual_withTargetPrincipalEqual() {
    ConnectorConfig k1 =
        new ConnectorConfig.Builder().withTargetPrincipal("joe@example.com").build();
    ConnectorConfig k2 =
        new ConnectorConfig.Builder().withTargetPrincipal("joe@example.com").build();

    assertThat(k1).isEqualTo(k2);
    assertThat(k1.hashCode()).isEqualTo(k2.hashCode());
  }

  @Test
  public void testNotEqual_withDelegatesNotEqual() {
    ConnectorConfig k1 =
        new ConnectorConfig.Builder().withDelegates(Arrays.asList("joe@example.com")).build();
    ConnectorConfig k2 =
        new ConnectorConfig.Builder().withDelegates(Arrays.asList("steve@example.com")).build();

    assertThat(k1).isNotEqualTo(k2);
    assertThat(k1.hashCode()).isNotEqualTo(k2.hashCode());
  }

  @Test
  public void testEqual_withDelegatesEqual() {
    ConnectorConfig k1 =
        new ConnectorConfig.Builder().withDelegates(Arrays.asList("joe@example.com")).build();
    ConnectorConfig k2 =
        new ConnectorConfig.Builder().withDelegates(Arrays.asList("joe@example.com")).build();

    assertThat(k1).isEqualTo(k2);
    assertThat(k1.hashCode()).isEqualTo(k2.hashCode());
  }

  @Test
  public void testHashCode() {
    final String wantTargetPrincipal = "test@example.com";
    final List<String> wantDelegates = Arrays.asList("test1@example.com", "test2@example.com");
    final String wantAdminServiceEndpoint = "alloydb.googleapis.com:443";
    ConnectorConfig cc =
        new ConnectorConfig.Builder()
            .withTargetPrincipal(wantTargetPrincipal)
            .withDelegates(wantDelegates)
            .withAdminServiceEndpoint(wantAdminServiceEndpoint)
            .build();

    assertThat(cc.hashCode())
        .isEqualTo(Objects.hashCode(wantTargetPrincipal, wantDelegates, wantAdminServiceEndpoint));
  }
}
