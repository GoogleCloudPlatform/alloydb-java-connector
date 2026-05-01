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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Factory for creating MetricRecorder instances. */
class MetricRecorderFactory {

  private static final Logger logger = LoggerFactory.getLogger(MetricRecorderFactory.class);

  static MetricRecorder newMetricRecorder(
      boolean enabled,
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
          projectId, location, cluster, instance, clientUid);
    } catch (Exception e) {
      logger.debug("Built-in metrics exporter failed to initialize: {}", e.getMessage());
      return new NullMetricRecorder();
    }
  }
}
