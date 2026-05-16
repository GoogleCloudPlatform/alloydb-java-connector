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

/** Holds metadata to attach to a metric recording. */
class TelemetryAttributes {

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

  private boolean iamAuthn;
  private boolean cacheHit;
  private String dialStatus;
  private String refreshStatus;
  private String refreshType;

  TelemetryAttributes() {
    this.iamAuthn = false;
    this.cacheHit = false;
    this.dialStatus = "";
    this.refreshStatus = "";
    this.refreshType = "";
  }

  boolean isIamAuthn() {
    return iamAuthn;
  }

  void setIamAuthn(boolean iamAuthn) {
    this.iamAuthn = iamAuthn;
  }

  boolean isCacheHit() {
    return cacheHit;
  }

  void setCacheHit(boolean cacheHit) {
    this.cacheHit = cacheHit;
  }

  String getDialStatus() {
    return dialStatus;
  }

  void setDialStatus(String dialStatus) {
    this.dialStatus = dialStatus;
  }

  String getRefreshStatus() {
    return refreshStatus;
  }

  void setRefreshStatus(String refreshStatus) {
    this.refreshStatus = refreshStatus;
  }

  String getRefreshType() {
    return refreshType;
  }

  void setRefreshType(String refreshType) {
    this.refreshType = refreshType;
  }

  static String authTypeValue(boolean iamAuthn) {
    return iamAuthn ? "iam" : "built_in";
  }
}
