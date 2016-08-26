/**
 * Copyright 2016 Palantir Technologies
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
 *
 */

package com.palantir.atlasdb.performance.backend;

import java.net.InetSocketAddress;
import java.util.function.Function;

import com.palantir.atlasdb.cassandra.ImmutableCassandraCredentialsConfig;
import com.palantir.atlasdb.cassandra.ImmutableCassandraKeyValueServiceConfig;
import com.palantir.atlasdb.keyvalue.dbkvs.ImmutableDbKeyValueServiceConfig;
import com.palantir.atlasdb.keyvalue.dbkvs.ImmutablePostgresDdlConfig;
import com.palantir.atlasdb.spi.KeyValueServiceConfig;
import com.palantir.nexus.db.pool.config.ImmutableMaskedValue;
import com.palantir.nexus.db.pool.config.ImmutablePostgresConnectionConfig;

public enum KeyValueServiceType {
    POSTGRES(addr -> ImmutableDbKeyValueServiceConfig.builder()
            .ddl(ImmutablePostgresDdlConfig.builder().build())
            .connection(
                    ImmutablePostgresConnectionConfig.builder()
                            .host(addr.getHostString())
                            .port(5432)
                            .dbName("atlas")
                            .dbLogin("palantir")
                            .dbPassword(ImmutableMaskedValue.of("palantir"))
                            .build()
            ).build(),
            5432,
            "postgres-docker-compose.yml"),
    CASSANDRA(addr -> ImmutableCassandraKeyValueServiceConfig.builder()
            .addServers(addr)
            .poolSize(20)
            .keyspace("atlasdb")
            .credentials(ImmutableCassandraCredentialsConfig.builder()
                    .username("cassandra")
                    .password("cassandra")
                    .build())
            .ssl(false)
            .replicationFactor(1)
            .mutationBatchCount(10000)
            .mutationBatchSizeBytes(10000000)
            .fetchBatchCount(1000)
            .safetyDisabled(false)
            .autoRefreshNodes(false)
            .build(),
            9160,
            "cassandra-docker-compose.yml");

    private final Function<InetSocketAddress, KeyValueServiceConfig> addressToKvsConfigFunction;
    private final int kvsPort;
    private final String dockerComposeFileName;

    KeyValueServiceType(Function<InetSocketAddress, KeyValueServiceConfig> addressToKvsConfigFunction,
            int kvsPort,
            String dockerComposeFileName) {
        this.addressToKvsConfigFunction = addressToKvsConfigFunction;
        this.kvsPort = kvsPort;
        this.dockerComposeFileName = dockerComposeFileName;
    }

    public KeyValueServiceConfig getKeyValueServiceConfig(InetSocketAddress docker) {
        return addressToKvsConfigFunction.apply(docker);
    }

    public String getDockerComposeResourceFileName() {
        return dockerComposeFileName;
    }

    public int getKeyValueServicePort() {
        return kvsPort;
    }
}
