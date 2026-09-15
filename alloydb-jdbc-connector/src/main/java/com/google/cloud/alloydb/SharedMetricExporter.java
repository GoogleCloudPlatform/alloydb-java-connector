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

import com.google.cloud.monitoring.v3.MetricServiceSettings;
import com.google.cloud.opentelemetry.metric.GoogleCloudMetricExporter;
import com.google.cloud.opentelemetry.metric.MetricConfiguration;
import com.google.cloud.opentelemetry.metric.MonitoredResourceDescription;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A reference counted, per-project Cloud Monitoring metric exporter and its export scheduler.
 *
 * <p>The monitored resource that identifies an AlloyDB instance is an OpenTelemetry {@code
 * Resource}, which is fixed for the lifetime of an {@code SdkMeterProvider}. Every instance
 * therefore needs its own meter provider and its own metric reader. Without pooling, each instance
 * would also stand up its own {@code MetricServiceClient} — and so its own gRPC channel and
 * transport threads — plus its own export thread. This class lets every instance in a project share
 * a single channel and a single export thread instead.
 *
 * <p>{@code PeriodicMetricReader.shutdown()} shuts down both the exporter and the executor it was
 * given, so readers are handed the non-closing views returned by {@link #exporterView()} and {@link
 * #schedulerView()}. The real resources are torn down when the last {@link #release()} arrives.
 */
class SharedMetricExporter {

  private static final String MONITORED_RESOURCE = "alloydb.googleapis.com/InstanceClient";
  private static final String METRIC_PREFIX = "alloydb.googleapis.com/client/connector";

  // Increase the max inbound metadata size from the default of 8 KB. The Cloud Monitoring
  // API response headers can exceed the default, causing gRPC HeaderListSizeException errors.
  private static final int MAX_INBOUND_METADATA_SIZE = 16 * 1024; // 16 KB

  private static final long SHUTDOWN_TIMEOUT_SECONDS = 10;

  private static final Map<String, SharedMetricExporter> POOL = new HashMap<>();

  private final String projectId;
  private final MetricExporter exporter;
  private final ScheduledExecutorService scheduler;

  // Guarded by SharedMetricExporter.class.
  private int refCount;

  private SharedMetricExporter(
      String projectId, MetricExporter exporter, ScheduledExecutorService scheduler) {
    this.projectId = projectId;
    this.exporter = exporter;
    this.scheduler = scheduler;
  }

  /**
   * Returns the exporter for {@code projectId}, creating it if necessary, and takes a reference on
   * it. Every successful call must be matched by exactly one call to {@link #release()}.
   */
  static synchronized SharedMetricExporter acquire(String projectId) throws IOException {
    SharedMetricExporter shared = POOL.get(projectId);
    if (shared == null) {
      shared = new SharedMetricExporter(projectId, newExporter(projectId), newScheduler(projectId));
      POOL.put(projectId, shared);
    }
    shared.refCount++;
    return shared;
  }

  /** Drops a reference, tearing down the channel and export thread when the last one is gone. */
  void release() {
    synchronized (SharedMetricExporter.class) {
      if (refCount == 0 || --refCount > 0) {
        return;
      }
      POOL.remove(projectId);
    }
    exporter.shutdown().join(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    scheduler.shutdown();
  }

  /** An exporter view whose {@code shutdown()} does not tear down the shared exporter. */
  MetricExporter exporterView() {
    return new NonClosingExporter(exporter);
  }

  /** A scheduler view whose {@code shutdown()} does not tear down the shared scheduler. */
  ScheduledExecutorService schedulerView() {
    return new NonClosingScheduler(scheduler);
  }

  private static ScheduledExecutorService newScheduler(String projectId) {
    ThreadFactory threadFactory =
        r -> {
          Thread t = new Thread(r, "alloydb-metrics-export-" + projectId);
          t.setDaemon(true);
          return t;
        };
    return Executors.newSingleThreadScheduledExecutor(threadFactory);
  }

  private static MetricExporter newExporter(String projectId) throws IOException {
    MonitoredResourceDescription monitoredResourceDescription =
        new MonitoredResourceDescription(
            MONITORED_RESOURCE,
            new HashSet<>(
                Arrays.asList(
                    CloudMonitoringMetricRecorder.PROJECT_ID,
                    CloudMonitoringMetricRecorder.LOCATION,
                    CloudMonitoringMetricRecorder.CLUSTER_ID,
                    CloudMonitoringMetricRecorder.INSTANCE_ID,
                    CloudMonitoringMetricRecorder.CLIENT_UID)));

    MetricServiceSettings metricServiceSettings =
        MetricServiceSettings.newBuilder()
            .setTransportChannelProvider(
                MetricServiceSettings.defaultGrpcTransportProviderBuilder()
                    .setMaxInboundMetadataSize(MAX_INBOUND_METADATA_SIZE)
                    .build())
            .build();

    MetricConfiguration configuration =
        MetricConfiguration.builder()
            .setProjectId(projectId)
            .setPrefix(METRIC_PREFIX)
            .setUseServiceTimeSeries(true)
            .setMonitoredResourceDescription(monitoredResourceDescription)
            .setMetricServiceSettings(metricServiceSettings)
            .setResourceAttributesFilter(key -> false)
            .setInstrumentationLibraryLabelsEnabled(false)
            .build();

    return GoogleCloudMetricExporter.createWithConfiguration(configuration);
  }

  /** Delegates everything except shutdown, which the pool owns. Visible for testing. */
  static final class NonClosingExporter implements MetricExporter {

    private final MetricExporter delegate;

    NonClosingExporter(MetricExporter delegate) {
      this.delegate = delegate;
    }

    @Override
    public AggregationTemporality getAggregationTemporality(InstrumentType instrumentType) {
      return delegate.getAggregationTemporality(instrumentType);
    }

    @Override
    public Aggregation getDefaultAggregation(InstrumentType instrumentType) {
      return delegate.getDefaultAggregation(instrumentType);
    }

    @Override
    public CompletableResultCode export(Collection<MetricData> metrics) {
      return delegate.export(metrics);
    }

    @Override
    public CompletableResultCode flush() {
      return delegate.flush();
    }

    @Override
    public CompletableResultCode shutdown() {
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public void close() {
      // No-op. The pool owns the exporter's lifecycle.
    }
  }

  /** Delegates everything except shutdown, which the pool owns. Visible for testing. */
  static final class NonClosingScheduler implements ScheduledExecutorService {

    private final ScheduledExecutorService delegate;

    NonClosingScheduler(ScheduledExecutorService delegate) {
      this.delegate = delegate;
    }

    @Override
    public void shutdown() {
      // No-op. The pool owns the scheduler's lifecycle.
    }

    @Override
    public List<Runnable> shutdownNow() {
      return Collections.emptyList();
    }

    @Override
    public boolean isShutdown() {
      return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
      return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      // Nothing to wait for: this view never shuts the delegate down.
      return true;
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
      return delegate.schedule(command, delay, unit);
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
      return delegate.schedule(callable, delay, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(
        Runnable command, long initialDelay, long period, TimeUnit unit) {
      return delegate.scheduleAtFixedRate(command, initialDelay, period, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(
        Runnable command, long initialDelay, long delay, TimeUnit unit) {
      return delegate.scheduleWithFixedDelay(command, initialDelay, delay, unit);
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
      return delegate.submit(task);
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
      return delegate.submit(task, result);
    }

    @Override
    public Future<?> submit(Runnable task) {
      return delegate.submit(task);
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
        throws InterruptedException {
      return delegate.invokeAll(tasks);
    }

    @Override
    public <T> List<Future<T>> invokeAll(
        Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
        throws InterruptedException {
      return delegate.invokeAll(tasks, timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
        throws InterruptedException, ExecutionException {
      return delegate.invokeAny(tasks);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
        throws InterruptedException, ExecutionException, TimeoutException {
      return delegate.invokeAny(tasks, timeout, unit);
    }

    @Override
    public void execute(Runnable command) {
      delegate.execute(command);
    }
  }
}
