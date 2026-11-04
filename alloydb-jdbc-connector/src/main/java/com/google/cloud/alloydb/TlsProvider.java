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
   * Get post-quantum key exchange wherever it is available, without specifying how.
   *
   * <p>On JDK 27 and later this is the JRE's own provider, which negotiates ML-KEM natively; before
   * that it is Conscrypt, where its native library loads. Conscrypt is not loaded at all on a JRE
   * that needs no help, so this option stops substituting a TLS implementation on its own as
   * runtimes move to JDK 27.
   *
   * <p>Where neither can supply it -- an older JRE on a platform Conscrypt does not publish a
   * native library for -- the connection is served by the JRE's provider with a classical
   * handshake, and the connector logs that once. Use this rather than {@link #CONSCRYPT} for an
   * artifact that has to run on those platforms, where a classical handshake is preferable to a
   * failed connection.
   */
  AUTO,

  /**
   * Always use the JRE's default JSSE provider. This is the default. On JDK 27 and later it already
   * negotiates post-quantum key exchange natively; before that it does not.
   */
  JDK,

  /**
   * Require Conscrypt. Connections fail rather than silently falling back to the JRE default when
   * Conscrypt's native library cannot be loaded on this platform.
   *
   * <p>Unlike {@link #AUTO} this names an implementation rather than an outcome, so it is honored
   * on every JRE, including those that could negotiate ML-KEM themselves. That makes it the way to
   * exercise Conscrypt deliberately -- the connector's own integration tests use it -- and the way
   * to guarantee a post-quantum handshake or no connection at all.
   */
  CONSCRYPT
}
