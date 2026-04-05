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

import com.google.common.base.Objects;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Immutable configuration for an SSH tunnel to a bastion host. */
public class SshTunnelConfig {

  private static final int DEFAULT_SSH_PORT = 22;

  private final String host;
  private final int port;
  private final String user;
  private final String privateKeyPath;
  private final String knownHostsPath;

  private SshTunnelConfig(
      String host, int port, String user, String privateKeyPath, String knownHostsPath) {
    this.host = host;
    this.port = port;
    this.user = user;
    this.privateKeyPath = privateKeyPath;
    this.knownHostsPath = knownHostsPath;
  }

  public String getHost() {
    return host;
  }

  public int getPort() {
    return port;
  }

  public String getUser() {
    return user;
  }

  public String getPrivateKeyPath() {
    return privateKeyPath;
  }

  public String getKnownHostsPath() {
    return knownHostsPath;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof SshTunnelConfig)) {
      return false;
    }
    SshTunnelConfig that = (SshTunnelConfig) o;
    return port == that.port
        && Objects.equal(host, that.host)
        && Objects.equal(user, that.user)
        && Objects.equal(privateKeyPath, that.privateKeyPath)
        && Objects.equal(knownHostsPath, that.knownHostsPath);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(host, port, user, privateKeyPath, knownHostsPath);
  }

  /** The builder for SshTunnelConfig. */
  static class Builder {
    private String host;
    private int port;
    private String user;
    private String privateKeyPath;
    private String knownHostsPath;

    Builder withHost(String host) {
      this.host = host;
      return this;
    }

    Builder withPort(int port) {
      this.port = port;
      return this;
    }

    Builder withUser(String user) {
      this.user = user;
      return this;
    }

    Builder withPrivateKeyPath(String privateKeyPath) {
      this.privateKeyPath = privateKeyPath;
      return this;
    }

    Builder withKnownHostsPath(String knownHostsPath) {
      this.knownHostsPath = knownHostsPath;
      return this;
    }

    SshTunnelConfig build() {
      if (host == null || host.isEmpty()) {
        throw new IllegalStateException("SSH host is required");
      }
      if (user == null || user.isEmpty()) {
        throw new IllegalStateException(
            "alloydbSshUser is required when alloydbSshHost is configured");
      }
      if (privateKeyPath == null || privateKeyPath.isEmpty()) {
        throw new IllegalStateException(
            "alloydbSshPrivateKeyPath is required when alloydbSshHost is configured");
      }
      Path keyPath = Paths.get(privateKeyPath);
      if (!Files.isReadable(keyPath)) {
        throw new IllegalStateException(
            String.format(
                "SSH private key file does not exist or is not readable: %s", privateKeyPath));
      }
      int resolvedPort = port > 0 ? port : DEFAULT_SSH_PORT;
      return new SshTunnelConfig(host, resolvedPort, user, privateKeyPath, knownHostsPath);
    }
  }
}
