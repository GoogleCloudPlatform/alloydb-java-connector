/*
 * Copyright 2023 Google LLC
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
package com.google.cloud.alloydb.nativeimage;

import com.google.api.gax.nativeimage.NativeImageUtils;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

/**
 * Registers GraalVM configuration for the AlloyDB libraries.
 *
 * <p>This class is only used when this library is used in <a
 * href="https://www.graalvm.org/22.0/reference-manual/native-image/">GraalVM native image</a>
 * compilation.
 */
class AlloyDBFeature implements Feature {

  private static final String ALLOYDB_SOCKET_CLASS = "com.google.cloud.alloydb.SocketFactory";

  @Override
  public void beforeAnalysis(BeforeAnalysisAccess access) {
    if (access.findClassByName(ALLOYDB_SOCKET_CLASS) == null) {
      return;
    }

    // Register AlloyDB Socket.
    NativeImageUtils.registerClassForReflection(access, ALLOYDB_SOCKET_CLASS);

    // Register PostgreSQL driver config.
    NativeImageUtils.registerClassForReflection(access, "org.postgresql.PGProperty");

    // Register Hikari configs if used with AlloyDB.
    if (access.findClassByName("com.zaxxer.hikari.HikariConfig") != null) {
      NativeImageUtils.registerClassForReflection(access, "com.zaxxer.hikari.HikariConfig");

      RuntimeReflection.register(
          access.findClassByName("[Lcom.zaxxer.hikari.util.ConcurrentBag$IConcurrentBagEntry;"));

      RuntimeReflection.register(access.findClassByName("[Ljava.sql.Statement;"));
    }
  }
}
