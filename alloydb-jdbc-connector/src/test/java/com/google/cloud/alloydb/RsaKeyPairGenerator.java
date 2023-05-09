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

package com.google.cloud.alloydb;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;

public class RsaKeyPairGenerator {
  public static final KeyPair TEST_KEY_PAIR = generateKeyPair();

  public static KeyPair generateKeyPair() {
    KeyPairGenerator generator;
    try {
      generator = java.security.KeyPairGenerator.getInstance("RSA");
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException("Missing RSA generator");
    }
    generator.initialize(2048);

    return generator.generateKeyPair();
  }
}
