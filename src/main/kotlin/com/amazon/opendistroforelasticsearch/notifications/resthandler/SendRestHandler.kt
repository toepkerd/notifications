/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 *
 */

package com.amazon.opendistroforelasticsearch.notifications.resthandler

import com.amazon.opendistroforelasticsearch.notifications.NotificationPlugin.Companion.LOG_PREFIX
import com.amazon.opendistroforelasticsearch.notifications.NotificationPlugin.Companion.PLUGIN_BASE_URI
import com.amazon.opendistroforelasticsearch.notifications.action.SendAction
import com.amazon.opendistroforelasticsearch.notifications.util.logger
import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.rest.BaseRestHandler
import org.elasticsearch.rest.BaseRestHandler.RestChannelConsumer
import org.elasticsearch.rest.RestHandler.Route
import org.elasticsearch.rest.RestRequest
import org.elasticsearch.rest.RestRequest.Method.POST
import java.io.IOException

/**
 * Rest handler for sending notification.
 * This handler [SendAction] for sending notification.
 */
internal class SendRestHandler : BaseRestHandler() {

    companion object {
        private val log by logger(SendRestHandler::class.java)
        const val SEND_BASE_URI = "$PLUGIN_BASE_URI/send"
    }

    /**
     * {@inheritDoc}
     */
    override fun getName(): String = "send"

    /**
     * {@inheritDoc}
     */
    override fun routes(): List<Route> {
        return listOf(
            Route(POST, SEND_BASE_URI)
        )
    }

    /**
     * {@inheritDoc}
     */
    @Throws(IOException::class)
    @Suppress("SpreadOperator") // There is no way around dealing with java vararg without spread operator.
    override fun prepareRequest(request: RestRequest, client: NodeClient): RestChannelConsumer {
        log.debug("$LOG_PREFIX:prepareRequest")
        return RestChannelConsumer {
            SendAction(request, client, it).send()
        }
    }
}
