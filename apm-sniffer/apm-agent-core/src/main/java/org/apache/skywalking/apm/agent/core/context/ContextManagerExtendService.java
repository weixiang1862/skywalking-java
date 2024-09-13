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

package org.apache.skywalking.apm.agent.core.context;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.skywalking.apm.agent.core.boot.BootService;
import org.apache.skywalking.apm.agent.core.boot.DefaultImplementor;
import org.apache.skywalking.apm.agent.core.boot.ServiceManager;
import org.apache.skywalking.apm.agent.core.conf.Config;
import org.apache.skywalking.apm.agent.core.conf.dynamic.ConfigurationDiscoveryService;
import org.apache.skywalking.apm.agent.core.conf.dynamic.watcher.IgnoreSuffixPatternsWatcher;
import org.apache.skywalking.apm.agent.core.conf.dynamic.watcher.SpanLimitWatcher;
import org.apache.skywalking.apm.agent.core.remote.GRPCChannelListener;
import org.apache.skywalking.apm.agent.core.remote.GRPCChannelManager;
import org.apache.skywalking.apm.agent.core.remote.GRPCChannelStatus;
import org.apache.skywalking.apm.agent.core.sampling.SamplingService;
import org.apache.skywalking.apm.agent.core.so11y.AgentSO11Y;
import org.apache.skywalking.apm.util.StringUtil;

@DefaultImplementor
public class ContextManagerExtendService implements BootService, GRPCChannelListener {

    private volatile Set ignoreSuffixSet;

    private volatile GRPCChannelStatus status = GRPCChannelStatus.DISCONNECT;

    private IgnoreSuffixPatternsWatcher ignoreSuffixPatternsWatcher;

    private SpanLimitWatcher spanLimitWatcher;

    @Override
    public void prepare() {
        ServiceManager.INSTANCE.findService(GRPCChannelManager.class).addChannelListener(this);
    }

    @Override
    public void boot() {
        ignoreSuffixSet = Stream.of(Config.Agent.IGNORE_SUFFIX.split(",")).collect(Collectors.toSet());
        ignoreSuffixPatternsWatcher = new IgnoreSuffixPatternsWatcher("agent.ignore_suffix", this);
        spanLimitWatcher = new SpanLimitWatcher("agent.span_limit_per_segment");

        ConfigurationDiscoveryService configurationDiscoveryService = ServiceManager.INSTANCE.findService(
            ConfigurationDiscoveryService.class);
        configurationDiscoveryService.registerAgentConfigChangeWatcher(spanLimitWatcher);
        configurationDiscoveryService.registerAgentConfigChangeWatcher(ignoreSuffixPatternsWatcher);

        handleIgnoreSuffixPatternsChanged();
    }

    @Override
    public void onComplete() {

    }

    @Override
    public void shutdown() {

    }

    public AbstractTracerContext createTraceContext(String operationName, boolean forceSampling) {
        AbstractTracerContext context;
        /*
         * Don't trace anything if the backend is not available.
         */
        if (!Config.Agent.KEEP_TRACING && GRPCChannelStatus.DISCONNECT.equals(status)) {
            AgentSO11Y.recordTracingContextCreate(forceSampling, true);
            return new IgnoredTracerContext();
        }

        int suffixIdx = operationName.lastIndexOf(".");
        if (suffixIdx > -1 && ignoreSuffixSet.contains(operationName.substring(suffixIdx))) {
            AgentSO11Y.recordTracingContextCreate(forceSampling, true);
            context = new IgnoredTracerContext();
        } else {
            SamplingService samplingService = ServiceManager.INSTANCE.findService(SamplingService.class);
            if (forceSampling || samplingService.trySampling(operationName)) {
                AgentSO11Y.recordTracingContextCreate(forceSampling, false);
                context = new TracingContext(operationName, spanLimitWatcher);
            } else {
                AgentSO11Y.recordTracingContextCreate(false, true);
                AgentSO11Y.recordLeakedTracingContext(true);
                context = new IgnoredTracerContext();
            }
        }

        return context;
    }

    @Override
    public void statusChanged(final GRPCChannelStatus status) {
        this.status = status;
    }

    public void handleIgnoreSuffixPatternsChanged() {
        if (StringUtil.isNotBlank(ignoreSuffixPatternsWatcher.getIgnoreSuffixPatterns())) {
            ignoreSuffixSet = Stream.of(ignoreSuffixPatternsWatcher.getIgnoreSuffixPatterns().split(",")).collect(Collectors.toSet());
        }
    }
}
