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
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;

/** Records telemetry metrics using OpenTelemetry with Cloud Monitoring exporter. */
class CloudMonitoringMetricRecorder implements MetricRecorder {

  static final String METER_NAME = "alloydb.googleapis.com/client/connector";
  static final String MONITORED_RESOURCE = "alloydb.googleapis.com/InstanceClient";
  static final String METRIC_PREFIX = "alloydb.googleapis.com/client/connector";

  // Resource attribute keys.
  static final String RESOURCE_TYPE_KEY = "gcp.resource_type";
  static final String PROJECT_ID = "project_id";
  static final String LOCATION = "location";
  static final String CLUSTER_ID = "cluster_id";
  static final String INSTANCE_ID = "instance_id";
  static final String CLIENT_UID = "client_uid";

  // Metric names.
  static final String DIAL_COUNT = "dial_count";
  static final String DIAL_LATENCY = "dial_latencies";
  static final String OPEN_CONNECTIONS = "open_connections";
  static final String BYTES_SENT = "bytes_sent_count";
  static final String BYTES_RECEIVED = "bytes_received_count";
  static final String REFRESH_COUNT = "refresh_count";

  static final String CONNECTOR_TYPE = "java";

  private static final long DEFAULT_EXPORT_INTERVAL_MS = 60_000;
  private static final long SHUTDOWN_TIMEOUT_SECONDS = 10;

  // Increase the max inbound metadata size from the default of 8 KB. The Cloud Monitoring
  // API response headers can exceed the default, causing gRPC HeaderListSizeException errors.
  private static final int MAX_INBOUND_METADATA_SIZE = 16 * 1024; // 16 KB

  // AttributeKey.stringKey() allocates a fresh key on every call, so hoist the keys used on the
  // recording paths to constants rather than rebuilding them per recording.
  private static final AttributeKey<String> CONNECTOR_TYPE_KEY =
      AttributeKey.stringKey(TelemetryAttributes.CONNECTOR_TYPE);
  private static final AttributeKey<String> AUTH_TYPE_KEY =
      AttributeKey.stringKey(TelemetryAttributes.AUTH_TYPE);
  private static final AttributeKey<Boolean> IS_CACHE_HIT_KEY =
      AttributeKey.booleanKey(TelemetryAttributes.IS_CACHE_HIT);
  private static final AttributeKey<String> STATUS_KEY =
      AttributeKey.stringKey(TelemetryAttributes.STATUS);
  private static final AttributeKey<String> REFRESH_TYPE_KEY =
      AttributeKey.stringKey(TelemetryAttributes.REFRESH_TYPE);

  // dial_latencies and the byte counters carry only connector_type, which never varies, so the
  // attribute set is built once here instead of on every recording.
  private static final Attributes CONNECTOR_TYPE_ONLY =
      Attributes.of(CONNECTOR_TYPE_KEY, CONNECTOR_TYPE);

  private final SdkMeterProvider meterProvider;
  private final LongCounter dialCount;
  private final DoubleHistogram dialLatency;
  private final LongUpDownCounter openConnections;
  private final LongCounter bytesTx;
  private final LongCounter bytesRx;
  private final LongCounter refreshCount;

  CloudMonitoringMetricRecorder(
      MetricExporter sharedExporter,
      String projectId,
      String location,
      String cluster,
      String instance,
      String clientUid) {

    Resource resource =
        Resource.create(
            Attributes.of(
                AttributeKey.stringKey(RESOURCE_TYPE_KEY), MONITORED_RESOURCE,
                AttributeKey.stringKey(PROJECT_ID), projectId,
                AttributeKey.stringKey(LOCATION), location,
                AttributeKey.stringKey(CLUSTER_ID), cluster,
                AttributeKey.stringKey(INSTANCE_ID), instance,
                AttributeKey.stringKey(CLIENT_UID), clientUid));

    // Every instance needs its own meter provider, because the resource above identifies the
    // instance and is fixed for a provider's lifetime. The exporter underneath is shared, so it is
    // wrapped: PeriodicMetricReader.shutdown() shuts down whatever exporter it was handed, and one
    // instance going away must not stop every other instance's metrics.
    PeriodicMetricReader reader =
        PeriodicMetricReader.builder(new NonClosingExporter(sharedExporter))
            .setInterval(Duration.ofMillis(DEFAULT_EXPORT_INTERVAL_MS))
            .build();

    this.meterProvider =
        SdkMeterProvider.builder().setResource(resource).registerMetricReader(reader).build();

    Meter meter = meterProvider.get(METER_NAME);

    this.dialCount = meter.counterBuilder(DIAL_COUNT).build();
    this.dialLatency = meter.histogramBuilder(DIAL_LATENCY).build();
    this.openConnections = meter.upDownCounterBuilder(OPEN_CONNECTIONS).build();
    this.bytesTx = meter.counterBuilder(BYTES_SENT).build();
    this.bytesRx = meter.counterBuilder(BYTES_RECEIVED).build();
    this.refreshCount = meter.counterBuilder(REFRESH_COUNT).build();
  }

  @Override
  public boolean isEnabled() {
    return true;
  }

  @Override
  public void shutdown() {
    meterProvider.shutdown().join(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  private static Attributes dialAttributes(TelemetryAttributes attrs) {
    return Attributes.of(
        CONNECTOR_TYPE_KEY,
        CONNECTOR_TYPE,
        AUTH_TYPE_KEY,
        TelemetryAttributes.authTypeValue(attrs.isIamAuthn()),
        IS_CACHE_HIT_KEY,
        attrs.isCacheHit(),
        STATUS_KEY,
        attrs.getDialStatus());
  }

  private static Attributes connectionAttributes(TelemetryAttributes attrs) {
    return Attributes.of(
        CONNECTOR_TYPE_KEY,
        CONNECTOR_TYPE,
        AUTH_TYPE_KEY,
        TelemetryAttributes.authTypeValue(attrs.isIamAuthn()));
  }

  @Override
  public void recordDialCount(TelemetryAttributes attrs) {
    dialCount.add(1, dialAttributes(attrs));
  }

  @Override
  public void recordDialLatency(double latencyMs) {
    dialLatency.record(latencyMs, CONNECTOR_TYPE_ONLY);
  }

  @Override
  public void recordOpenConnection(TelemetryAttributes attrs) {
    openConnections.add(1, connectionAttributes(attrs));
  }

  @Override
  public void recordClosedConnection(TelemetryAttributes attrs) {
    openConnections.add(-1, connectionAttributes(attrs));
  }

  @Override
  public void recordBytesRx(long count) {
    bytesRx.add(count, CONNECTOR_TYPE_ONLY);
  }

  @Override
  public void recordBytesTx(long count) {
    bytesTx.add(count, CONNECTOR_TYPE_ONLY);
  }

  @Override
  public void recordRefreshCount(TelemetryAttributes attrs) {
    refreshCount.add(
        1,
        Attributes.of(
            CONNECTOR_TYPE_KEY,
            CONNECTOR_TYPE,
            STATUS_KEY,
            attrs.getRefreshStatus(),
            REFRESH_TYPE_KEY,
            attrs.getRefreshType()));
  }

  /**
   * Builds the Cloud Monitoring exporter for {@code projectId}. This stands up a {@code
   * MetricServiceClient}, and so a gRPC channel and its transport threads, which is why callers
   * share one exporter across every instance in a project rather than building one per instance.
   * The returned exporter is safe for concurrent use by several readers.
   */
  static MetricExporter newExporter(String projectId) throws IOException {
    MonitoredResourceDescription monitoredResourceDescription =
        new MonitoredResourceDescription(
            MONITORED_RESOURCE,
            new HashSet<>(
                Arrays.asList(PROJECT_ID, LOCATION, CLUSTER_ID, INSTANCE_ID, CLIENT_UID)));

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

  /**
   * Delegates everything but shutdown to a shared exporter. Each instance registers its own {@link
   * PeriodicMetricReader}, and a reader shuts down its exporter when it stops, so readers are
   * handed one of these instead. The owner of the shared exporter shuts it down. Visible for
   * testing.
   */
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
      // No-op. The shared exporter's owner closes it.
    }
  }
}
