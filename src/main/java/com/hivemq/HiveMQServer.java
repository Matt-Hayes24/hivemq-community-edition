/*
 * Copyright 2019-present HiveMQ GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hivemq;

import com.codahale.metrics.MetricRegistry;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.inject.Injector;
import com.hivemq.bootstrap.*;
import com.hivemq.bootstrap.ioc.GuiceBootstrap;
import com.hivemq.common.shutdown.ShutdownHooks;
import com.hivemq.configuration.ConfigurationBootstrap;
import com.hivemq.configuration.HivemqId;
import com.hivemq.configuration.info.SystemInformationImpl;
import com.hivemq.configuration.service.FullConfigurationService;
import com.hivemq.configuration.service.InternalConfigurations;
import com.hivemq.embedded.EmbeddedExtension;
import com.hivemq.extension.sdk.api.annotations.NotNull;
import com.hivemq.extension.sdk.api.annotations.Nullable;
import com.hivemq.extension.sdk.api.services.admin.AdminService;
import com.hivemq.extensions.ExtensionBootstrap;
import com.hivemq.extensions.services.admin.AdminServiceImpl;
import com.hivemq.lifecycle.LifecycleModule;
import com.hivemq.metrics.MetricRegistryLogger;
import com.hivemq.migration.MigrationUnit;
import com.hivemq.migration.Migrations;
import com.hivemq.migration.meta.PersistenceType;
import com.hivemq.persistence.PersistenceStartup;
import com.hivemq.persistence.payload.PublishPayloadPersistence;
import com.hivemq.statistics.UsageStatistics;
import com.hivemq.util.Checkpoints;
import com.hivemq.util.TemporaryFileUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.hivemq.configuration.service.PersistenceConfigurationService.PersistenceMode;

/**
 * @author Dominik Obermaier
 * @author Florian Limpöck
 */
public class HiveMQServer {

    private static final Logger log = LoggerFactory.getLogger(HiveMQServer.class);

    private final @NotNull HiveMQNettyBootstrap nettyBootstrap;
    private final @NotNull PublishPayloadPersistence payloadPersistence;
    private final @NotNull ExtensionBootstrap extensionBootstrap;
    private final @NotNull AdminService adminService;

    @Inject
    HiveMQServer(
            final @NotNull HiveMQNettyBootstrap nettyBootstrap,
            final @NotNull PublishPayloadPersistence payloadPersistence,
            final @NotNull ExtensionBootstrap extensionBootstrap,
            final @NotNull AdminService adminService) {

        this.nettyBootstrap = nettyBootstrap;
        this.payloadPersistence = payloadPersistence;
        this.extensionBootstrap = extensionBootstrap;
        this.adminService = adminService;
    }

    public void start(final @Nullable EmbeddedExtension embeddedExtension) throws Exception {

        payloadPersistence.init();

        final CompletableFuture<Void> extensionStartFuture = extensionBootstrap.startExtensionSystem(embeddedExtension);
        extensionStartFuture.get();

        final ListenableFuture<List<ListenerStartupInformation>> startFuture = nettyBootstrap.bootstrapServer();

        final List<ListenerStartupInformation> startupInformation = startFuture.get();
        Checkpoints.checkpoint("listener-started");

        new StartupListenerVerifier(startupInformation).verifyAndPrint();

        ((AdminServiceImpl) adminService).hivemqStarted();
    }

    public static void main(final @NotNull String[] args) throws Exception {

        final long startTime = System.nanoTime();

        final MetricRegistry metricRegistry = new MetricRegistry();
        metricRegistry.addListener(new MetricRegistryLogger());

        final SystemInformationImpl systemInformation;
        LoggingBootstrap.prepareLogging();

        log.info("Starting HiveMQ Community Edition Server");

        log.trace("Initializing HiveMQ home directory");
        //Create SystemInformation this early because logging depends on it
        systemInformation = new SystemInformationImpl(true);

        log.trace("Initializing Logging");
        LoggingBootstrap.initLogging(systemInformation.getConfigFolder());

        log.trace("Initializing Exception handlers");
        HiveMQExceptionHandlerBootstrap.addUnrecoverableExceptionHandler();

        log.trace("Initializing configuration");
        final FullConfigurationService configService = ConfigurationBootstrap.bootstrapConfig(systemInformation);

        final HivemqId hiveMQId = new HivemqId();
        log.info("This HiveMQ ID is {}", hiveMQId.get());

        //ungraceful shutdown does not delete tmp folders, so we clean them up on broker start
        log.trace("Cleaning up temporary folders");
        TemporaryFileUtils.deleteTmpFolder(systemInformation.getDataFolder());

        //must happen before persistence injector bootstrap as it creates the persistence folder.
        log.trace("Checking for migrations");
        final Map<MigrationUnit, PersistenceType> migrations = Migrations.checkForTypeMigration(systemInformation);
        final Set<MigrationUnit> valueMigrations = Migrations.checkForValueMigration(systemInformation);

        final LifecycleModule lifecycleModule = new LifecycleModule();

        log.trace("Initializing persistences");
        final Injector persistenceInjector =
                GuiceBootstrap.persistenceInjector(systemInformation, metricRegistry, hiveMQId, configService,
                        lifecycleModule);
        //blocks until all persistences started
        persistenceInjector.getInstance(PersistenceStartup.class).finish();
        final ShutdownHooks shutdownHooks = persistenceInjector.getInstance(ShutdownHooks.class);

        if (shutdownHooks.isShuttingDown()) {
            return;
        }
        if (configService.persistenceConfigurationService().getMode() != PersistenceMode.IN_MEMORY) {

            if (migrations.size() + valueMigrations.size() > 0) {
                if(migrations.size() > 0) {
                    log.info("Persistence types has been changed, migrating persistent data.");
                } else {
                    log.info("Persistence values has been changed, migrating persistent data.");
                }
                for (final MigrationUnit migrationUnit : migrations.keySet()) {
                    log.debug("{} needs to be migrated.", StringUtils.capitalize(migrationUnit.toString()));
                }
                for (final MigrationUnit migrationUnit : valueMigrations) {
                    log.debug("{} needs to be migrated.", StringUtils.capitalize(migrationUnit.toString()));
                }
                Migrations.migrate(persistenceInjector, migrations, valueMigrations);
            }

            Migrations.afterMigration(systemInformation);

        } else {
            log.info("Starting with in memory persistences");
        }

        log.trace("Initializing Guice");
        final Injector injector = GuiceBootstrap.bootstrapInjector(systemInformation, metricRegistry, hiveMQId,
                configService, persistenceInjector, lifecycleModule);
        if (injector == null) {
            return;
        }

        if (shutdownHooks.isShuttingDown()) {
            return;
        }
        final HiveMQServer instance = injector.getInstance(HiveMQServer.class);

        if (InternalConfigurations.GC_AFTER_STARTUP) {
            log.trace("Starting initial garbage collection after startup");
            final long start = System.currentTimeMillis();
            //Start garbage collection of objects we don't need anymore after starting up
            System.gc();
            log.trace("Finished initial garbage collection after startup in {}ms", System.currentTimeMillis() - start);
        }

        if (shutdownHooks.isShuttingDown()) {
            return;
        }

        /* It's important that we are modifying the log levels after Guice is initialized,
        otherwise this somehow interferes with Singleton creation */
        LoggingBootstrap.addLoglevelModifiers();
        instance.start(null);

        if (shutdownHooks.isShuttingDown()) {
            return;
        }

        log.info("Started HiveMQ in {}ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime));

        if (shutdownHooks.isShuttingDown()) {
            return;
        }

        final UsageStatistics usageStatistics = injector.getInstance(UsageStatistics.class);
        usageStatistics.start();
    }
}
