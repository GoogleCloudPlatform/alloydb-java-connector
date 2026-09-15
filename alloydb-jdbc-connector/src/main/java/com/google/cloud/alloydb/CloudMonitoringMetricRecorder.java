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

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Records telemetry metrics using OpenTelemetry with Cloud Monitoring exporter. */
class CloudMonitoringMetricRecorder implements MetricRecorder {

  static final String METER_NAME = "alloydb.googleapis.com/client/connector";
  static final String MONITORED_RESOURCE = "alloydb.googleapis.com/InstanceClient";

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

  private final SharedMetricExporter sharedExporter;
  private final SdkMeterProvider meterProvider;
  private final LongCounter dialCount;
  private final DoubleHistogram dialLatency;
  private final LongUpDownCounter openConnections;
  private final LongCounter bytesTx;
  private final LongCounter bytesRx;
  private final LongCounter refreshCount;

  CloudMonitoringMetricRecorder(
      String projectId, String location, String cluster, String instance, String clientUid)
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

    // Every instance needs its own meter provider because the monitored resource above is fixed
    // per provider, but the gRPC channel and the export thread underneath are shared per project.
    this.sharedExporter = SharedMetricExporter.acquire(projectId);

    PeriodicMetricReader reader;
    try {
      reader =
          PeriodicMetricReader.builder(sharedExporter.exporterView())
              .setExecutor(sharedExporter.schedulerView())
              .setInterval(Duration.ofMillis(DEFAULT_EXPORT_INTERVAL_MS))
              .build();
    } catch (RuntimeException | Error e) {
      sharedExporter.release();
      throw e;
    }

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
    try {
      meterProvider.shutdown().join(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } finally {
      sharedExporter.release();
    }
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
}
