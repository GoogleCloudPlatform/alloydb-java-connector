/*
 * Copyright 2023 Google LLC
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

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

@SuppressWarnings("ReferenceEquality")
public class RefresherTest {

  public static final long TEST_TIMEOUT_MS = 3000;

  private final AsyncRateLimiter rateLimiter = new AsyncRateLimiter(10);

  private ListeningScheduledExecutorService executorService;

  @Before
  public void before() throws Exception {
    executorService = newTestExecutor();
  }

  @After
  public void after() {
    executorService.shutdown();
    executorService = null;
  }

  @Test
  public void testDataRetrievedSuccessfully() {
    ExampleData data = new ExampleData(Instant.now().plus(1, ChronoUnit.HOURS));
    Refresher r =
        new Refresher(
            "RefresherTest.testDataRetrievedSuccessfully",
            executorService,
            () -> Futures.immediateFuture(data),
            rateLimiter);
    ConnectionInfo gotInfo = r.getConnectionInfo(TEST_TIMEOUT_MS);
    assertThat(gotInfo).isSameInstanceAs(data);
  }

  @Test
  public void testRateLimiterInUse() {
    ExampleData data = new ExampleData(Instant.now().plus(1, ChronoUnit.HOURS));
    SpyRateLimiter rl = new SpyRateLimiter(10);
    Refresher r =
        new Refresher(
            "RefresherTest.testRateLimiterInUse",
            executorService,
            () -> Futures.immediateFuture(data),
            rl);
    ConnectionInfo gotInfo = r.getConnectionInfo(TEST_TIMEOUT_MS);
    assertThat(gotInfo).isSameInstanceAs(data);
    assertThat(rl.counter).isNotEqualTo(0);
  }

  @Test
  public void testInstanceFailsOnConnectionError() {
    Refresher r =
        new Refresher(
            "RefresherTest.testInstanceFailsOnConnectionError",
            executorService,
            () -> Futures.immediateFailedFuture(new RuntimeException("always fails")),
            rateLimiter);
    RuntimeException ex =
        assertThrows(RuntimeException.class, () -> r.getConnectionInfo(TEST_TIMEOUT_MS));
    assertThat(ex).hasMessageThat().contains("always fails");
  }

  @Test
  public void testInstanceFailsOnTooLongToRetrieve() {
    PauseCondition cond = new PauseCondition();
    ExampleData data = new ExampleData(Instant.now().plus(1, ChronoUnit.HOURS));
    Refresher r =
        new Refresher(
            "RefresherTest.testInstanceFailsOnTooLongToRetrieve",
            executorService,
            () -> {
              cond.pause();
              return Futures.immediateFuture(data);
            },
            rateLimiter);
    try {
      RuntimeException ex =
          assertThrows(RuntimeException.class, () -> r.getConnectionInfo(TEST_TIMEOUT_MS));
      assertThat(ex).hasMessageThat().contains("No refresh has completed");
    } finally {
      r.close();
    }
  }

  @Test
  public void testForcesRefresh() throws Exception {
    ExampleData data = new ExampleData(Instant.now().plus(1, ChronoUnit.HOURS));
    AtomicInteger refreshCount = new AtomicInteger();
    final PauseCondition cond = new PauseCondition();
    Refresher r =
        new Refresher(
            "RefresherTest.testForcesRefresh",
            executorService,
            () -> {
              int c = refreshCount.get();
              // Allow the first execution to complete immediately.
              // The second execution should pause until signaled.
              if (c == 1) {
                cond.pause();
              }
              refreshCount.incrementAndGet();
              return Futures.immediateFuture(data);
            },
            rateLimiter);
    try {
      r.getConnectionInfo(TEST_TIMEOUT_MS);
      assertThat(refreshCount.get()).isEqualTo(1);

      // Force refresh, which will start, but not finish the refresh process.
      r.forceRefresh();

      // Then immediately getSslData() and assert that the refresh count has not changed.
      // Refresh count hasn't changed because we re-use the existing connection info.
      r.getConnectionInfo(TEST_TIMEOUT_MS);
      assertThat(refreshCount.get()).isEqualTo(1);

      // Allow the second refresh operation to complete
      cond.proceed();
      cond.waitForPauseToEnd(1000L);
      cond.waitForCondition(() -> refreshCount.get() >= 2, 1000L);

      // getSslData again, and assert the refresh operation completed.
      r.getConnectionInfo(TEST_TIMEOUT_MS);
      assertThat(refreshCount.get()).isEqualTo(2);
    } finally {
      r.close();
    }
  }

  @Test
  public void testRetriesOnInitialFailures() throws Exception {
    ExampleData data = new ExampleData(Instant.now().plus(1, ChronoUnit.HOURS));

    AtomicInteger refreshCount = new AtomicInteger();

    Refresher r =
        new Refresher(
            "RefresherTest.testRetriesOnInitialFailures",
            executorService,
            () -> {
              int c = refreshCount.get();
              refreshCount.incrementAndGet();
              if (c == 0) {
                throw new RuntimeException("bad request 0");
              }
              return Futures.immediateFuture(data);
            },
            rateLimiter);

    // Get the first data that is about to expire
    long until = System.currentTimeMillis() + 3000;
    while (r.getConnectionInfo(TEST_TIMEOUT_MS) != data && System.currentTimeMillis() < until) {
      Thread.sleep(100);
    }
    try {
      assertThat(refreshCount.get()).isEqualTo(2);
      assertThat(r.getConnectionInfo(TEST_TIMEOUT_MS)).isEqualTo(data);
    } finally {
      r.close();
    }
  }

  @Test
  public void testRefreshesExpiredData() throws Exception {
    ExampleData initialData = new ExampleData(Instant.now().plus(2, ChronoUnit.SECONDS));
    ExampleData data = new ExampleData(Instant.now().plus(1, ChronoUnit.HOURS));

    AtomicInteger refreshCount = new AtomicInteger();
    final PauseCondition refresh1 = new PauseCondition();

    Refresher r =
        new Refresher(
            "RefresherTest.testRefreshesExpiredData",
            executorService,
            () -> {
              int c = refreshCount.get();
              ExampleData refreshResult = data;
              switch (c) {
                case 0:
                  // refresh 0 should return initialData immediately
                  refreshResult = initialData;
                  break;
                case 1:
                  // refresh 1 should pause
                  refresh1.pause();
                  break;
              }
              // refresh 2 and on should return data immediately
              refreshCount.incrementAndGet();
              return Futures.immediateFuture(refreshResult);
            },
            rateLimiter);

    // Get the first data that is about to expire
    ConnectionInfo d = r.getConnectionInfo(TEST_TIMEOUT_MS);
    try {
      assertThat(refreshCount.get()).isEqualTo(1);
      assertThat(d).isSameInstanceAs(r.getConnectionInfo(TEST_TIMEOUT_MS));

      // Wait for the instance to expire
      while (Instant.now().isBefore(initialData.getExpiration())) {
        Thread.sleep(10);
      }

      // Allow the second refresh operation to complete
      refresh1.proceed();
      refresh1.waitForPauseToEnd(1000L);

      // getSslData again, and assert the refresh operation completed.
      refresh1.waitForCondition(() -> r.getConnectionInfo(TEST_TIMEOUT_MS) == data, 1000L);
    } finally {
      r.close();
    }
  }

  @Test
  public void testThatForceRefreshBalksWhenAScheduledRefreshIsInProgress() throws Exception {
    // Set expiration 1 minute in the future, so that it will trigger a scheduled refresh
    // and won't expire during this testcase.
    ExampleData expiresInOneMinute = new ExampleData(Instant.now().plus(1, ChronoUnit.MINUTES));

    // Set the next refresh data expiration 1 hour in the future.
    ExampleData data = new ExampleData(Instant.now().plus(1, ChronoUnit.HOURS));

    AtomicInteger refreshCount = new AtomicInteger();
    final PauseCondition refresh0 = new PauseCondition();
    final PauseCondition refresh1 = new PauseCondition();

    Refresher r =
        new Refresher(
            "RefresherTest.testThatForceRefreshBalksWhenAScheduledRefreshIsInProgress",
            executorService,
            () -> {
              int c = refreshCount.get();
              ExampleData refreshResult = data;
              switch (c) {
                case 0:
                  refresh0.pause();
                  refreshResult = expiresInOneMinute;
                  break;
                case 1:
                  refresh1.pause();
                  break;
              }
              refreshCount.incrementAndGet();
              return Futures.immediateFuture(refreshResult);
            },
            rateLimiter);

    refresh0.proceed();
    refresh0.waitForPauseToEnd(1000);
    refresh0.waitForCondition(() -> refreshCount.get() > 0, 1000);
    try {
      // Get the first data that is about to expire
      assertThat(refreshCount.get()).isEqualTo(1);
      ConnectionInfo d = r.getConnectionInfo(TEST_TIMEOUT_MS);
      assertThat(d).isSameInstanceAs(expiresInOneMinute);

      // Because the data is about to expire, scheduled refresh will begin immediately.
      // Wait until refresh is in progress.
      refresh1.waitForPauseToStart(1000);

      // Then call forceRefresh(), which should balk because a refresh attempt is in progress.
      r.forceRefresh();

      // Finally, allow the scheduled refresh operation to complete
      refresh1.proceed();
      refresh1.waitForPauseToEnd(5000);
      refresh1.waitForCondition(() -> refreshCount.get() > 1, 1000);

      // Now that the ConnectionInfo has expired, this getSslData should pause until new data
      // has been retrieved.

      // getSslData again, and assert the refresh operation completed.
      refresh1.waitForCondition(() -> r.getConnectionInfo(TEST_TIMEOUT_MS) == data, 1000L);
      assertThat(refreshCount.get()).isEqualTo(2);
    } finally {
      r.close();
    }
  }

  @Test
  public void testThatForceRefreshBalksWhenAForceRefreshIsInProgress() throws Exception {
    ExampleData initialData = new ExampleData(Instant.now().plus(1, ChronoUnit.HOURS));
    ExampleData data = new ExampleData(Instant.now().plus(1, ChronoUnit.HOURS));

    AtomicInteger refreshCount = new AtomicInteger();
    final PauseCondition refresh1 = new PauseCondition();

    Refresher r =
        new Refresher(
            "RefresherTest.testThatForceRefreshBalksWhenAForceRefreshIsInProgress",
            executorService,
            () -> {
              int c = refreshCount.get();
              switch (c) {
                case 0:
                  refreshCount.incrementAndGet();
                  return Futures.immediateFuture(initialData);
                case 1:
                  refresh1.pause();
                  refreshCount.incrementAndGet();
                  return Futures.immediateFuture(data);
                default:
                  return Futures.immediateFuture(data);
              }
            },
            rateLimiter);

    // Get the first data that is about to expire
    ConnectionInfo d = r.getConnectionInfo(TEST_TIMEOUT_MS);
    try {
      assertThat(refreshCount.get()).isEqualTo(1);
      assertThat(d).isSameInstanceAs(initialData);

      // call forceRefresh twice, this should only result in 1 refresh fetch
      r.forceRefresh();
      r.forceRefresh();

      // Allow the refresh operation to complete
      refresh1.proceed();

      // Now that the ConnectionInfo has expired, this getSslData should pause until new data
      // has been retrieved.
      refresh1.waitForPauseToEnd(1000);
      refresh1.waitForCondition(() -> refreshCount.get() >= 2, 1000);

      // assert the refresh operation completed exactly once after
      // forceRefresh was called multiple times.
      refresh1.waitForCondition(() -> r.getConnectionInfo(TEST_TIMEOUT_MS) == data, 1000L);
      assertThat(refreshCount.get()).isEqualTo(2);
    } finally {
      r.close();
    }
  }

  @Test
  public void testRefreshRetriesOnAfterFailedAttempts() throws Exception {
    ExampleData aboutToExpireData = new ExampleData(Instant.now().plus(10, ChronoUnit.MILLIS));
    ExampleData data = new ExampleData(Instant.now().plus(1, ChronoUnit.HOURS));

    AtomicInteger refreshCount = new AtomicInteger();
    final PauseCondition badRequest1 = new PauseCondition();
    final PauseCondition badRequest2 = new PauseCondition();
    final PauseCondition goodRequest = new PauseCondition();

    Refresher r =
        new Refresher(
            "RefresherTest.testRefreshRetriesOnAfterFailedAttempts",
            executorService,
            () -> {
              int c = refreshCount.get();
              switch (c) {
                case 0:
                  refreshCount.incrementAndGet();
                  return Futures.immediateFuture(aboutToExpireData);
                case 1:
                  badRequest1.pause();
                  refreshCount.incrementAndGet();
                  throw new RuntimeException("bad request 1");
                case 2:
                  badRequest2.pause();
                  refreshCount.incrementAndGet();
                  throw new RuntimeException("bad request 2");
                default:
                  goodRequest.pause();
                  refreshCount.incrementAndGet();
                  return Futures.immediateFuture(data);
              }
            },
            rateLimiter);

    // Get the first data that is about to expire
    ConnectionInfo d = r.getConnectionInfo(TEST_TIMEOUT_MS);
    try {
      assertThat(refreshCount.get()).isEqualTo(1);
      assertThat(d).isSameInstanceAs(aboutToExpireData);

      // Don't force a refresh, this should automatically schedule a refresh right away because
      // the token returned in the first request had less than 4 minutes before it expired.

      // Wait for the current ConnectionInfo to actually expire.
      while (Instant.now().isBefore(aboutToExpireData.getExpiration())) {
        Thread.sleep(10);
      }

      // Orchestrate the failed attempts

      // Allow the second refresh operation to complete
      badRequest1.proceed();
      badRequest1.waitForPauseToEnd(5000);
      badRequest1.waitForCondition(() -> refreshCount.get() == 2, 2000);

      // Allow the second bad request completes
      badRequest2.proceed();
      badRequest2.waitForCondition(() -> refreshCount.get() == 3, 2000);

      // Allow the final good request to complete
      goodRequest.proceed();
      goodRequest.waitForCondition(() -> refreshCount.get() == 4, 2000);

      // Try getSslData() again, and assert the refresh operation eventually completes.
      goodRequest.waitForCondition(() -> r.getConnectionInfo(TEST_TIMEOUT_MS) == data, 2000);
    } finally {
      r.close();
    }
  }

  @Test
  public void testClosedInstanceDataThrowsException() {
    ExampleData data = new ExampleData(Instant.now().plus(1, ChronoUnit.HOURS));
    Refresher r =
        new Refresher(
            "RefresherTest.testClosedInstanceDataThrowsException",
            executorService,
            () -> Futures.immediateFuture(data),
            rateLimiter);
    r.close();

    assertThrows(IllegalStateException.class, () -> r.getConnectionInfo(TEST_TIMEOUT_MS));
    assertThrows(IllegalStateException.class, r::forceRefresh);
  }

  @Test
  public void testClosedInstanceDataStopsRefreshTasks() throws Exception {
    ExampleData data = new ExampleData(Instant.now().plus(1, ChronoUnit.HOURS));

    AtomicInteger refreshCount = new AtomicInteger();
    final PauseCondition refresh0 = new PauseCondition();

    Refresher r =
        new Refresher(
            "RefresherTest.testClosedInstanceDataStopsRefreshTasks",
            executorService,
            () -> {
              int c = refreshCount.get();
              if (c == 0) {
                refresh0.pause();
              }
              refreshCount.incrementAndGet();
              return Futures.immediateFuture(data);
            },
            rateLimiter);

    // Wait for the first refresh attempt to complete.
    refresh0.proceed();
    refresh0.waitForPauseToEnd(TEST_TIMEOUT_MS);

    // Assert that refresh gets instance data before it is closed
    refresh0.waitForCondition(() -> refreshCount.get() == 1, TEST_TIMEOUT_MS);

    // Assert that the next refresh task is scheduled in the future
    assertThat(r.getNext().isDone()).isFalse();

    // Close the instance
    r.close();

    // Assert that the next refresh task is canceled
    assertThat(r.getNext().isDone()).isTrue();
    assertThat(r.getNext().isCancelled()).isTrue();
  }

  @Test
  public void testRefreshesTokenIfExpired() throws Exception {
    ExampleData initialData = new ExampleData(Instant.now().minus(2, ChronoUnit.SECONDS));
    ExampleData data = new ExampleData(Instant.now().plus(1, ChronoUnit.HOURS));

    AtomicInteger refreshCount = new AtomicInteger();
    final PauseCondition refresh1 = new PauseCondition();

    Refresher r =
        new Refresher(
            "RefresherTest.testRefreshesTokenIfExpired",
            executorService,
            new RefreshCalculator(),
            () -> {
              int c = refreshCount.get();
              ExampleData refreshResult = data;
              if (c == 0) { // refresh 0 should return initialData immediately
                refreshResult = initialData;
              }
              // refresh 2 and on should return data immediately
              refreshCount.incrementAndGet();
              return Futures.immediateFuture(refreshResult);
            },
            rateLimiter,
            false);

    // Get the first data that is about to expire
    refresh1.waitForCondition(() -> r.getConnectionInfo(TEST_TIMEOUT_MS) == initialData, 1000L);
    try {
      assertThat(refreshCount.get()).isEqualTo(1);

      r.refreshIfExpired();

      // getConnectionInfo again, and assert the refresh operation completed.
      refresh1.waitForCondition(() -> r.getConnectionInfo(TEST_TIMEOUT_MS) == data, 1000L);
      assertThat(refreshCount.get()).isEqualTo(2);
    } finally {
      r.close();
    }
  }

  @Test
  public void testGetConnectionInfo_throwsTerminalException_refreshOperationNotScheduled() {
    ExampleData data = new ExampleData(Instant.now().plus(1, ChronoUnit.HOURS));
    AtomicInteger refreshCount = new AtomicInteger();

    Refresher r =
        new Refresher(
            "RefresherTest.testGetConnectionInfo_throwsTerminalException_refreshOperationNotScheduled",
            executorService,
            () -> {
              int c = refreshCount.get();
              ExampleData refreshResult = data;
              if (c == 0) { // refresh 0 should throw an exception
                refreshCount.incrementAndGet();
                throw new TerminalException("Not authorized");
              }
              // refresh 2 and on should return data immediately
              refreshCount.incrementAndGet();
              return Futures.immediateFuture(refreshResult);
            },
            rateLimiter);

    try {
      // Raising TerminalException stops the refresher's executor from running the next task.
      assertThrows(TerminalException.class, () -> r.getConnectionInfo(TEST_TIMEOUT_MS));
      assertThat(refreshCount.get()).isEqualTo(1);
    } finally {
      r.close();
    }
  }

  @Test
  public void testGetConnectionInfo_throwsRuntimeException_refreshOperationScheduled()
      throws Exception {
    ExampleData data = new ExampleData(Instant.now().plus(1, ChronoUnit.HOURS));
    AtomicInteger refreshCount = new AtomicInteger();
    final PauseCondition refresh1 = new PauseCondition();

    Refresher r =
        new Refresher(
            "RefresherTest.testGetConnectionInfo_throwsRuntimeException_refreshOperationScheduled",
            executorService,
            () -> {
              int c = refreshCount.get();
              ExampleData refreshResult = data;
              if (c == 0) { // refresh 0 should throw an exception
                refreshCount.incrementAndGet();
                throw new RuntimeException("Bad Gateway");
              }
              // refresh 2 and on should return data immediately
              refreshCount.incrementAndGet();
              return Futures.immediateFuture(refreshResult);
            },
            rateLimiter);

    // getConnectionInfo again, and assert the refresh operation completed.
    refresh1.waitForCondition(() -> r.getConnectionInfo(TEST_TIMEOUT_MS) == data, 1000L);
    try {
      assertThat(refreshCount.get()).isEqualTo(2);
    } finally {
      r.close();
    }
  }

  private ListeningScheduledExecutorService newTestExecutor() {
    ScheduledThreadPoolExecutor executor =
        (ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(2);
    executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    return MoreExecutors.listeningDecorator(
        MoreExecutors.getExitingScheduledExecutorService(executor));
  }

  private static class SpyRateLimiter extends AsyncRateLimiter {
    int counter;

    SpyRateLimiter(long delayBetweenAttempts) {
      super(delayBetweenAttempts);
    }

    @Override
    public ListenableFuture<?> acquireAsync(ScheduledExecutorService executor) {
      counter++;
      return super.acquireAsync(executor);
    }
  }

  private static class ExampleData extends ConnectionInfo {

    private final Instant expiration;

    ExampleData(Instant expiration) {
      super(
          "10.1.1.1",
          "34.1.1.1",
          "abcde.12345.us-central1.alloydb.goog",
          "instance",
          null,
          null,
          null);
      this.expiration = expiration;
    }

    @Override
    Instant getExpiration() {
      return expiration;
    }
  }
}
