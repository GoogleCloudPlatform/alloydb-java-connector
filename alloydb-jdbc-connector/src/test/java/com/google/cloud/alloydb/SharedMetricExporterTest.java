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
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * The exporter and the scheduler are shared by every instance in a project, but each instance
 * registers its own PeriodicMetricReader and a reader shuts down both of them when it stops. These
 * tests pin the wrappers that stop one instance's reader from tearing down everyone else's metrics.
 */
public class SharedMetricExporterTest {

  @Test
  public void testExporterViewDoesNotShutDownTheSharedExporter() {
    CountingExporter delegate = new CountingExporter();

    MetricExporter view = new SharedMetricExporter.NonClosingExporter(delegate);
    view.export(Collections.emptyList());
    view.flush();

    assertThat(view.shutdown().isSuccess()).isTrue();
    view.close();

    assertThat(delegate.exports.get()).isEqualTo(1);
    assertThat(delegate.flushes.get()).isEqualTo(1);
    assertThat(delegate.shutdowns.get()).isEqualTo(0);
  }

  @Test
  public void testSchedulerViewDoesNotShutDownTheSharedScheduler() throws Exception {
    ScheduledExecutorService delegate = Executors.newSingleThreadScheduledExecutor();
    try {
      ScheduledExecutorService view = new SharedMetricExporter.NonClosingScheduler(delegate);

      view.shutdown();
      assertThat(view.shutdownNow()).isEmpty();
      // A reader waits on termination after shutting down; it must not stall for the full timeout.
      assertThat(view.awaitTermination(1, TimeUnit.SECONDS)).isTrue();

      assertThat(delegate.isShutdown()).isFalse();

      // The shared scheduler is still usable by every other instance's reader.
      AtomicInteger ran = new AtomicInteger();
      view.schedule((Runnable) ran::incrementAndGet, 0, TimeUnit.MILLISECONDS).get();
      assertThat(ran.get()).isEqualTo(1);
    } finally {
      delegate.shutdownNow();
    }
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
