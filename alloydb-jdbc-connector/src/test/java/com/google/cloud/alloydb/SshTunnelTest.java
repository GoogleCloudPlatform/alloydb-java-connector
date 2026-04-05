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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SshTunnelTest {

  @Rule public TemporaryFolder tempFolder = new TemporaryFolder();

  private SshServer sshServer;
  private File privateKeyFile;
  private File knownHostsFile;
  private ServerSocket echoServer;

  private static void writeKnownHosts(File file, SshServer server)
      throws IOException, GeneralSecurityException {
    Iterable<KeyPair> hostKeys = server.getKeyPairProvider().loadKeys(null);
    KeyPair hostKeyPair = hostKeys.iterator().next();
    PublicKey hostPublicKey = hostKeyPair.getPublic();

    StringBuilder keyPart = new StringBuilder();
    PublicKeyEntry.appendPublicKeyEntry(keyPart, hostPublicKey);
    int port = server.getPort();

    String entry;
    if (port == 22) {
      entry = String.format("127.0.0.1 %s\n", keyPart);
    } else {
      entry = String.format("[127.0.0.1]:%d %s\n", port, keyPart);
    }

    Files.write(file.toPath(), entry.getBytes(StandardCharsets.UTF_8));
  }

  @Before
  public void setUp() throws Exception {
    // Generate client key pair
    KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
    keyGen.initialize(2048);
    KeyPair clientKeyPair = keyGen.generateKeyPair();

    // Write private key in OpenSSH format
    privateKeyFile = tempFolder.newFile("id_rsa");
    try (FileOutputStream fos = new FileOutputStream(privateKeyFile)) {
      OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(clientKeyPair, "test key", null, fos);
    }

    // Start embedded SSH server
    sshServer = SshServer.setUpDefaultServer();
    sshServer.setHost("127.0.0.1");
    sshServer.setPort(0); // random port

    File hostKeyFile = tempFolder.newFile("host_key");
    //noinspection ResultOfMethodCallIgnored
    hostKeyFile.delete(); // SimpleGeneratorHostKeyProvider creates it
    sshServer.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKeyFile.toPath()));

    // Accept the client's public key
    PublicKey expectedKey = clientKeyPair.getPublic();
    sshServer.setPublickeyAuthenticator(
        (username, key, session) -> KeyUtils.compareKeys(expectedKey, key));

    // Allow port forwarding
    sshServer.setForwardingFilter(AcceptAllForwardingFilter.INSTANCE);
    sshServer.start();

    // Write known_hosts with the server's host key
    knownHostsFile = tempFolder.newFile("known_hosts");
    writeKnownHosts(knownHostsFile, sshServer);

    // Start a simple echo server as the "remote" target
    echoServer = new ServerSocket(0);
    Thread echoThread =
        new Thread(
            () -> {
              while (!echoServer.isClosed()) {
                try {
                  Socket sock = echoServer.accept();
                  Thread handler =
                      new Thread(
                          () -> {
                            try {
                              InputStream in = sock.getInputStream();
                              OutputStream out = sock.getOutputStream();
                              int b;
                              while ((b = in.read()) != -1) {
                                out.write(b);
                                out.flush();
                              }
                              sock.close();
                            } catch (IOException e) {
                              // Connection closed
                            }
                          });
                  handler.setDaemon(true);
                  handler.start();
                } catch (IOException e) {
                  // Server closed
                }
              }
            });
    echoThread.setDaemon(true);
    echoThread.start();
  }

  @After
  public void tearDown() throws Exception {
    if (echoServer != null && !echoServer.isClosed()) {
      echoServer.close();
    }
    if (sshServer != null && sshServer.isOpen()) {
      sshServer.stop();
    }
  }

  private SshTunnelConfig buildConfig(String knownHostsPath) {
    return new SshTunnelConfig.Builder()
        .withHost("127.0.0.1")
        .withPort(sshServer.getPort())
        .withUser("testuser")
        .withPrivateKeyPath(privateKeyFile.getAbsolutePath())
        .withKnownHostsPath(knownHostsPath)
        .build();
  }

  @Test
  public void testConfigureHostKeyVerification_withExplicitKnownHosts() throws IOException {
    File knownHosts = tempFolder.newFile("explicit_known_hosts");
    SshTunnelConfig config = buildConfig(knownHosts.getAbsolutePath());

    SshClient client = SshClient.setUpDefaultClient();

    SshTunnel.configureHostKeyVerification(client, config);

    assertThat(client.getServerKeyVerifier()).isNotNull();
  }

  @Test
  public void testConfigureHostKeyVerification_withoutKnownHostsThrows() throws IOException {
    String originalHome = System.getProperty("user.home");
    System.setProperty("user.home", tempFolder.newFolder("empty_home").getAbsolutePath());
    SshTunnelConfig config = buildConfig(null);
    try (SshClient client = SshClient.setUpDefaultClient()) {
      IllegalStateException ex =
          assertThrows(
              IllegalStateException.class,
              () -> SshTunnel.configureHostKeyVerification(client, config));

      assertThat(ex.getMessage()).contains("No SSH known hosts file found");
    } finally {
      System.setProperty("user.home", originalHome);
    }
  }

  @Test
  public void testConfigureHostKeyVerification_usesDefaultKnownHosts() throws IOException {
    String originalHome = System.getProperty("user.home");
    try {
      File fakeHome = tempFolder.newFolder("fake_home");
      File sshDir = new File(fakeHome, ".ssh");
      //noinspection ResultOfMethodCallIgnored
      sshDir.mkdirs();
      //noinspection ResultOfMethodCallIgnored
      new File(sshDir, "known_hosts").createNewFile();

      System.setProperty("user.home", fakeHome.getAbsolutePath());
      SshTunnelConfig config = buildConfig(null);
      SshClient client = SshClient.setUpDefaultClient();

      SshTunnel.configureHostKeyVerification(client, config);

      assertThat(client.getServerKeyVerifier()).isNotNull();
    } finally {
      System.setProperty("user.home", originalHome);
    }
  }

  @Test
  public void testOpenAndClose() throws Exception {
    SshTunnelConfig config = buildConfig(knownHostsFile.getAbsolutePath());

    SshTunnel tunnel = SshTunnel.open(config);
    assertThat(tunnel).isNotNull();

    tunnel.close();
  }

  @Test
  public void testCreateSocket_connectsThroughTunnel() throws Exception {
    SshTunnelConfig config = buildConfig(knownHostsFile.getAbsolutePath());

    try (SshTunnel tunnel = SshTunnel.open(config)) {
      Socket socket = tunnel.createSocket("127.0.0.1", echoServer.getLocalPort());

      socket.getOutputStream().write(42);
      socket.getOutputStream().flush();
      int response = socket.getInputStream().read();
      assertThat(response).isEqualTo(42);

      socket.close();
    }
  }

  @Test
  public void testCreateSocket_cachesPortForwards() throws Exception {
    SshTunnelConfig config = buildConfig(knownHostsFile.getAbsolutePath());

    try (SshTunnel tunnel = SshTunnel.open(config)) {
      Socket socket1 = tunnel.createSocket("127.0.0.1", echoServer.getLocalPort());
      Socket socket2 = tunnel.createSocket("127.0.0.1", echoServer.getLocalPort());

      socket1.getOutputStream().write(1);
      socket1.getOutputStream().flush();
      assertThat(socket1.getInputStream().read()).isEqualTo(1);

      socket2.getOutputStream().write(2);
      socket2.getOutputStream().flush();
      assertThat(socket2.getInputStream().read()).isEqualTo(2);

      // Cached port forward means same local port
      assertThat(socket1.getPort()).isEqualTo(socket2.getPort());

      socket1.close();
      socket2.close();
    }
  }

  @Test
  public void testCreateSocket_afterCloseThrows() throws Exception {
    SshTunnelConfig config = buildConfig(knownHostsFile.getAbsolutePath());

    SshTunnel tunnel = SshTunnel.open(config);
    tunnel.close();

    assertThrows(
        IOException.class, () -> tunnel.createSocket("127.0.0.1", echoServer.getLocalPort()));
  }

  @Test
  public void testOpen_wrapsIOExceptionOnConnectionFailure() throws Exception {
    // Use a port that's been closed so the connection is refused
    ServerSocket temp = new ServerSocket(0);
    int port = temp.getLocalPort();
    temp.close();

    SshTunnelConfig config =
        new SshTunnelConfig.Builder()
            .withHost("127.0.0.1")
            .withPort(port)
            .withUser("testuser")
            .withPrivateKeyPath(privateKeyFile.getAbsolutePath())
            .withKnownHostsPath(knownHostsFile.getAbsolutePath())
            .build();

    RuntimeException ex = assertThrows(RuntimeException.class, () -> SshTunnel.open(config));
    assertThat(ex.getMessage()).contains("Failed to open SSH tunnel");
  }

  @Test
  public void testCreateSocket_retriesAfterStalePortForward() throws Exception {
    SshTunnelConfig config = buildConfig(knownHostsFile.getAbsolutePath());

    try (SshTunnel tunnel = SshTunnel.open(config)) {
      // Warm the port forward cache with a real connection.
      Socket first = tunnel.createSocket("127.0.0.1", echoServer.getLocalPort());
      first.close();

      // Replace the cached port forward with one pointing at a closed port, simulating a stale
      // listener.
      Field pfField = SshTunnel.class.getDeclaredField("portForwards");
      pfField.setAccessible(true);
      @SuppressWarnings("unchecked")
      ConcurrentHashMap<String, SshdSocketAddress> portForwards =
          (ConcurrentHashMap<String, SshdSocketAddress>) pfField.get(tunnel);
      String key = "127.0.0.1:" + echoServer.getLocalPort();
      ServerSocket temp = new ServerSocket(0);
      int deadPort = temp.getLocalPort();
      temp.close();
      portForwards.put(key, new SshdSocketAddress("127.0.0.1", deadPort));

      // createSocket should detect the stale forward, recreate it, and succeed.
      Socket retried = tunnel.createSocket("127.0.0.1", echoServer.getLocalPort());

      retried.getOutputStream().write(99);
      retried.getOutputStream().flush();
      int response = retried.getInputStream().read();
      assertThat(response).isEqualTo(99);

      retried.close();
    }
  }

  @Test
  public void testClose_isIdempotent() throws Exception {
    SshTunnelConfig config = buildConfig(knownHostsFile.getAbsolutePath());

    SshTunnel tunnel = SshTunnel.open(config);

    tunnel.close();
    tunnel.close();
  }
}
