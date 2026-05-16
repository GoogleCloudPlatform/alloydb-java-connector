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

/** A no-op MetricRecorder for when telemetry is disabled. */
class NullMetricRecorder implements MetricRecorder {

  @Override
  public void shutdown() {}

  @Override
  public void recordDialCount(TelemetryAttributes attrs) {}

  @Override
  public void recordDialLatency(double latencyMs, TelemetryAttributes attrs) {}

  @Override
  public void recordOpenConnection(TelemetryAttributes attrs) {}

  @Override
  public void recordClosedConnection(TelemetryAttributes attrs) {}

  @Override
  public void recordBytesRx(long count, TelemetryAttributes attrs) {}

  @Override
  public void recordBytesTx(long count, TelemetryAttributes attrs) {}

  @Override
  public void recordRefreshCount(TelemetryAttributes attrs) {}
}
