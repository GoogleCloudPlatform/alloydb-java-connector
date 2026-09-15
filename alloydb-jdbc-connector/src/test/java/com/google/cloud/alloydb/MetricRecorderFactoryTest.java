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
    assertThat(recorder.isEnabled()).isFalse();
  }

  @Test
  public void testExporterFailureFallsBackToNullRecorder() {
    // A blank project id makes the Cloud Monitoring exporter fail to initialize. The factory must
    // swallow that and hand back a no-op recorder, because telemetry never blocks a connection.
    MetricRecorder recorder =
        MetricRecorderFactory.newMetricRecorder(true, "", "region", "cluster", "instance", "uid");

    assertThat(recorder).isInstanceOf(NullMetricRecorder.class);
    assertThat(recorder.isEnabled()).isFalse();
  }
}
