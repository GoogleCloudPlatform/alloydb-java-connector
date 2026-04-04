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
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

  private static final long DEFAULT_EXPORT_INTERVAL_MS = 60_000;
  public static final String CONNECTOR_TYPE = "java";

  private final SdkMeterProvider meterProvider;
  private final LongCounter dialCount;
  private final DoubleHistogram dialLatency;
  private final LongUpDownCounter openConnections;
  private final LongCounter bytesTx;
  private final LongCounter bytesRx;
  private final LongCounter refreshCount;

  private static final int MAX_INBOUND_METADATA_SIZE = 16 * 1024; // 16 KB

  CloudMonitoringMetricRecorder(
      String projectId,
      String location,
      String cluster,
      String instance,
      String clientUid)
      throws IOException {

    Resource resource =
        Resource.create(
            Attributes.of(
                AttributeKey.stringKey(RESOURCE_TYPE_KEY), MONITORED_RESOURCE,
                AttributeKey.stringKey(PROJECT_ID), projectId,
                AttributeKey.stringKey(LOCATION), location,
                AttributeKey.stringKey(CLUSTER_ID), cluster,
                AttributeKey.stringKey(INSTANCE_ID), instance,
                AttributeKey.stringKey(CLIENT_UID), clientUid));

    MonitoredResourceDescription monitoredResourceDescription =
        new MonitoredResourceDescription(
            MONITORED_RESOURCE,
            new HashSet<>(Arrays.asList(PROJECT_ID, LOCATION, CLUSTER_ID, INSTANCE_ID, CLIENT_UID)));

    // Increase the max inbound metadata size from the default of 8 KB. The Cloud Monitoring
    // API response headers can exceed the default, causing gRPC HeaderListSizeException errors.
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
    MetricExporter exporter = GoogleCloudMetricExporter.createWithConfiguration(configuration);

    PeriodicMetricReader reader =
        PeriodicMetricReader.builder(exporter)
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
  public void shutdown() {
    meterProvider.shutdown();
  }

  @Override
  public void recordDialCount(TelemetryAttributes attrs) {
    dialCount.add(
        1,
        Attributes.of(
            AttributeKey.stringKey(TelemetryAttributes.CONNECTOR_TYPE),
            CONNECTOR_TYPE,
            AttributeKey.stringKey(TelemetryAttributes.AUTH_TYPE),
            TelemetryAttributes.authTypeValue(attrs.isIamAuthn()),
            AttributeKey.booleanKey(TelemetryAttributes.IS_CACHE_HIT),
            attrs.isCacheHit(),
            AttributeKey.stringKey(TelemetryAttributes.STATUS),
            attrs.getDialStatus()));
  }

  @Override
  public void recordDialLatency(double latencyMs, TelemetryAttributes attrs) {
    dialLatency.record(
        latencyMs,
        Attributes.of(
            AttributeKey.stringKey(TelemetryAttributes.CONNECTOR_TYPE),
            CONNECTOR_TYPE));
  }

  @Override
  public void recordOpenConnection(TelemetryAttributes attrs) {
    openConnections.add(
        1,
        Attributes.of(
            AttributeKey.stringKey(TelemetryAttributes.CONNECTOR_TYPE),
            CONNECTOR_TYPE,
            AttributeKey.stringKey(TelemetryAttributes.AUTH_TYPE),
            TelemetryAttributes.authTypeValue(attrs.isIamAuthn())));
  }

  @Override
  public void recordClosedConnection(TelemetryAttributes attrs) {
    openConnections.add(
        -1,
        Attributes.of(
            AttributeKey.stringKey(TelemetryAttributes.CONNECTOR_TYPE),
            CONNECTOR_TYPE,
            AttributeKey.stringKey(TelemetryAttributes.AUTH_TYPE),
            TelemetryAttributes.authTypeValue(attrs.isIamAuthn())));
  }

  @Override
  public void recordBytesRx(long count, TelemetryAttributes attrs) {
    bytesRx.add(
        count,
        Attributes.of(
            AttributeKey.stringKey(TelemetryAttributes.CONNECTOR_TYPE),
            CONNECTOR_TYPE));
  }

  @Override
  public void recordBytesTx(long count, TelemetryAttributes attrs) {
    bytesTx.add(
        count,
        Attributes.of(
            AttributeKey.stringKey(TelemetryAttributes.CONNECTOR_TYPE),
            CONNECTOR_TYPE));
  }

  @Override
  public void recordRefreshCount(TelemetryAttributes attrs) {
    refreshCount.add(
        1,
        Attributes.of(
            AttributeKey.stringKey(TelemetryAttributes.CONNECTOR_TYPE),
            CONNECTOR_TYPE,
            AttributeKey.stringKey(TelemetryAttributes.STATUS),
            attrs.getRefreshStatus(),
            AttributeKey.stringKey(TelemetryAttributes.REFRESH_TYPE),
            attrs.getRefreshType()));
  }
}
