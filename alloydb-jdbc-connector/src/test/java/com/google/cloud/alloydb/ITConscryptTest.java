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

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Runs real queries against a real instance over a connection served by Conscrypt.
 *
 * <p>{@code ITConnectorTest} covers the handshake itself. This covers what happens afterwards: the
 * PostgreSQL JDBC driver reads and writes the socket the connector hands it, and under Conscrypt
 * that is a {@code Java8EngineSocket} rather than the JRE's {@code SSLSocketImpl}. The driver's
 * stream handling, the connector's own length-prefixed metadata exchange, and connection pooling
 * all have to work on it.
 */
public class ITConscryptTest {

  private HikariDataSource dataSource;

  @Before
  public void setUp() {
    // The scheduled GraalVM job runs this class -- the native profile's surefire include list
    // matches IT*.java -- and Conscrypt cannot load there. See NativeImage.
    NativeImage.assumeConscryptIsLoadable();
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(String.format("jdbc:postgresql:///%s", System.getenv("ALLOYDB_DB")));
    config.setUsername(System.getenv("ALLOYDB_USER"));
    config.setPassword(System.getenv("ALLOYDB_PASS"));
    config.addDataSourceProperty("socketFactory", "com.google.cloud.alloydb.SocketFactory");
    config.addDataSourceProperty("alloydbInstanceName", System.getenv("ALLOYDB_INSTANCE_NAME"));
    // CONSCRYPT rather than AUTO: this test should fail, not quietly fall back to a classical
    // handshake, if the provider cannot serve the connection.
    config.addDataSourceProperty("alloydbTlsProvider", "CONSCRYPT");
    this.dataSource = new HikariDataSource(config);
  }

  @After
  public void tearDown() {
    if (this.dataSource != null) {
      dataSource.close();
    }
  }

  @Test
  public void testConnect() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      try (PreparedStatement statement = connection.prepareStatement("SELECT 1")) {
        ResultSet resultSet = statement.executeQuery();
        resultSet.next();

        assertThat(resultSet.getInt(1)).isEqualTo(1);
      }
    }
  }

  /**
   * A payload larger than one TLS record, read back in full. A socket whose stream handling is
   * subtly wrong tends to pass a one-row query and fail here.
   */
  @Test
  public void testConnect_readsAPayloadSpanningManyRecords() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      try (PreparedStatement statement =
          connection.prepareStatement("SELECT repeat('x', 1000000)")) {
        ResultSet resultSet = statement.executeQuery();
        resultSet.next();

        assertThat(resultSet.getString(1)).hasLength(1000000);
      }
    }
  }

  /** Pooled connections are reused and reopened; both have to work on a Conscrypt socket. */
  @Test
  public void testConnect_reusesPooledConnections() throws SQLException {
    for (int i = 0; i < 3; i++) {
      try (Connection connection = dataSource.getConnection()) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1")) {
          ResultSet resultSet = statement.executeQuery();
          resultSet.next();

          assertThat(resultSet.getInt(1)).isEqualTo(1);
        }
      }
    }
  }
}
