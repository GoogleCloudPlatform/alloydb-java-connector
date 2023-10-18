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

import com.google.common.collect.BoundType;
import com.google.common.collect.Range;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ITServiceAccountImpersonationTest {

  private HikariDataSource dataSource;

  @Before
  public void setUp() {
    this.dataSource = AlloyDbJdbcServiceAccountImpersonationDataSourceFactory.createDataSource();
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
      try (PreparedStatement statement = connection.prepareStatement("SELECT NOW()")) {
        ResultSet resultSet = statement.executeQuery();
        resultSet.next();
        Timestamp timestamp = resultSet.getTimestamp(1);
        Instant databaseInstant = timestamp.toInstant();

        Instant now = Instant.now();
        assertThat(databaseInstant)
            .isIn(
                Range.range(
                    now.minus(1, ChronoUnit.MINUTES),
                    BoundType.CLOSED,
                    now.plus(1, ChronoUnit.MINUTES),
                    BoundType.CLOSED));
      }
    }
  }
}
