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

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

public class TelemetryAttributesTest {

  @Test
  public void testAuthTypeValue_iam() {
    assertThat(TelemetryAttributes.authTypeValue(true)).isEqualTo("iam");
  }

  @Test
  public void testAuthTypeValue_builtIn() {
    assertThat(TelemetryAttributes.authTypeValue(false)).isEqualTo("built_in");
  }

  @Test
  public void testForDial() {
    TelemetryAttributes attrs =
        TelemetryAttributes.forDial(true, true, TelemetryAttributes.DIAL_TLS_ERROR);

    assertThat(attrs.isIamAuthn()).isTrue();
    assertThat(attrs.isCacheHit()).isTrue();
    assertThat(attrs.getDialStatus()).isEqualTo("tls_error");
    assertThat(attrs.getRefreshStatus()).isEmpty();
    assertThat(attrs.getRefreshType()).isEmpty();
  }

  @Test
  public void testForConnection() {
    TelemetryAttributes attrs = TelemetryAttributes.forConnection(false);

    assertThat(attrs.isIamAuthn()).isFalse();
    assertThat(attrs.isCacheHit()).isFalse();
    assertThat(attrs.getDialStatus()).isEmpty();
  }

  @Test
  public void testForRefresh() {
    TelemetryAttributes attrs =
        TelemetryAttributes.forRefresh(
            TelemetryAttributes.REFRESH_FAILURE, TelemetryAttributes.REFRESH_LAZY_TYPE);

    assertThat(attrs.getRefreshStatus()).isEqualTo("failure");
    assertThat(attrs.getRefreshType()).isEqualTo("lazy");
  }

  @Test
  public void testInternedRefreshConstants() {
    assertThat(TelemetryAttributes.REFRESH_AHEAD_SUCCEEDED.getRefreshStatus()).isEqualTo("success");
    assertThat(TelemetryAttributes.REFRESH_AHEAD_SUCCEEDED.getRefreshType())
        .isEqualTo("refresh_ahead");
    assertThat(TelemetryAttributes.REFRESH_AHEAD_FAILED.getRefreshStatus()).isEqualTo("failure");
    assertThat(TelemetryAttributes.REFRESH_AHEAD_FAILED.getRefreshType())
        .isEqualTo("refresh_ahead");
    assertThat(TelemetryAttributes.REFRESH_LAZY_SUCCEEDED.getRefreshStatus()).isEqualTo("success");
    assertThat(TelemetryAttributes.REFRESH_LAZY_SUCCEEDED.getRefreshType()).isEqualTo("lazy");
    assertThat(TelemetryAttributes.REFRESH_LAZY_FAILED.getRefreshStatus()).isEqualTo("failure");
    assertThat(TelemetryAttributes.REFRESH_LAZY_FAILED.getRefreshType()).isEqualTo("lazy");
  }
}
