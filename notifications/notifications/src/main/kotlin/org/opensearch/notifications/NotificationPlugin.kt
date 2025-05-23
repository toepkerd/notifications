/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.notifications

import org.opensearch.action.ActionRequest
import org.opensearch.cluster.metadata.IndexNameExpressionResolver
import org.opensearch.cluster.node.DiscoveryNodes
import org.opensearch.cluster.service.ClusterService
import org.opensearch.common.settings.ClusterSettings
import org.opensearch.common.settings.IndexScopedSettings
import org.opensearch.common.settings.Setting
import org.opensearch.common.settings.Settings
import org.opensearch.common.settings.SettingsFilter
import org.opensearch.commons.notifications.action.NotificationsActions
import org.opensearch.commons.utils.logger
import org.opensearch.core.action.ActionResponse
import org.opensearch.core.common.io.stream.NamedWriteableRegistry
import org.opensearch.core.xcontent.NamedXContentRegistry
import org.opensearch.env.Environment
import org.opensearch.env.NodeEnvironment
import org.opensearch.indices.SystemIndexDescriptor
import org.opensearch.notifications.action.CreateNotificationConfigAction
import org.opensearch.notifications.action.DeleteNotificationConfigAction
import org.opensearch.notifications.action.GetChannelListAction
import org.opensearch.notifications.action.GetNotificationConfigAction
import org.opensearch.notifications.action.GetPluginFeaturesAction
import org.opensearch.notifications.action.PublishNotificationAction
import org.opensearch.notifications.action.SendNotificationAction
import org.opensearch.notifications.action.SendTestNotificationAction
import org.opensearch.notifications.action.UpdateNotificationConfigAction
import org.opensearch.notifications.index.ConfigIndexingActions
import org.opensearch.notifications.index.NotificationConfigIndex
import org.opensearch.notifications.resthandler.NotificationChannelListRestHandler
import org.opensearch.notifications.resthandler.NotificationConfigRestHandler
import org.opensearch.notifications.resthandler.NotificationFeaturesRestHandler
import org.opensearch.notifications.resthandler.SendTestMessageRestHandler
import org.opensearch.notifications.security.UserAccessManager
import org.opensearch.notifications.send.SendMessageActionHelper
import org.opensearch.notifications.settings.PluginSettings
import org.opensearch.notifications.settings.PluginSettings.REMOTE_METADATA_ENDPOINT
import org.opensearch.notifications.settings.PluginSettings.REMOTE_METADATA_REGION
import org.opensearch.notifications.settings.PluginSettings.REMOTE_METADATA_SERVICE_NAME
import org.opensearch.notifications.settings.PluginSettings.REMOTE_METADATA_STORE_TYPE
import org.opensearch.notifications.spi.NotificationCore
import org.opensearch.notifications.spi.NotificationCoreExtension
import org.opensearch.notifications.util.SecureIndexClient
import org.opensearch.plugins.ActionPlugin
import org.opensearch.plugins.Plugin
import org.opensearch.plugins.SystemIndexPlugin
import org.opensearch.remote.metadata.client.impl.SdkClientFactory
import org.opensearch.remote.metadata.common.CommonValue.REMOTE_METADATA_ENDPOINT_KEY
import org.opensearch.remote.metadata.common.CommonValue.REMOTE_METADATA_REGION_KEY
import org.opensearch.remote.metadata.common.CommonValue.REMOTE_METADATA_SERVICE_NAME_KEY
import org.opensearch.remote.metadata.common.CommonValue.REMOTE_METADATA_TYPE_KEY
import org.opensearch.remote.metadata.common.CommonValue.TENANT_AWARE_KEY
import org.opensearch.repositories.RepositoriesService
import org.opensearch.rest.RestController
import org.opensearch.rest.RestHandler
import org.opensearch.script.ScriptService
import org.opensearch.threadpool.ThreadPool
import org.opensearch.transport.client.Client
import org.opensearch.watcher.ResourceWatcherService
import java.util.function.Supplier

/**
 * Entry point of the OpenSearch Notifications plugin
 * This class initializes the rest handlers.
 */
class NotificationPlugin : ActionPlugin, Plugin(), NotificationCoreExtension, SystemIndexPlugin {

    lateinit var clusterService: ClusterService // initialized in createComponents()

    internal companion object {
        private val log by logger(NotificationPlugin::class.java)

        // Plugin main global constants
        const val PLUGIN_NAME = "opensearch-notifications"
        const val LOG_PREFIX = "notifications"
        const val PLUGIN_BASE_URI = "/_plugins/_notifications"

        // Other global constants
        const val TEXT_QUERY_TAG = "text_query"
    }

    /**
     * {@inheritDoc}
     */
    override fun getSettings(): List<Setting<*>> {
        log.debug("$LOG_PREFIX:getSettings")
        return PluginSettings.getAllSettings()
    }

    override fun getSystemIndexDescriptors(settings: Settings?): Collection<SystemIndexDescriptor> {
        return listOf(
            SystemIndexDescriptor(
                NotificationConfigIndex.INDEX_NAME,
                "System index for storing notification channels related configurations."
            )
        )
    }

    /**
     * {@inheritDoc}
     */
    override fun createComponents(
        client: Client,
        clusterService: ClusterService,
        threadPool: ThreadPool,
        resourceWatcherService: ResourceWatcherService,
        scriptService: ScriptService,
        xContentRegistry: NamedXContentRegistry,
        environment: Environment,
        nodeEnvironment: NodeEnvironment,
        namedWriteableRegistry: NamedWriteableRegistry,
        indexNameExpressionResolver: IndexNameExpressionResolver,
        repositoriesServiceSupplier: Supplier<RepositoriesService>
    ): Collection<Any> {
        log.debug("$LOG_PREFIX:createComponents")
        this.clusterService = clusterService
        val settings = environment.settings()
        val sdkClient = SdkClientFactory.createSdkClient(
            SecureIndexClient(client),
            xContentRegistry,
            mapOf(
                REMOTE_METADATA_TYPE_KEY to REMOTE_METADATA_STORE_TYPE.get(settings),
                REMOTE_METADATA_ENDPOINT_KEY to REMOTE_METADATA_ENDPOINT.get(settings),
                REMOTE_METADATA_REGION_KEY to REMOTE_METADATA_REGION.get(settings),
                REMOTE_METADATA_SERVICE_NAME_KEY to REMOTE_METADATA_SERVICE_NAME.get(settings),
                TENANT_AWARE_KEY to "false"
            ),
            client.threadPool().executor(ThreadPool.Names.GENERIC)
        )
        PluginSettings.addSettingsUpdateConsumer(clusterService)
        NotificationConfigIndex.initialize(sdkClient, client, clusterService)
        ConfigIndexingActions.initialize(NotificationConfigIndex, UserAccessManager)
        SendMessageActionHelper.initialize(NotificationConfigIndex, UserAccessManager)
        return listOf(sdkClient)
    }

    /**
     * {@inheritDoc}
     */
    override fun getActions(): List<ActionPlugin.ActionHandler<out ActionRequest, out ActionResponse>> {
        log.debug("$LOG_PREFIX:getActions")
        return listOf(
            ActionPlugin.ActionHandler(SendTestNotificationAction.ACTION_TYPE, SendTestNotificationAction::class.java),
            ActionPlugin.ActionHandler(
                NotificationsActions.CREATE_NOTIFICATION_CONFIG_ACTION_TYPE,
                CreateNotificationConfigAction::class.java
            ),
            ActionPlugin.ActionHandler(
                NotificationsActions.UPDATE_NOTIFICATION_CONFIG_ACTION_TYPE,
                UpdateNotificationConfigAction::class.java
            ),
            ActionPlugin.ActionHandler(
                NotificationsActions.DELETE_NOTIFICATION_CONFIG_ACTION_TYPE,
                DeleteNotificationConfigAction::class.java
            ),
            ActionPlugin.ActionHandler(
                NotificationsActions.GET_NOTIFICATION_CONFIG_ACTION_TYPE,
                GetNotificationConfigAction::class.java
            ),
            ActionPlugin.ActionHandler(
                NotificationsActions.GET_CHANNEL_LIST_ACTION_TYPE,
                GetChannelListAction::class.java
            ),
            ActionPlugin.ActionHandler(
                NotificationsActions.GET_PLUGIN_FEATURES_ACTION_TYPE,
                GetPluginFeaturesAction::class.java
            ),
            ActionPlugin.ActionHandler(
                NotificationsActions.SEND_NOTIFICATION_ACTION_TYPE,
                SendNotificationAction::class.java
            ),
            ActionPlugin.ActionHandler(
                NotificationsActions.LEGACY_PUBLISH_NOTIFICATION_ACTION_TYPE,
                PublishNotificationAction::class.java
            )
        )
    }

    /**
     * {@inheritDoc}
     */
    override fun getRestHandlers(
        settings: Settings,
        restController: RestController,
        clusterSettings: ClusterSettings,
        indexScopedSettings: IndexScopedSettings,
        settingsFilter: SettingsFilter,
        indexNameExpressionResolver: IndexNameExpressionResolver,
        nodesInCluster: Supplier<DiscoveryNodes>
    ): List<RestHandler> {
        log.debug("$LOG_PREFIX:getRestHandlers")
        return listOf(
            NotificationConfigRestHandler(),
            NotificationFeaturesRestHandler(),
            NotificationChannelListRestHandler(),
            SendTestMessageRestHandler()
            // NotificationStatsRestHandler()
        )
    }

    override fun setNotificationCore(core: NotificationCore) {
        log.debug("$LOG_PREFIX:setNotificationCore")
        CoreProvider.initialize(core)
    }
}
