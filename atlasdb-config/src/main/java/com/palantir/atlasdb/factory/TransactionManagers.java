/*
 * Copyright 2015 Palantir Technologies, Inc. All rights reserved.
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
package com.palantir.atlasdb.factory;

import java.util.ServiceLoader;
import java.util.Set;

import javax.annotation.Nullable;
import javax.net.ssl.SSLSocketFactory;

import org.apache.commons.lang3.ObjectUtils;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.MetricRegistry;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.palantir.atlasdb.AtlasDbConstants;
import com.palantir.atlasdb.cleaner.Cleaner;
import com.palantir.atlasdb.cleaner.CleanupFollower;
import com.palantir.atlasdb.cleaner.DefaultCleanerBuilder;
import com.palantir.atlasdb.config.AtlasDbConfig;
import com.palantir.atlasdb.config.AtlasDbRuntimeConfig;
import com.palantir.atlasdb.config.ImmutableAtlasDbConfig;
import com.palantir.atlasdb.config.LeaderConfig;
import com.palantir.atlasdb.config.ServerListConfig;
import com.palantir.atlasdb.config.TimeLockClientConfig;
import com.palantir.atlasdb.factory.startup.TimeLockMigrator;
import com.palantir.atlasdb.http.UserAgents;
import com.palantir.atlasdb.keyvalue.api.KeyValueService;
import com.palantir.atlasdb.keyvalue.impl.NamespacedKeyValueServices;
import com.palantir.atlasdb.keyvalue.impl.ProfilingKeyValueService;
import com.palantir.atlasdb.keyvalue.impl.SweepStatsKeyValueService;
import com.palantir.atlasdb.keyvalue.impl.TracingKeyValueService;
import com.palantir.atlasdb.keyvalue.impl.ValidatingQueryRewritingKeyValueService;
import com.palantir.atlasdb.memory.InMemoryAtlasDbConfig;
import com.palantir.atlasdb.persistentlock.CheckAndSetExceptionMapper;
import com.palantir.atlasdb.persistentlock.KvsBackedPersistentLockService;
import com.palantir.atlasdb.persistentlock.NoOpPersistentLockService;
import com.palantir.atlasdb.persistentlock.PersistentLockService;
import com.palantir.atlasdb.schema.SweepSchema;
import com.palantir.atlasdb.schema.generated.SweepTableFactory;
import com.palantir.atlasdb.spi.AtlasDbFactory;
import com.palantir.atlasdb.sweep.BackgroundSweeper;
import com.palantir.atlasdb.sweep.BackgroundSweeperImpl;
import com.palantir.atlasdb.sweep.CellsSweeper;
import com.palantir.atlasdb.sweep.ImmutableSweepBatchConfig;
import com.palantir.atlasdb.sweep.NoOpBackgroundSweeperPerformanceLogger;
import com.palantir.atlasdb.sweep.PersistentLockManager;
import com.palantir.atlasdb.sweep.SweepBatchConfig;
import com.palantir.atlasdb.sweep.SweepTaskRunner;
import com.palantir.atlasdb.table.description.Schema;
import com.palantir.atlasdb.table.description.Schemas;
import com.palantir.atlasdb.transaction.api.AtlasDbConstraintCheckingMode;
import com.palantir.atlasdb.transaction.impl.ConflictDetectionManager;
import com.palantir.atlasdb.transaction.impl.ConflictDetectionManagers;
import com.palantir.atlasdb.transaction.impl.SerializableTransactionManager;
import com.palantir.atlasdb.transaction.impl.SweepStrategyManager;
import com.palantir.atlasdb.transaction.impl.SweepStrategyManagers;
import com.palantir.atlasdb.transaction.impl.TransactionTables;
import com.palantir.atlasdb.transaction.service.TransactionService;
import com.palantir.atlasdb.transaction.service.TransactionServices;
import com.palantir.atlasdb.util.AtlasDbMetrics;
import com.palantir.leader.LeaderElectionService;
import com.palantir.leader.proxy.AwaitingLeadershipProxy;
import com.palantir.lock.LockClient;
import com.palantir.lock.LockServerOptions;
import com.palantir.lock.RemoteLockService;
import com.palantir.lock.client.LockRefreshingRemoteLockService;
import com.palantir.lock.impl.LockServiceImpl;
import com.palantir.timestamp.TimestampService;
import com.palantir.timestamp.TimestampStoreInvalidator;

public final class TransactionManagers {
    private static final Logger log = LoggerFactory.getLogger(TransactionManagers.class);
    private static final ServiceLoader<AtlasDbFactory> loader = ServiceLoader.load(AtlasDbFactory.class);
    public static final LockClient LOCK_CLIENT = LockClient.of("atlas instance");

    private TransactionManagers() {
        // Utility class
    }

    /**
     * Accepts a single {@link Schema}.
     * @see TransactionManagers#createInMemory(Set)
     */
    public static SerializableTransactionManager createInMemory(Schema schema) {
        return createInMemory(ImmutableSet.of(schema));
    }

    /**
     * Create a {@link SerializableTransactionManager} backed by an
     * {@link com.palantir.atlasdb.keyvalue.impl.InMemoryKeyValueService}. This should be used for testing
     * purposes only.
     */
    public static SerializableTransactionManager createInMemory(Set<Schema> schemas) {
        AtlasDbConfig config = ImmutableAtlasDbConfig.builder().keyValueService(new InMemoryAtlasDbConfig()).build();
        AtlasDbRuntimeConfig runtimeConfig = AtlasDbRuntimeConfig.create(config);
        return create(config,
                () -> runtimeConfig,
                schemas,
                x -> { },
                false);
    }

    /**
     * Create a {@link SerializableTransactionManager} with provided configurations, {@link Schema},
     * and an environment in which to register HTTP server endpoints.
     */
    public static SerializableTransactionManager create(
            AtlasDbConfig config,
            java.util.function.Supplier<AtlasDbRuntimeConfig> runtimeConfig,
            Schema schema,
            Environment env,
            boolean allowHiddenTableAccess) {
        return create(config, runtimeConfig, ImmutableSet.of(schema), env, allowHiddenTableAccess);
    }

    /**
     * Create a {@link SerializableTransactionManager} with provided configurations, a set of
     * {@link Schema}s, and an environment in which to register HTTP server endpoints.
     */
    public static SerializableTransactionManager create(
            AtlasDbConfig config,
            java.util.function.Supplier<AtlasDbRuntimeConfig> runtimeConfig,
            Set<Schema> schemas,
            Environment env,
            boolean allowHiddenTableAccess) {
        log.info("Called TransactionManagers.create with live reloading config on thread {}."
                        + " This should only happen once.",
                Thread.currentThread().getName());
        return create(config, runtimeConfig, schemas, env, LockServerOptions.DEFAULT, allowHiddenTableAccess);
    }

    /**
     * Create a {@link SerializableTransactionManager} with provided configurations, a set of
     * {@link Schema}s, {@link LockServerOptions}, and an environment in which to register HTTP server endpoints.
     */
    public static SerializableTransactionManager create(
            AtlasDbConfig config,
            java.util.function.Supplier<AtlasDbRuntimeConfig> runtimeConfig,
            Set<Schema> schemas,
            Environment env,
            LockServerOptions lockServerOptions,
            boolean allowHiddenTableAccess) {
        validateRuntimeConfig(runtimeConfig.get());
        return create(config, runtimeConfig, schemas, env, lockServerOptions, allowHiddenTableAccess,
                UserAgents.DEFAULT_USER_AGENT);
    }

    public static SerializableTransactionManager create(
            AtlasDbConfig config,
            java.util.function.Supplier<AtlasDbRuntimeConfig> runtimeConfig,
            Set<Schema> schemas,
            Environment env,
            LockServerOptions lockServerOptions,
            boolean allowHiddenTableAccess,
            Class<?> callingClass) {
        validateRuntimeConfig(runtimeConfig.get());
        return create(config, runtimeConfig, schemas, env, lockServerOptions, allowHiddenTableAccess,
                UserAgents.fromClass(callingClass));
    }

    private static SerializableTransactionManager create(
            AtlasDbConfig config,
            java.util.function.Supplier<AtlasDbRuntimeConfig> runtimeConfig,
            Set<Schema> schemas,
            Environment env,
            LockServerOptions lockServerOptions,
            boolean allowHiddenTableAccess,
            String userAgent) {
        ServiceDiscoveringAtlasSupplier atlasFactory =
                new ServiceDiscoveringAtlasSupplier(config.keyValueService(), config.leader());

        KeyValueService rawKvs = atlasFactory.getKeyValueService();

        LockAndTimestampServices lockAndTimestampServices = createLockAndTimestampServices(
                config,
                env,
                () -> LockServiceImpl.create(lockServerOptions),
                atlasFactory::getTimestampService,
                atlasFactory.getTimestampStoreInvalidator(),
                userAgent);

        KeyValueService kvs = NamespacedKeyValueServices.wrapWithStaticNamespaceMappingKvs(rawKvs);
        kvs = ProfilingKeyValueService.create(kvs, config.getKvsSlowLogThresholdMillis());
        kvs = SweepStatsKeyValueService.create(kvs, lockAndTimestampServices.time());
        kvs = TracingKeyValueService.create(kvs);
        kvs = AtlasDbMetrics.instrument(KeyValueService.class, kvs,
                MetricRegistry.name(KeyValueService.class, userAgent));
        kvs = ValidatingQueryRewritingKeyValueService.create(kvs);

        TransactionTables.createTables(kvs);

        PersistentLockService persistentLockService = createAndRegisterPersistentLockService(kvs, env);

        TransactionService transactionService = TransactionServices.createTransactionService(kvs);
        ConflictDetectionManager conflictManager = ConflictDetectionManagers.create(kvs);
        SweepStrategyManager sweepStrategyManager = SweepStrategyManagers.createDefault(kvs);

        Set<Schema> allSchemas = ImmutableSet.<Schema>builder()
                .add(SweepSchema.INSTANCE.getLatestSchema())
                .addAll(schemas)
                .build();
        for (Schema schema : allSchemas) {
            Schemas.createTablesAndIndexes(schema, kvs);
        }

        CleanupFollower follower = CleanupFollower.create(schemas);

        Cleaner cleaner = new DefaultCleanerBuilder(
                kvs,
                lockAndTimestampServices.lock(),
                lockAndTimestampServices.time(),
                LOCK_CLIENT,
                ImmutableList.of(follower),
                transactionService)
                .setBackgroundScrubAggressively(config.backgroundScrubAggressively())
                .setBackgroundScrubBatchSize(config.getBackgroundScrubBatchSize())
                .setBackgroundScrubFrequencyMillis(config.getBackgroundScrubFrequencyMillis())
                .setBackgroundScrubThreads(config.getBackgroundScrubThreads())
                .setPunchIntervalMillis(config.getPunchIntervalMillis())
                .setTransactionReadTimeout(config.getTransactionReadTimeoutMillis())
                .buildCleaner();

        SerializableTransactionManager transactionManager = new SerializableTransactionManager(kvs,
                lockAndTimestampServices.time(),
                LOCK_CLIENT,
                lockAndTimestampServices.lock(),
                transactionService,
                Suppliers.ofInstance(AtlasDbConstraintCheckingMode.FULL_CONSTRAINT_CHECKING_THROWS_EXCEPTIONS),
                conflictManager,
                sweepStrategyManager,
                cleaner,
                allowHiddenTableAccess);

        PersistentLockManager persistentLockManager = new PersistentLockManager(
                persistentLockService,
                config.getSweepPersistentLockWaitMillis());
        CellsSweeper cellsSweeper = new CellsSweeper(
                transactionManager,
                kvs,
                persistentLockManager,
                ImmutableList.of(follower));
        SweepTaskRunner sweepRunner = new SweepTaskRunner(
                kvs,
                transactionManager::getUnreadableTimestamp,
                transactionManager::getImmutableTimestamp,
                transactionService,
                sweepStrategyManager,
                cellsSweeper);
        BackgroundSweeper backgroundSweeper = BackgroundSweeperImpl.create(
                transactionManager,
                kvs,
                sweepRunner,
                () -> MoreObjects.firstNonNull(runtimeConfig.get().enableSweep(), config.enableSweep()),
                () -> MoreObjects.firstNonNull(runtimeConfig.get().getSweepPauseMillis(), config.getSweepPauseMillis()),
                () -> getSweepBatchConfig(runtimeConfig.get(), config),
                SweepTableFactory.of(),
                new NoOpBackgroundSweeperPerformanceLogger(),
                persistentLockManager);
        backgroundSweeper.runInBackground();

        return transactionManager;
    }

    private static void validateRuntimeConfig(@Nullable AtlasDbRuntimeConfig runtimeConfig) {
        if (runtimeConfig == null) {
            log.warn("AtlasDB now supports live reloadable configs. Please use the 'atlas-runtime' block for specifying"
                    + " live-reloadable atlas configs. Default to using configs specified on the 'atlas' block");
        }
    }

    private static SweepBatchConfig getSweepBatchConfig(AtlasDbRuntimeConfig runtimeConfig, AtlasDbConfig config) {
        if (config.getSweepBatchSize() != null || config.getSweepCellBatchSize() != null) {
            log.warn("Configuration parameters 'sweepBatchSize' and 'sweepCellBatchSize' have been deprecated"
                    + " in favor of 'sweepMaxCellTsPairsToExamine', 'sweepCandidateBatchSize'"
                    + " and 'sweepDeleteBatchSize'. Please update your configuration files.");
        }
        return ImmutableSweepBatchConfig.builder()
                .maxCellTsPairsToExamine(chooseBestValue(
                        runtimeConfig.getSweepReadLimit(),
                        config.getSweepReadLimit(),
                        config.getSweepCellBatchSize(),
                        AtlasDbConstants.DEFAULT_SWEEP_READ_LIMIT))
                .candidateBatchSize(chooseBestValue(
                        runtimeConfig.getSweepCandidateBatchHint(),
                        config.getSweepCandidateBatchHint(),
                        config.getSweepBatchSize(),
                        AtlasDbConstants.DEFAULT_SWEEP_CANDIDATE_BATCH_HINT))
                .deleteBatchSize(chooseBestValue(
                        runtimeConfig.getSweepDeleteBatchHint(),
                        config.getSweepDeleteBatchHint(),
                        AtlasDbConstants.DEFAULT_SWEEP_DELETE_BATCH_HINT))
                .build();
    }

    private static int chooseBestValue(@Nullable Integer runtimeOption,
            @Nullable Integer newOption, @Nullable Integer oldOption, int defaultValue) {
        return ObjectUtils.firstNonNull(runtimeOption, newOption, oldOption, defaultValue);
    }

    private static int chooseBestValue(@Nullable Integer newOption, @Nullable Integer oldOption, int defaultValue) {
        return ObjectUtils.firstNonNull(newOption, oldOption, defaultValue);
    }

    private static PersistentLockService createAndRegisterPersistentLockService(KeyValueService kvs, Environment env) {
        if (!kvs.supportsCheckAndSet()) {
            return new NoOpPersistentLockService();
        }

        PersistentLockService pls = KvsBackedPersistentLockService.create(kvs);
        env.register(pls);
        env.register(new CheckAndSetExceptionMapper());
        return pls;
    }

    /**
     * This method should not be used directly. It remains here to support the AtlasDB-Dagger module and the CLIs, but
     * may be removed at some point in the future.
     *
     * @deprecated Not intended for public use outside of the AtlasDB CLIs
     */
    @Deprecated
    public static LockAndTimestampServices createLockAndTimestampServices(
            AtlasDbConfig config,
            Environment env,
            Supplier<RemoteLockService> lock,
            Supplier<TimestampService> time) {
        return createLockAndTimestampServices(
                config,
                env,
                lock,
                time,
                () -> {
                    log.warn("Note: Automatic migration isn't performed by the CLI tools.");
                    return AtlasDbFactory.NO_OP_FAST_FORWARD_TIMESTAMP;
                },
                UserAgents.DEFAULT_USER_AGENT);
    }

    @VisibleForTesting
    static LockAndTimestampServices createLockAndTimestampServices(
            AtlasDbConfig config,
            Environment env,
            Supplier<RemoteLockService> lock,
            Supplier<TimestampService> time,
            TimestampStoreInvalidator invalidator,
            String userAgent) {
        LockAndTimestampServices lockAndTimestampServices =
                createRawServices(config, env, lock, time, invalidator, userAgent);
        return withRefreshingLockService(lockAndTimestampServices);
    }

    private static LockAndTimestampServices withRefreshingLockService(
            LockAndTimestampServices lockAndTimestampServices) {
        return ImmutableLockAndTimestampServices.builder()
                .from(lockAndTimestampServices)
                .lock(LockRefreshingRemoteLockService.create(lockAndTimestampServices.lock()))
                .build();
    }

    @VisibleForTesting
    static LockAndTimestampServices createRawServices(
            AtlasDbConfig config,
            Environment env,
            Supplier<RemoteLockService> lock,
            Supplier<TimestampService> time,
            TimestampStoreInvalidator invalidator,
            String userAgent) {
        if (config.leader().isPresent()) {
            return createRawLeaderServices(config.leader().get(), env, lock, time, userAgent);
        } else if (config.timestamp().isPresent() && config.lock().isPresent()) {
            return createRawRemoteServices(config, userAgent);
        } else if (config.timelock().isPresent()) {
            TimeLockClientConfig timeLockClientConfig = config.timelock().get();
            TimeLockMigrator.create(timeLockClientConfig, invalidator, userAgent).migrate();
            return createNamespacedRawRemoteServices(timeLockClientConfig, userAgent);
        } else {
            return createRawEmbeddedServices(env, lock, time);
        }
    }

    private static LockAndTimestampServices createNamespacedRawRemoteServices(
            TimeLockClientConfig config,
            String userAgent) {
        ServerListConfig namespacedServerListConfig = config.toNamespacedServerList();
        return getLockAndTimestampServices(namespacedServerListConfig, userAgent);
    }

    private static LockAndTimestampServices getLockAndTimestampServices(
            ServerListConfig timelockServerListConfig,
            String userAgent) {
        RemoteLockService lockService = new ServiceCreator<>(RemoteLockService.class, userAgent)
                .apply(timelockServerListConfig);
        TimestampService timeService = new ServiceCreator<>(TimestampService.class, userAgent)
                .apply(timelockServerListConfig);

        return ImmutableLockAndTimestampServices.builder()
                .lock(lockService)
                .time(timeService)
                .build();
    }

    private static LockAndTimestampServices createRawLeaderServices(
            LeaderConfig leaderConfig,
            Environment env,
            Supplier<RemoteLockService> lock,
            Supplier<TimestampService> time,
            String userAgent) {
        LeaderElectionService leader = Leaders.create(env, leaderConfig, userAgent);

        env.register(AwaitingLeadershipProxy.newProxyInstance(RemoteLockService.class, lock, leader));
        env.register(AwaitingLeadershipProxy.newProxyInstance(TimestampService.class, time, leader));

        Optional<SSLSocketFactory> sslSocketFactory = ServiceCreator.createSslSocketFactory(
                leaderConfig.sslConfiguration());

        return ImmutableLockAndTimestampServices.builder()
                .lock(ServiceCreator.createService(
                        sslSocketFactory,
                        leaderConfig.leaders(),
                        RemoteLockService.class,
                        userAgent))
                .time(ServiceCreator.createService(
                        sslSocketFactory,
                        leaderConfig.leaders(),
                        TimestampService.class,
                        userAgent))
                .build();
    }

    private static LockAndTimestampServices createRawRemoteServices(AtlasDbConfig config, String userAgent) {
        RemoteLockService lockService = new ServiceCreator<>(RemoteLockService.class, userAgent)
                .apply(config.lock().get());
        TimestampService timeService = new ServiceCreator<>(TimestampService.class, userAgent)
                .apply(config.timestamp().get());

        return ImmutableLockAndTimestampServices.builder()
                .lock(lockService)
                .time(timeService)
                .build();
    }

    private static LockAndTimestampServices createRawEmbeddedServices(
            Environment env,
            Supplier<RemoteLockService> lock,
            Supplier<TimestampService> time) {
        RemoteLockService lockService = lock.get();
        TimestampService timeService = time.get();

        env.register(lockService);
        env.register(timeService);

        return ImmutableLockAndTimestampServices.builder()
                .lock(lockService)
                .time(timeService)
                .build();
    }

    @Value.Immutable
    public interface LockAndTimestampServices {
        RemoteLockService lock();
        TimestampService time();
    }

    public interface Environment {
        void register(Object resource);
    }
}
