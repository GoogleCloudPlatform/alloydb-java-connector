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

class CredentialFactoryProvider {

  private final CredentialFactory defaultCredentialFactory;

  CredentialFactoryProvider() {
    this.defaultCredentialFactory = new DefaultCredentialFactory();
  }

  CredentialFactoryProvider(CredentialFactory defaultCredentialFactory) {
    this.defaultCredentialFactory = defaultCredentialFactory;
  }

  CredentialFactory getDefaultCredentialFactory() {
    return defaultCredentialFactory;
  }

  CredentialFactory getInstanceCredentialFactory(ConnectorConfig config) {

    CredentialFactory instanceCredentialFactory;
    if (config.getGoogleCredentialsSupplier() != null) {
      instanceCredentialFactory =
          new SupplierCredentialFactory(config.getGoogleCredentialsSupplier());
    } else if (config.getGoogleCredentials() != null) {
      instanceCredentialFactory = new ConstantCredentialFactory(config.getGoogleCredentials());
    } else if (config.getGoogleCredentialsPath() != null) {
      instanceCredentialFactory = new FileCredentialFactory(config.getGoogleCredentialsPath());
    } else {
      instanceCredentialFactory = getDefaultCredentialFactory();
    }

    // Validate targetPrincipal and delegates
    if (config.getTargetPrincipal() == null
        && config.getDelegates() != null
        && !config.getDelegates().isEmpty()) {
      throw new IllegalArgumentException(
          String.format(
              "Connection property %s must be when %s is set.",
              ConnectionConfig.ALLOYDB_TARGET_PRINCIPAL, ConnectionConfig.ALLOYDB_DELEGATES));
    }

    // If targetPrincipal and delegates are set, then
    if (config.getTargetPrincipal() != null && !config.getTargetPrincipal().isEmpty()) {
      instanceCredentialFactory =
          new ServiceAccountImpersonatingCredentialFactory(
              instanceCredentialFactory, config.getTargetPrincipal(), config.getDelegates());
    }

    return instanceCredentialFactory;
  }
}
