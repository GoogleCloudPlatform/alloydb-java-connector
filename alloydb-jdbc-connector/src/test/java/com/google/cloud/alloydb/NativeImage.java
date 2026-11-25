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

import static org.junit.Assume.assumeFalse;

/**
 * Skips tests that need Conscrypt when they are running inside a GraalVM native image.
 *
 * <p>Conscrypt's JNI library is extracted from its jar and loaded at runtime, which a native image
 * cannot do, so there is nothing for those tests to assert there. See docs/pqc.md.
 *
 * <p>Every Conscrypt test calls this, but only the integration tests strictly need it. The
 * scheduled {@code graalvm} job runs {@code mvnw -Pnative test}, and that profile's surefire
 * configuration -- inherited from {@code native-image-shared-config}, not declared in this repo --
 * replaces the usual pattern with an include list of {@code IT*.java} and {@code *ClientTest.java}.
 * Only the {@code IT} classes run under the native image; the plain unit tests are excluded and
 * guard themselves anyway, so that running the suite by hand on a native image reports skips rather
 * than failures.
 */
final class NativeImage {

  static final boolean ACTIVE = System.getProperty("org.graalvm.nativeimage.imagecode") != null;

  /** Skips the calling test when Conscrypt's native library cannot be loaded. */
  static void assumeConscryptIsLoadable() {
    assumeFalse("Conscrypt cannot load its native library in a native image", ACTIVE);
  }

  private NativeImage() {}
}
