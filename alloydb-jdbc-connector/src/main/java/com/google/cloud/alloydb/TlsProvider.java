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
 * Selects the JSSE provider used for the mTLS connection to an AlloyDB instance.
 *
 * <p>Applications choose a value through the {@code alloydbTlsProvider} connection property rather
 * than through this type, so it stays package private.
 *
 * <p>See the Post-Quantum Cryptography Guide in docs/pqc.md for why an application might want to
 * change this.
 */
enum TlsProvider {
  /**
   * Use the Bouncy Castle JSSE provider (BCJSSE) when it is registered, and the JRE's default JSSE
   * provider otherwise. Use this when an application may or may not register BCJSSE and either
   * outcome is acceptable.
   */
  AUTO,

  /**
   * Always use the JRE's default JSSE provider, even when BCJSSE is registered. This is the
   * default, so that registering BCJSSE for some other part of an application does not move AlloyDB
   * connections onto a different TLS stack.
   */
  JDK,

  /**
   * Require the Bouncy Castle JSSE provider (BCJSSE). Connections fail rather than silently falling
   * back to the JRE default when BCJSSE is not registered or cannot provide a TLSv1.3 SSLContext.
   */
  BOUNCY_CASTLE
}
