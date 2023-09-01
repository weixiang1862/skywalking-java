/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.skywalking.apm.plugin.arthas.service;

import com.taobao.arthas.common.PidUtils;
import com.taobao.arthas.common.SocketUtils;
import io.grpc.Channel;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.apache.skywalking.apm.agent.core.boot.BootService;
import org.apache.skywalking.apm.agent.core.boot.DefaultNamedThreadFactory;
import org.apache.skywalking.apm.agent.core.boot.ServiceManager;
import org.apache.skywalking.apm.agent.core.conf.Config;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.remote.GRPCChannelListener;
import org.apache.skywalking.apm.agent.core.remote.GRPCChannelManager;
import org.apache.skywalking.apm.agent.core.remote.GRPCChannelStatus;
import org.apache.skywalking.apm.network.arthas.ArthasCommandServiceGrpc;
import org.apache.skywalking.apm.network.arthas.CommandRequest;
import org.apache.skywalking.apm.network.arthas.CommandResponse;
import org.apache.skywalking.apm.plugin.arthas.util.ArthasCtl;
import org.apache.skywalking.apm.plugin.arthas.config.ArthasConfig;
import org.apache.skywalking.apm.util.RunnableWithExceptionProtection;
import org.apache.skywalking.apm.util.StringUtil;

import static org.apache.skywalking.apm.agent.core.conf.Config.Collector.GRPC_UPSTREAM_TIMEOUT;

public class CommandListener implements BootService, GRPCChannelListener {

    private volatile ScheduledFuture<?> getCommandFuture;
    private volatile GRPCChannelStatus status = GRPCChannelStatus.DISCONNECT;
    private volatile ArthasCommandServiceGrpc.ArthasCommandServiceBlockingStub commandServiceBlockingStub;

    private volatile Integer arthasTelnetPort;

    private static final ILog LOGGER = LogManager.getLogger(CommandListener.class);

    @Override
    public void statusChanged(final GRPCChannelStatus status) {
        if (GRPCChannelStatus.CONNECTED.equals(status)) {
            Object channel = ServiceManager.INSTANCE.findService(GRPCChannelManager.class).getChannel();
            // DO NOT REMOVE Channel CAST, or it will throw `incompatible types: org.apache.skywalking.apm.dependencies.io.grpc.Channel
            // cannot be converted to io.grpc.Channel` exception when compile due to agent core's shade of grpc dependencies.
            commandServiceBlockingStub = ArthasCommandServiceGrpc.newBlockingStub((Channel) channel);
        } else {
            commandServiceBlockingStub = null;
        }
        this.status = status;
    }

    @Override
    public void prepare() throws Throwable {
        if (StringUtil.isBlank(ArthasConfig.Plugin.Arthas.TUNNEL_SERVER)) {
            throw new IllegalArgumentException("arthas tunnel server can not be empty, please set by agent.config.");
        }
        ServiceManager.INSTANCE.findService(GRPCChannelManager.class).addChannelListener(this);
    }

    @Override
    public void boot() throws Throwable {
        getCommandFuture = Executors.newSingleThreadScheduledExecutor(
            new DefaultNamedThreadFactory("CommandListener")
        ).scheduleWithFixedDelay(
            new RunnableWithExceptionProtection(
                this::getCommand,
                t -> LOGGER.error("get arthas command error.", t)
            ), 0, 2, TimeUnit.SECONDS
        );
    }

    @Override
    public void onComplete() throws Throwable {

    }

    @Override
    public void shutdown() throws Throwable {
        if (getCommandFuture != null) {
            getCommandFuture.cancel(true);
        }
    }

    private void getCommand() {
        LOGGER.debug("CommandListener running, status:{}.", status);

        if (!GRPCChannelStatus.CONNECTED.equals(status) || commandServiceBlockingStub == null) {
            return;
        }

        CommandRequest.Builder builder = CommandRequest.newBuilder();
        builder.setServiceName(Config.Agent.SERVICE_NAME);
        builder.setInstanceName(Config.Agent.INSTANCE_NAME);

        final CommandResponse commandResponse = commandServiceBlockingStub.withDeadlineAfter(
            GRPC_UPSTREAM_TIMEOUT, TimeUnit.SECONDS).get(builder.build());

        switch (commandResponse.getCommand()) {
            case START:
                if (alreadyAttached()) {
                    LOGGER.warn("arthas already attached, no need start again");
                    return;
                }

                try {
                    arthasTelnetPort = SocketUtils.findAvailableTcpPort();
                    ArthasCtl.startArthas(PidUtils.currentLongPid(), arthasTelnetPort);
                } catch (Exception e) {
                    LOGGER.info("error when start arthas", e);
                }
                break;
            case STOP:
                if (!alreadyAttached()) {
                    LOGGER.warn("no arthas attached, no need to stop");
                    return;
                }

                try {
                    ArthasCtl.stopArthas(arthasTelnetPort);
                    arthasTelnetPort = null;
                } catch (Exception e) {
                    LOGGER.info("error when stop arthas", e);
                }
                break;
        }
    }

    private boolean alreadyAttached() {
        return arthasTelnetPort != null && !SocketUtils.isTcpPortAvailable(arthasTelnetPort);
    }
}
