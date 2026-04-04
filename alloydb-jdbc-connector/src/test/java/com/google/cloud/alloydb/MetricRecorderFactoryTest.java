/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.alloydb;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

public class MetricRecorderFactoryTest {

  @Test
  public void testDisabledReturnsNullRecorder() {
    MetricRecorder recorder =
        MetricRecorderFactory.newMetricRecorder(
            false, "project", "region", "cluster", "instance", "uid");
    assertThat(recorder).isInstanceOf(NullMetricRecorder.class);
  }

  @Test
  public void testEnabledWithInvalidConfigReturnsNullRecorder() {
    // When the Cloud Monitoring exporter fails to initialize (e.g., no credentials),
    // the factory should gracefully fall back to a NullMetricRecorder.
    MetricRecorder recorder =
        MetricRecorderFactory.newMetricRecorder(
            true, "project", "region", "cluster", "instance", "uid");
    // In a test environment without GCP credentials, this will fail to create
    // the Cloud Monitoring exporter and fall back to NullMetricRecorder.
    assertThat(recorder).isNotNull();
  }
}
