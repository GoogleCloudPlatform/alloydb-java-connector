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

import io.opentelemetry.sdk.metrics.export.MetricExporter;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Factory for creating MetricRecorder instances. */
class MetricRecorderFactory {

  private static final Logger logger = LoggerFactory.getLogger(MetricRecorderFactory.class);

  /**
   * Supplies the Cloud Monitoring exporter shared by every instance in a project. It is a supplier
   * rather than a value so that no gRPC channel is built when metrics are disabled.
   */
  interface MetricExporterSupplier {
    MetricExporter get() throws IOException;
  }

  static MetricRecorder newMetricRecorder(
      boolean enabled,
      MetricExporterSupplier exporterSupplier,
      String projectId,
      String location,
      String cluster,
      String instance,
      String clientUid) {
    if (!enabled) {
      logger.debug("Disabling built-in metrics");
      return new NullMetricRecorder();
    }
    try {
      return new CloudMonitoringMetricRecorder(
          exporterSupplier.get(), projectId, location, cluster, instance, clientUid);
    } catch (Throwable t) {
      // Telemetry must never stop a connection from being established. Catch Throwable rather than
      // Exception so that a missing OpenTelemetry class (NoClassDefFoundError on a minimized or
      // shaded classpath) degrades to no metrics instead of failing the dial.
      // Log the summary at warn and keep the stack trace for debug: this is expected whenever the
      // caller's credentials lack monitoring.timeSeries.create, and it is not worth a stack trace
      // in every such application's logs.
      logger.warn(
          "Built-in metrics are disabled: the Cloud Monitoring exporter failed to initialize ({}). "
              + "Connections are unaffected.",
          t.toString());
      logger.debug("Built-in metrics exporter failed to initialize.", t);
      return new NullMetricRecorder();
    }
  }

  private MetricRecorderFactory() {}
}
