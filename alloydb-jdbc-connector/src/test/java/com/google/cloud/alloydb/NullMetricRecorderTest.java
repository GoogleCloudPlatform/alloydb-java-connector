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

import org.junit.Test;

public class NullMetricRecorderTest {

  @Test
  public void testAllMethodsAreNoOp() {
    NullMetricRecorder recorder = new NullMetricRecorder();
    TelemetryAttributes attrs = new TelemetryAttributes();

    // These should all complete without error
    recorder.recordDialCount(attrs);
    recorder.recordDialLatency(100.0, attrs);
    recorder.recordOpenConnection(attrs);
    recorder.recordClosedConnection(attrs);
    recorder.recordRefreshCount(attrs);
    recorder.shutdown();
  }
}
