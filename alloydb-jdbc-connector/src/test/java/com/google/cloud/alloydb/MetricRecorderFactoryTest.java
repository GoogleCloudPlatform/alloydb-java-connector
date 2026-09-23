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

import java.io.IOException;
import org.junit.Test;

public class MetricRecorderFactoryTest {

  @Test
  public void testDisabledReturnsNullRecorderWithoutBuildingAnExporter() {
    MetricRecorder recorder =
        MetricRecorderFactory.newMetricRecorder(
            false,
            () -> {
              throw new AssertionError("the exporter must not be built when metrics are disabled");
            },
            "project",
            "region",
            "cluster",
            "instance",
            "uid");

    assertThat(recorder).isInstanceOf(NullMetricRecorder.class);
    assertThat(recorder.isEnabled()).isFalse();
  }

  @Test
  public void testExporterFailureFallsBackToNullRecorder() {
    // Telemetry never blocks a connection, so a failure to build the exporter must be swallowed in
    // favor of a no-op recorder.
    MetricRecorder recorder =
        MetricRecorderFactory.newMetricRecorder(
            true,
            () -> {
              throw new IOException("no credentials");
            },
            "project",
            "region",
            "cluster",
            "instance",
            "uid");

    assertThat(recorder).isInstanceOf(NullMetricRecorder.class);
    assertThat(recorder.isEnabled()).isFalse();
  }
}
