/*
 * Copyright 2026 Google LLC
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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import org.junit.Test;

/** Verifies the connector can reach the AlloyDB instance over its public IP and run a query. */
public class ITPublicIpConnectivityTest {

  @Test
  public void testConnectOverPublicIp() throws SQLException {
    Properties props = new Properties();
    props.setProperty("user", System.getenv("ALLOYDB_USER"));
    props.setProperty("password", System.getenv("ALLOYDB_PASS"));
    props.setProperty("socketFactory", "com.google.cloud.alloydb.SocketFactory");
    props.setProperty("alloydbInstanceName", System.getenv("ALLOYDB_INSTANCE_NAME"));
    props.setProperty("alloydbIpType", "PUBLIC");

    String jdbcUrl = "jdbc:postgresql:///" + System.getenv("ALLOYDB_DB");

    try (Connection conn = DriverManager.getConnection(jdbcUrl, props);
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT 1")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getInt(1)).isEqualTo(1);
    }
  }
}
