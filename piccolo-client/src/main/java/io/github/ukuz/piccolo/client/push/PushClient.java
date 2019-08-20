/*
 * Copyright 2019 ukuz90
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
package io.github.ukuz.piccolo.client.push;

import static io.github.ukuz.piccolo.api.common.threadpool.ExecutorFactory.*;

import io.github.ukuz.piccolo.api.connection.Connection;
import io.github.ukuz.piccolo.api.external.common.Assert;
import io.github.ukuz.piccolo.api.mq.MQMessageReceiver;
import io.github.ukuz.piccolo.api.push.PushContext;
import io.github.ukuz.piccolo.api.push.PushMsg;
import io.github.ukuz.piccolo.api.router.ClientLocator;
import io.github.ukuz.piccolo.client.PiccoloClient;
import io.github.ukuz.piccolo.common.message.PushMessage;
import io.github.ukuz.piccolo.core.router.RemoteRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;

import static io.github.ukuz.piccolo.mq.kafka.Topics.*;

/**
 * @author ukuz90
 */
public class PushClient {

    private ConcurrentMap<String, NestedMessageReceiver> topicsHandler = new ConcurrentHashMap<>();
    private PiccoloClient piccoloClient;
    private static final Logger LOGGER = LoggerFactory.getLogger(PushClient.class);
    private Executor dispatchHandlerExecutor;

    public PushClient() {
        piccoloClient = new PiccoloClient();
        dispatchHandlerExecutor = piccoloClient.getExecutorFactory().create(PUSH_CLIENT, piccoloClient.getEnvironment());
    }

    public void registerHandler(BaseDispatcherHandler handler) {
        Assert.notNull(handler, "handler must not be null");
        registerHandler(DISPATCH_MESSAGE.getTopic(), handler);
    }

    public void push(PushContext context) {
        Assert.notNull(context, "context must not be null");
        //先查询client所在的网关服务器的地址
        if (context.getUserId() != null) {
            Set<RemoteRouter> remoteRouters = piccoloClient.getRemoteRouterManager().lookupAll(context.getUserId());
            remoteRouters.forEach(remoteRouter -> {
                Connection connection = piccoloClient.getGatewayConnectionFactory().getConnection(remoteRouter.getRouterValue().getHostAndPort());
                if (connection != null) {
                    PushMessage msg = PushMessage.build(connection).content(context.getContext());
                    connection.sendAsync(msg);
                } else {
                    LOGGER.error("can not push message to gateway server, is it work, userId: {} server: {}",
                            context.getUserId(),
                            remoteRouter.getRouterValue().getHostAndPort());
                    //TODO 是否重试？
                }
            });
        } else if (context.getUserIds() != null && context.getUserIds().size() > 0) {

        } else if (context.isBroadcast()) {

        }
    }

    private void registerHandler(String topic, BaseDispatcherHandler handler) {
        Assert.notNull(topic, "topic must not be null");
        Assert.notNull(handler, "handler must not be null");
        LOGGER.info("registerHandler topic: {} handler: {}", topic, handler);

        topicsHandler.computeIfAbsent(topic, t -> new NestedMessageReceiver(handler));
        piccoloClient.getMQClient().subscribe(topic, topicsHandler.get(topic));
    }

    private class NestedMessageReceiver implements MQMessageReceiver<byte[]> {

        private BaseDispatcherHandler handler;

        public NestedMessageReceiver(BaseDispatcherHandler handler) {
            Assert.notNull(handler, "handler must not be null");
            this.handler = handler;
        }

        @Override
        public void receive(String topic, byte[] message) {
            dispatchHandlerExecutor.execute(() -> handler.onDispatch(message));
        }
    }

}