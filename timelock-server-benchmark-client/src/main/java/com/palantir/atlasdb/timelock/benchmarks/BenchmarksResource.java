/*
 * Copyright 2017 Palantir Technologies, Inc. All rights reserved.
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.atlasdb.timelock.benchmarks;

import java.util.Map;

import com.google.common.collect.ImmutableSet;
import com.palantir.atlasdb.config.AtlasDbConfig;
import com.palantir.atlasdb.factory.TransactionManagers;
import com.palantir.atlasdb.transaction.impl.SerializableTransactionManager;

public class BenchmarksResource implements BenchmarksService {

    private final AtlasDbConfig config;
    private final SerializableTransactionManager txnManager;

    public BenchmarksResource(AtlasDbConfig config) {
        this.config = config;
        this.txnManager = TransactionManagers.create(config, ImmutableSet.of(), res -> { }, true);
    }

    @Override
    public Map<String, Object> writeTransaction(int numClients, int numRequestsPerClient) {
        return WriteTransactionBenchmark.execute(txnManager, numClients, numRequestsPerClient);
    }

    @Override
    public Map<String, Object> contendedWriteTransaction(int numClients, int numRequestsPerClient) {
        return ContendedWriteTransactionBenchmark.execute(txnManager, numClients, numRequestsPerClient);
    }

    @Override
    public Map<String, Object> readTransaction(int numClients, int numRequestsPerClient) {
        return ReadTransactionBenchmark.execute(txnManager, numClients, numRequestsPerClient);
    }

    @Override
    public Map<String, Object> kvsWrite(int numClients, int numRequestsPerClient) {
        return KvsWriteBenchmark.execute(txnManager, numClients, numRequestsPerClient);
    }

    @Override
    public Map<String, Object> kvsCas(int numClients, int numRequestsPerClient) {
        return KvsCasBenchmark.execute(txnManager, numClients, numRequestsPerClient);
    }

    @Override
    public Map<String, Object> kvsRead(int numClients, int numRequestsPerClient) {
        return KvsReadBenchmark.execute(txnManager, numClients, numRequestsPerClient);
    }

    @Override
    public Map<String, Object> timestamp(int numClients, int numRequestsPerClient) {
        return TimestampBenchmark.execute(txnManager, numClients, numRequestsPerClient);
    }
}
