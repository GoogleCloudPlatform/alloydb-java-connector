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

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class CloudMonitoringMetricRecorderTest {

  /**
   * One exporter is shared by every instance in a project, but each instance registers its own
   * PeriodicMetricReader and a reader shuts down its exporter when it stops. This pins the wrapper
   * that stops one instance shutting down from tearing down everyone else's metrics.
   */
  @Test
  public void testNonClosingExporterDoesNotShutDownTheSharedExporter() {
    CountingExporter delegate = new CountingExporter();

    MetricExporter view = new CloudMonitoringMetricRecorder.NonClosingExporter(delegate);
    view.export(Collections.emptyList());
    view.flush();

    assertThat(view.shutdown().isSuccess()).isTrue();
    view.close();

    assertThat(delegate.exports.get()).isEqualTo(1);
    assertThat(delegate.flushes.get()).isEqualTo(1);
    assertThat(delegate.shutdowns.get()).isEqualTo(0);
  }

  /**
   * The temporality and aggregation a reader uses come from its exporter, so the wrapper has to
   * pass them through. Swallowing them would silently change how metrics are aggregated.
   */
  @Test
  public void testNonClosingExporterDelegatesAggregationSelectors() {
    CountingExporter delegate = new CountingExporter();

    MetricExporter view = new CloudMonitoringMetricRecorder.NonClosingExporter(delegate);

    assertThat(view.getAggregationTemporality(InstrumentType.COUNTER))
        .isEqualTo(delegate.getAggregationTemporality(InstrumentType.COUNTER));
    assertThat(view.getDefaultAggregation(InstrumentType.HISTOGRAM))
        .isEqualTo(delegate.getDefaultAggregation(InstrumentType.HISTOGRAM));
  }

  private static final class CountingExporter implements MetricExporter {

    final AtomicInteger exports = new AtomicInteger();
    final AtomicInteger flushes = new AtomicInteger();
    final AtomicInteger shutdowns = new AtomicInteger();

    @Override
    public AggregationTemporality getAggregationTemporality(InstrumentType instrumentType) {
      return AggregationTemporality.CUMULATIVE;
    }

    @Override
    public Aggregation getDefaultAggregation(InstrumentType instrumentType) {
      return Aggregation.explicitBucketHistogram();
    }

    @Override
    public CompletableResultCode export(Collection<MetricData> metrics) {
      exports.incrementAndGet();
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
      flushes.incrementAndGet();
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
      shutdowns.incrementAndGet();
      return CompletableResultCode.ofSuccess();
    }
  }
}
