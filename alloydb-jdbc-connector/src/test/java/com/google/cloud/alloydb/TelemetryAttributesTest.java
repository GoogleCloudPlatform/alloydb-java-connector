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
  public void testDefaults() {
    TelemetryAttributes attrs = new TelemetryAttributes();
    assertThat(attrs.isIamAuthn()).isFalse();
    assertThat(attrs.isCacheHit()).isFalse();
    assertThat(attrs.getDialStatus()).isEmpty();
    assertThat(attrs.getRefreshStatus()).isEmpty();
    assertThat(attrs.getRefreshType()).isEmpty();
  }

  @Test
  public void testSettersAndGetters() {
    TelemetryAttributes attrs = new TelemetryAttributes();
    attrs.setIamAuthn(true);
    attrs.setCacheHit(true);
    attrs.setDialStatus(TelemetryAttributes.DIAL_SUCCESS);
    attrs.setRefreshStatus(TelemetryAttributes.REFRESH_SUCCESS);
    attrs.setRefreshType(TelemetryAttributes.REFRESH_AHEAD_TYPE);

    assertThat(attrs.isIamAuthn()).isTrue();
    assertThat(attrs.isCacheHit()).isTrue();
    assertThat(attrs.getDialStatus()).isEqualTo("success");
    assertThat(attrs.getRefreshStatus()).isEqualTo("success");
    assertThat(attrs.getRefreshType()).isEqualTo("refresh_ahead");
  }
}
