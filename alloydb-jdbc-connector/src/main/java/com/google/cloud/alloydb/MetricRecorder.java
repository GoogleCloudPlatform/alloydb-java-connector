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

/** Interface for recording connector telemetry metrics. */
interface MetricRecorder {

  void shutdown();

  void recordDialCount(TelemetryAttributes attrs);

  void recordDialLatency(double latencyMs, TelemetryAttributes attrs);

  void recordOpenConnection(TelemetryAttributes attrs);

  void recordClosedConnection(TelemetryAttributes attrs);

  void recordBytesRx(long count, TelemetryAttributes attrs);

  void recordBytesTx(long count, TelemetryAttributes attrs);

  void recordRefreshCount(TelemetryAttributes attrs);
}
