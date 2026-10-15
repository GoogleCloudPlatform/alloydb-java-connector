/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.alloydb;

import org.junit.Assume;

/**
 * Skips integration tests that need VPC access to the AlloyDB instance (private IP, PSC, or a
 * direct connection) when the {@code skipPrivateIp} system property is {@code true}.
 */
final class PrivateIpAssumptions {

  static final String SKIP_PRIVATE_IP_PROPERTY = "skipPrivateIp";

  private PrivateIpAssumptions() {}

  /** Call at the start of {@code @Before}, before any connection resources are created. */
  static void assumePrivateIpAvailable() {
    Assume.assumeFalse(
        "Skipped: -D" + SKIP_PRIVATE_IP_PROPERTY + "=true (no VPC access)",
        Boolean.getBoolean(SKIP_PRIVATE_IP_PROPERTY));
  }
}
