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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link MetricRecorder} that remembers what it was told, so a test can assert on it. Shared by
 * every test in this package that observes what the connector records, so that the recorder grows
 * with {@link MetricRecorder} in one place rather than drifting between copies.
 */
final class RecordingMetricRecorder implements MetricRecorder {

  final List<String> dialStatuses = new ArrayList<>();
  final AtomicInteger openConnections = new AtomicInteger();
  final AtomicInteger closedConnections = new AtomicInteger();
  final AtomicLong bytesRx = new AtomicLong();
  final AtomicLong bytesTx = new AtomicLong();

  /**
   * Whether the connector should treat telemetry as turned on. Off by default, as it is in life.
   */
  volatile boolean enabled;

  /**
   * Makes the recording of a successful dial throw a {@link MetadataExchangeException}, which the
   * connector must not mistake for a failed dial. Dial latency is the last thing a successful dial
   * records, so by then the connection exists. The exception type is one of the connector's own on
   * purpose: those are unchecked, so recording a success anywhere inside the handlers that classify
   * a failure would let a recorder's failure be classified as one.
   */
  volatile boolean failDialLatency;

  /** Makes the byte-count methods throw, which the connector is expected to survive. */
  volatile boolean failByteFlushes;

  @Override
  public boolean isEnabled() {
    return enabled;
  }

  @Override
  public void shutdown() {}

  @Override
  public void recordDialCount(TelemetryAttributes attrs) {
    dialStatuses.add(attrs.getDialStatus());
  }

  @Override
  public void recordDialLatency(double latencyMs) {
    if (failDialLatency) {
      throw new MetadataExchangeException("recorder is broken");
    }
  }

  @Override
  public void recordOpenConnection(TelemetryAttributes attrs) {
    openConnections.incrementAndGet();
  }

  @Override
  public void recordClosedConnection(TelemetryAttributes attrs) {
    closedConnections.incrementAndGet();
  }

  @Override
  public void recordBytesRx(long count) {
    if (failByteFlushes) {
      throw new IllegalStateException("recorder is broken");
    }
    bytesRx.addAndGet(count);
  }

  @Override
  public void recordBytesTx(long count) {
    if (failByteFlushes) {
      throw new IllegalStateException("recorder is broken");
    }
    bytesTx.addAndGet(count);
  }

  @Override
  public void recordRefreshCount(TelemetryAttributes attrs) {}
}
