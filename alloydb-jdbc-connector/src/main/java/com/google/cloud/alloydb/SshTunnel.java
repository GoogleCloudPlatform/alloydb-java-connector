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

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.KnownHostsServerKeyVerifier;
import org.apache.sshd.client.keyverifier.RejectAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Manages an SSH tunnel session and local port forwards for reaching private AlloyDB instances. */
class SshTunnel implements Closeable {

  private static final Logger logger = LoggerFactory.getLogger(SshTunnel.class);
  private static final long CONNECT_TIMEOUT_MS = 30000;
  private static final long AUTH_TIMEOUT_MS = 30000;

  private final SshClient client;
  private final ClientSession session;
  private final ConcurrentHashMap<String, SshdSocketAddress> portForwards;

  private SshTunnel(SshClient client, ClientSession session) {
    this.client = client;
    this.session = session;
    this.portForwards = new ConcurrentHashMap<>();
  }

  /**
   * Opens an SSH tunnel using the given {@link SshTunnelConfig}.
   *
   * @param config the SSH tunnel configuration
   * @return an open SSH tunnel
   * @throws RuntimeException if the SSH connection cannot be established or if Apache MINA SSHD is
   *     not on the classpath
   */
  static SshTunnel open(SshTunnelConfig config) {
    try {
      return doOpen(config);
    } catch (NoClassDefFoundError e) {
      throw new RuntimeException(
          "SSH tunnel is configured but Apache MINA SSHD is not on the classpath. "
              + "Add org.apache.sshd:sshd-core to your dependencies.",
          e);
    } catch (IOException e) {
      throw new RuntimeException(
          String.format("Failed to open SSH tunnel to %s:%d", config.getHost(), config.getPort()),
          e);
    }
  }

  private static SshTunnel doOpen(SshTunnelConfig config) throws IOException {
    SshClient client = SshClient.setUpDefaultClient();
    ClientSession session = null;
    try {
      client.setKeyIdentityProvider(new FileKeyPairProvider(Paths.get(config.getPrivateKeyPath())));
      configureHostKeyVerification(client, config);
      client.start();

      logger.debug(
          String.format(
              "Opening SSH tunnel to %s:%d as user %s",
              config.getHost(), config.getPort(), config.getUser()));

      session =
          client
              .connect(config.getUser(), config.getHost(), config.getPort())
              .verify(CONNECT_TIMEOUT_MS)
              .getSession();

      session.auth().verify(AUTH_TIMEOUT_MS);

      logger.info(
          String.format("SSH tunnel established to %s:%d", config.getHost(), config.getPort()));

      return new SshTunnel(client, session);
    } catch (Exception e) {
      if (session != null) {
        try {
          session.close();
        } catch (Exception suppressed) {
          e.addSuppressed(suppressed);
        }
      }
      try {
        client.close();
      } catch (Exception suppressed) {
        e.addSuppressed(suppressed);
      }
      if (e instanceof IOException) {
        throw (IOException) e;
      }
      throw (RuntimeException) e;
    }
  }

  static void configureHostKeyVerification(SshClient client, SshTunnelConfig config) {
    if (config.getKnownHostsPath() != null) {
      client.setServerKeyVerifier(
          new KnownHostsServerKeyVerifier(
              RejectAllServerKeyVerifier.INSTANCE, Paths.get(config.getKnownHostsPath())));
      return;
    }

    Path defaultKnownHosts = Paths.get(System.getProperty("user.home"), ".ssh", "known_hosts");
    if (Files.exists(defaultKnownHosts)) {
      client.setServerKeyVerifier(
          new KnownHostsServerKeyVerifier(RejectAllServerKeyVerifier.INSTANCE, defaultKnownHosts));
      return;
    }

    throw new IllegalStateException(
        "No SSH known hosts file found. "
            + "Configure alloydbSshKnownHostsPath or create ~/.ssh/known_hosts. "
            + "To populate it, run: ssh-keyscan <BASTION_IP> >> ~/.ssh/known_hosts");
  }

  /**
   * Creates a plain socket connected to the given remote host and port through the SSH tunnel. The
   * tunnel creates a local port forward on demand and caches it for subsequent connections to the
   * same remote address.
   *
   * @param remoteHost the remote host to connect to through the tunnel
   * @param remotePort the remote port to connect to through the tunnel
   * @return a connected socket
   * @throws IOException if the connection fails or the SSH session is closed
   */
  Socket createSocket(String remoteHost, int remotePort) throws IOException {
    checkSessionOpen();

    String key = remoteHost + ":" + remotePort;
    SshdSocketAddress localAddress = getOrCreatePortForward(key, remoteHost, remotePort);

    Socket socket = new Socket();
    try {
      socket.connect(
          new InetSocketAddress(InetAddress.getLoopbackAddress(), localAddress.getPort()));
    } catch (IOException e) {
      socket.close();

      // Check if the session died — give a clear error instead of retrying on a dead tunnel.
      checkSessionOpen();

      // The cached port forward may be stale (e.g., the listener died). Recreate it and retry
      // once. Wrap the entire retry in a try-catch so that if the session dies between the
      // checkSessionOpen above and the retry, this method will still surface a clear error.
      try {
        localAddress = recreatePortForward(key, remoteHost, remotePort);
      } catch (IOException recreateException) {
        recreateException.addSuppressed(e);
        // Give a clear message if the session died during the recreate attempt.
        checkSessionOpen();
        throw recreateException;
      }
      socket = new Socket();
      try {
        socket.connect(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), localAddress.getPort()));
      } catch (IOException retryException) {
        socket.close();
        retryException.addSuppressed(e);
        throw retryException;
      }
    }
    return socket;
  }

  private void checkSessionOpen() throws IOException {
    if (!session.isOpen()) {
      throw new IOException(
          "SSH tunnel session is closed. Create a new connector to re-establish the tunnel.");
    }
  }

  private SshdSocketAddress getOrCreatePortForward(String key, String remoteHost, int remotePort)
      throws IOException {
    return unwrapPortForwardException(
        () ->
            portForwards.computeIfAbsent(
                key,
                k -> {
                  try {
                    return startPortForward(remoteHost, remotePort);
                  } catch (IOException e) {
                    throw new RuntimeException(e);
                  }
                }));
  }

  private SshdSocketAddress recreatePortForward(String key, String remoteHost, int remotePort)
      throws IOException {
    // Use compute to atomically replace the entry, avoiding races with concurrent callers.
    return unwrapPortForwardException(
        () ->
            portForwards.compute(
                key,
                (k, existing) -> {
                  if (existing != null) {
                    try {
                      session.stopLocalPortForwarding(existing);
                    } catch (Exception e) {
                      logger.warn("Error stopping stale port forward", e);
                    }
                  }
                  try {
                    return startPortForward(remoteHost, remotePort);
                  } catch (IOException e) {
                    throw new RuntimeException(e);
                  }
                }));
  }

  /**
   * ConcurrentHashMap lambdas cannot throw checked exceptions, so startPortForward wraps
   * IOException in RuntimeException. This method unwraps it and provides a clear session-dead error
   * if applicable.
   */
  private SshdSocketAddress unwrapPortForwardException(Supplier<SshdSocketAddress> operation)
      throws IOException {
    try {
      return operation.get();
    } catch (RuntimeException e) {
      if (e.getCause() instanceof IOException) {
        IOException cause = (IOException) e.getCause();
        try {
          checkSessionOpen();
        } catch (IOException sessionException) {
          sessionException.addSuppressed(cause);
          throw sessionException;
        }
        throw cause;
      }
      throw e;
    }
  }

  private SshdSocketAddress startPortForward(String remoteHost, int remotePort) throws IOException {
    SshdSocketAddress local = new SshdSocketAddress("127.0.0.1", 0);
    SshdSocketAddress remote = new SshdSocketAddress(remoteHost, remotePort);
    SshdSocketAddress bound = session.startLocalPortForwarding(local, remote);
    logger.debug(
        String.format(
            "SSH port forward created: 127.0.0.1:%d -> %s:%d",
            bound.getPort(), remoteHost, remotePort));
    return bound;
  }

  /** Returns true if the underlying SSH session is still open. */
  boolean isSessionOpen() {
    return session.isOpen();
  }

  @Override
  public void close() throws IOException {
    logger.debug("Closing SSH tunnel.");
    if (session.isOpen()) {
      for (SshdSocketAddress localAddress : portForwards.values()) {
        try {
          session.stopLocalPortForwarding(localAddress);
        } catch (Exception e) {
          logger.warn(String.format("Error stopping port forward: %s", e.getMessage()), e);
        }
      }
    }
    portForwards.clear();

    try {
      if (session.isOpen()) {
        session.close();
      }
    } catch (Exception e) {
      logger.warn(String.format("Error closing SSH session: %s", e.getMessage()), e);
    }

    try {
      client.close();
    } catch (Exception e) {
      logger.warn(String.format("Error closing SSH client: %s", e.getMessage()), e);
    }
  }
}
