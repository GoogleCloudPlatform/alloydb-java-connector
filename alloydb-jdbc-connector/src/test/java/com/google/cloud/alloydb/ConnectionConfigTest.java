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
import java.util.Properties;
import org.junit.Test;

public class ConnectionConfigTest {

  private static final String INSTANCE_NAME =
      "projects/<PROJECT>/locations/<REGION>/clusters/<CLUSTER>/instances/<INSTANCE>";

  @Test
  public void testConfigFromProps() {

    Properties props = new Properties();
    props.setProperty(ConnectionConfig.ALLOYDB_INSTANCE_NAME, INSTANCE_NAME);

    ConnectionConfig config = ConnectionConfig.fromConnectionProperties(props);

    assertThat(config.getInstanceName().toString()).isEqualTo(INSTANCE_NAME);
  }

  @Test
  public void testConfigFromBuilder() {
    final InstanceName wantInstance = InstanceName.parse(INSTANCE_NAME);

    ConnectionConfig config = new ConnectionConfig.Builder().withInstanceName(wantInstance).build();

    assertThat(config.getInstanceName()).isEqualTo(wantInstance);
  }
}
