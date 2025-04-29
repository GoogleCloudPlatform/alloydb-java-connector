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

import com.google.cloud.alloydb.v1alpha.InstanceName;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

class InMemoryConnectionInfoRepo implements ConnectionInfoRepository {

  private final ArrayList<Callable<ConnectionInfo>> registeredCallables;
  private final AtomicInteger index;

  public InMemoryConnectionInfoRepo() {
    registeredCallables = new ArrayList<>();
    index = new AtomicInteger(0);
  }

  @SuppressWarnings("RedundantThrows")
  @Override
  public ListenableFuture<ConnectionInfo> getConnectionInfo(
      InstanceName instanceName, KeyPair publicKey) {
    Callable<ConnectionInfo> callable = registeredCallables.get(index.getAndIncrement());
    try {
      return Futures.immediateFuture(callable.call());
    } catch (Exception e) {
      return Futures.immediateFailedFuture(new ExecutionException(e));
    }
  }

  @SafeVarargs
  public final void addResponses(Callable<ConnectionInfo>... callables) {
    registeredCallables.addAll(Arrays.asList(callables));
  }
}
