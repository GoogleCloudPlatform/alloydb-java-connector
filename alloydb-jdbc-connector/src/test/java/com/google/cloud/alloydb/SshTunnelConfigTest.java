/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.alloydb;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import java.io.File;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SshTunnelConfigTest {

  @Rule public TemporaryFolder tempFolder = new TemporaryFolder();

  private String createTempKeyFile() throws IOException {
    File keyFile = tempFolder.newFile("id_rsa");
    return keyFile.getAbsolutePath();
  }

  @Test
  public void testBuild_success() throws IOException {
    String keyPath = createTempKeyFile();

    SshTunnelConfig config =
        new SshTunnelConfig.Builder()
            .withHost("bastion.example.com")
            .withPort(2222)
            .withUser("myuser")
            .withPrivateKeyPath(keyPath)
            .withKnownHostsPath("/some/known_hosts")
            .build();

    assertThat(config.getHost()).isEqualTo("bastion.example.com");
    assertThat(config.getPort()).isEqualTo(2222);
    assertThat(config.getUser()).isEqualTo("myuser");
    assertThat(config.getPrivateKeyPath()).isEqualTo(keyPath);
    assertThat(config.getKnownHostsPath()).isEqualTo("/some/known_hosts");
  }

  @Test
  public void testBuild_defaultPort() throws IOException {
    String keyPath = createTempKeyFile();

    SshTunnelConfig config =
        new SshTunnelConfig.Builder()
            .withHost("bastion.example.com")
            .withUser("myuser")
            .withPrivateKeyPath(keyPath)
            .build();

    assertThat(config.getPort()).isEqualTo(22);
  }

  @Test
  public void testBuild_failsWithoutHost() {
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () ->
                new SshTunnelConfig.Builder()
                    .withUser("myuser")
                    .withPrivateKeyPath("/some/key")
                    .build());
    assertThat(ex).hasMessageThat().contains("SSH host is required");
  }

  @Test
  public void testBuild_failsWithoutUser() throws IOException {
    String keyPath = createTempKeyFile();

    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () ->
                new SshTunnelConfig.Builder()
                    .withHost("bastion.example.com")
                    .withPrivateKeyPath(keyPath)
                    .build());
    assertThat(ex).hasMessageThat().contains("alloydbSshUser is required");
  }

  @Test
  public void testBuild_failsWithoutPrivateKeyPath() {
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () ->
                new SshTunnelConfig.Builder()
                    .withHost("bastion.example.com")
                    .withUser("myuser")
                    .build());
    assertThat(ex).hasMessageThat().contains("alloydbSshPrivateKeyPath is required");
  }

  @Test
  public void testBuild_failsWhenPrivateKeyFileNotReadable() {
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () ->
                new SshTunnelConfig.Builder()
                    .withHost("bastion.example.com")
                    .withUser("myuser")
                    .withPrivateKeyPath("/nonexistent/path/id_rsa")
                    .build());
    assertThat(ex).hasMessageThat().contains("does not exist or is not readable");
  }

  @Test
  public void testEquals() throws IOException {
    String keyPath = createTempKeyFile();

    SshTunnelConfig a =
        new SshTunnelConfig.Builder()
            .withHost("bastion.example.com")
            .withPort(22)
            .withUser("myuser")
            .withPrivateKeyPath(keyPath)
            .build();

    SshTunnelConfig b =
        new SshTunnelConfig.Builder()
            .withHost("bastion.example.com")
            .withPort(22)
            .withUser("myuser")
            .withPrivateKeyPath(keyPath)
            .build();

    assertThat(a).isEqualTo(b);
    assertThat(a.hashCode()).isEqualTo(b.hashCode());
  }

  @Test
  public void testNotEquals_differentHost() throws IOException {
    String keyPath = createTempKeyFile();

    SshTunnelConfig a =
        new SshTunnelConfig.Builder()
            .withHost("bastion1.example.com")
            .withUser("myuser")
            .withPrivateKeyPath(keyPath)
            .build();

    SshTunnelConfig b =
        new SshTunnelConfig.Builder()
            .withHost("bastion2.example.com")
            .withUser("myuser")
            .withPrivateKeyPath(keyPath)
            .build();

    assertThat(a).isNotEqualTo(b);
  }

  @Test
  public void testNotEquals_differentPort() throws IOException {
    String keyPath = createTempKeyFile();

    SshTunnelConfig a =
        new SshTunnelConfig.Builder()
            .withHost("bastion.example.com")
            .withPort(22)
            .withUser("myuser")
            .withPrivateKeyPath(keyPath)
            .build();

    SshTunnelConfig b =
        new SshTunnelConfig.Builder()
            .withHost("bastion.example.com")
            .withPort(2222)
            .withUser("myuser")
            .withPrivateKeyPath(keyPath)
            .build();

    assertThat(a).isNotEqualTo(b);
  }
}
