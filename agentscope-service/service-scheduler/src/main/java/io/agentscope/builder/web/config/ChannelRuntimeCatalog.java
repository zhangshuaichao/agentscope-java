/*
 * Copyright 2024-2026 the original author or authors.
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
package io.agentscope.builder.web.config;

import io.agentscope.builder.runtime.config.ChannelConfigEntry;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 最近从控制平面获取的通道配置内存快照。{@link io.agentscope.builder.runtime.SchedulerGateway} 将入站轮次桥接到托管会话时，据此解析即时消息通道的 Builder 所有者。
 */
@Component
public class ChannelRuntimeCatalog {

    private final ConcurrentHashMap<String, ChannelConfigEntry> entries = new ConcurrentHashMap<>();

    /**
     * 使用 {@code configs} 替换目录，安全处理 null。
     */
    public void replaceAll(Map<String, ChannelConfigEntry> configs) {
        entries.clear();
        if (configs != null) {
            entries.putAll(configs);
        }
    }

    /**
     * 返回 {@code channelId} 对应配置，不存在时为 {@code null}。
     */
    public ChannelConfigEntry get(String channelId) {
        if (channelId == null) {
            return null;
        }
        return entries.get(channelId);
    }

    /**
     * 通道的 Builder 所有者 ID，未知时为 {@code null}。
     */
    public String ownerId(String channelId) {
        ChannelConfigEntry e = get(channelId);
        return e != null ? e.getOwnerId() : null;
    }

    /**
     * 通道的 DmScope 字符串；null／空白视为 MAIN。
     */
    public String dmScope(String channelId) {
        ChannelConfigEntry e = get(channelId);
        return e != null ? e.getDmScope() : null;
    }

    /**
     * 当前目录的不可修改视图。
     */
    public Map<String, ChannelConfigEntry> snapshot() {
        return Collections.unmodifiableMap(Map.copyOf(entries));
    }
}
