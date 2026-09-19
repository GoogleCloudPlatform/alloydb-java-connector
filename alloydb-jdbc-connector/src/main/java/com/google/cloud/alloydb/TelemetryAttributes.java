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

/**
 * Holds metadata to attach to a metric recording.
 *
 * <p>Instances are immutable. A single dial produces several recordings (dial count, dial latency,
 * open connection) and the resulting connection outlives the dial, so the attributes attached to a
 * recording must not be mutated after the fact.
 */
final class TelemetryAttributes {

  // Metric attribute keys.
  static final String CONNECTOR_TYPE = "connector_type";
  static final String AUTH_TYPE = "auth_type";
  static final String IS_CACHE_HIT = "is_cache_hit";
  static final String STATUS = "status";
  static final String REFRESH_TYPE = "refresh_type";

  // Dial status values.
  static final String DIAL_SUCCESS = "success";
  static final String DIAL_USER_ERROR = "user_error";
  static final String DIAL_CACHE_ERROR = "cache_error";
  static final String DIAL_TCP_ERROR = "tcp_error";
  static final String DIAL_TLS_ERROR = "tls_error";
  static final String DIAL_MDX_ERROR = "mdx_error";

  // Refresh status values.
  static final String REFRESH_SUCCESS = "success";
  static final String REFRESH_FAILURE = "failure";

  // Refresh type values.
  static final String REFRESH_AHEAD_TYPE = "refresh_ahead";
  static final String REFRESH_LAZY_TYPE = "lazy";

  // Refresh recordings only ever use one of these four combinations, so they are interned here to
  // keep the refresh path allocation free.
  static final TelemetryAttributes REFRESH_AHEAD_SUCCEEDED =
      new TelemetryAttributes(false, false, "", REFRESH_SUCCESS, REFRESH_AHEAD_TYPE);
  static final TelemetryAttributes REFRESH_AHEAD_FAILED =
      new TelemetryAttributes(false, false, "", REFRESH_FAILURE, REFRESH_AHEAD_TYPE);
  static final TelemetryAttributes REFRESH_LAZY_SUCCEEDED =
      new TelemetryAttributes(false, false, "", REFRESH_SUCCESS, REFRESH_LAZY_TYPE);
  static final TelemetryAttributes REFRESH_LAZY_FAILED =
      new TelemetryAttributes(false, false, "", REFRESH_FAILURE, REFRESH_LAZY_TYPE);

  private final boolean iamAuthn;
  private final boolean cacheHit;
  private final String dialStatus;
  private final String refreshStatus;
  private final String refreshType;

  private TelemetryAttributes(
      boolean iamAuthn,
      boolean cacheHit,
      String dialStatus,
      String refreshStatus,
      String refreshType) {
    this.iamAuthn = iamAuthn;
    this.cacheHit = cacheHit;
    this.dialStatus = dialStatus;
    this.refreshStatus = refreshStatus;
    this.refreshType = refreshType;
  }

  /** Attributes for a dial attempt that finished with the given status. */
  static TelemetryAttributes forDial(boolean iamAuthn, boolean cacheHit, String dialStatus) {
    return new TelemetryAttributes(iamAuthn, cacheHit, dialStatus, "", "");
  }

  /** Attributes for the lifetime of an established connection. */
  static TelemetryAttributes forConnection(boolean iamAuthn) {
    return new TelemetryAttributes(iamAuthn, false, "", "", "");
  }

  /** Attributes for a refresh operation of the given type that finished with the given status. */
  static TelemetryAttributes forRefresh(String refreshStatus, String refreshType) {
    return new TelemetryAttributes(false, false, "", refreshStatus, refreshType);
  }

  boolean isIamAuthn() {
    return iamAuthn;
  }

  boolean isCacheHit() {
    return cacheHit;
  }

  String getDialStatus() {
    return dialStatus;
  }

  String getRefreshStatus() {
    return refreshStatus;
  }

  String getRefreshType() {
    return refreshType;
  }

  static String authTypeValue(boolean iamAuthn) {
    return iamAuthn ? "iam" : "built_in";
  }
}
