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

import java.security.Provider;
import java.security.Security;

/**
 * Records the JCA registration of a named provider so that a test can register or unregister it and
 * then leave the JVM exactly as it found it.
 *
 * <p>Provider registration is global mutable state shared by every test in the fork, and the
 * position in the provider list decides preference order for everything else in the JVM. Restoring
 * by name alone would drop a provider the environment had registered, or append one that used to
 * sit earlier in the list.
 */
final class ProviderSnapshot {

  private final String name;
  private final Provider provider;
  private final int position;

  private ProviderSnapshot(String name, Provider provider, int position) {
    this.name = name;
    this.provider = provider;
    this.position = position;
  }

  /** Records whether {@code name} is registered, and where, without changing anything. */
  static ProviderSnapshot of(String name) {
    Provider[] providers = Security.getProviders();
    for (int i = 0; i < providers.length; i++) {
      if (providers[i].getName().equals(name)) {
        return new ProviderSnapshot(name, providers[i], i + 1); // Positions are 1-based.
      }
    }
    return new ProviderSnapshot(name, null, -1);
  }

  /** Returns the provider list to the state {@link #of(String)} recorded. */
  void restore() {
    Security.removeProvider(name);
    if (provider != null) {
      // insertProviderAt rather than addProvider: addProvider appends, which would silently
      // change the provider preference order for everything else in this JVM.
      Security.insertProviderAt(provider, position);
    }
  }
}
