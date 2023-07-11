package com.google.cloud.alloydb;

import static com.google.common.truth.Truth.assertThat;

import com.google.cloud.alloydb.v1beta.AlloyDBAdminClient;
import com.google.cloud.alloydb.v1beta.InstanceName;
import com.google.common.base.Objects;
import java.io.IOException;
import java.security.KeyPair;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ConnectorTest {

  private AlloyDBAdminClient alloydbAdminApiClient;

  @Before
  public void setUp() throws IOException {
    // Create the client once and close it later.
    alloydbAdminApiClient = AlloyDBAdminClient.create();
  }

  @After
  public void tearDown() {
    alloydbAdminApiClient.close();
  }

  @Test
  public void testEquals() {
    ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
    DefaultConnectionInfoRepository connectionInfoRepo =
        new DefaultConnectionInfoRepository(executor, alloydbAdminApiClient);
    KeyPair clientConnectorKeyPair = RsaKeyPairGenerator.generateKeyPair();
    DefaultConnectionInfoCacheFactory connectionInfoCacheFactory =
        new DefaultConnectionInfoCacheFactory();

    Connector a =
        new Connector(
            executor,
            connectionInfoRepo,
            clientConnectorKeyPair,
            connectionInfoCacheFactory,
            new ConcurrentHashMap<>());

    assertThat(a)
        .isNotEqualTo(
            new Connector(
                new ScheduledThreadPoolExecutor(1), // Different
                connectionInfoRepo,
                clientConnectorKeyPair,
                connectionInfoCacheFactory,
                new ConcurrentHashMap<>()));

    assertThat(a)
        .isNotEqualTo(
            new Connector(
                executor,
                new DefaultConnectionInfoRepository(executor, alloydbAdminApiClient), // Different
                clientConnectorKeyPair,
                connectionInfoCacheFactory,
                new ConcurrentHashMap<>()));

    assertThat(a)
        .isNotEqualTo(
            new Connector(
                executor,
                connectionInfoRepo,
                RsaKeyPairGenerator.generateKeyPair(), // Different
                connectionInfoCacheFactory,
                new ConcurrentHashMap<>()));

    assertThat(a)
        .isNotEqualTo(
            new Connector(
                executor,
                connectionInfoRepo,
                clientConnectorKeyPair,
                new DefaultConnectionInfoCacheFactory(),
                new ConcurrentHashMap<>()));
  }

  @Test
  public void testHashCode() {
    ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
    DefaultConnectionInfoRepository connectionInfoRepo =
        new DefaultConnectionInfoRepository(executor, alloydbAdminApiClient);
    KeyPair clientConnectorKeyPair = RsaKeyPairGenerator.generateKeyPair();
    DefaultConnectionInfoCacheFactory connectionInfoCacheFactory =
        new DefaultConnectionInfoCacheFactory();
    ConcurrentHashMap<InstanceName, ConnectionInfoCache> instances = new ConcurrentHashMap<>();

    Connector a =
        new Connector(
            executor,
            connectionInfoRepo,
            clientConnectorKeyPair,
            connectionInfoCacheFactory,
            instances);

    assertThat(a.hashCode())
        .isEqualTo(
            Objects.hashCode(
                executor,
                connectionInfoRepo,
                clientConnectorKeyPair,
                connectionInfoCacheFactory,
                instances
            )
        );
  }
}
