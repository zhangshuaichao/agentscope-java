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
package io.agentscope.extensions.channel.github;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程级 {@code channelId} 到 {@link GitHubChannel} 的查找表，
 * 供 {@link GitHubWebhookController} 将 URL 路由请求分派到对应渠道。
 */
public final class GitHubChannelRegistry {

    private static final GitHubChannelRegistry INSTANCE = new GitHubChannelRegistry();

    private final ConcurrentHashMap<String, GitHubChannel> channels = new ConcurrentHashMap<>();

    private GitHubChannelRegistry() {}

    /**
     * 返回进程级单例。
     */
    public static GitHubChannelRegistry instance() {
        return INSTANCE;
    }

    public void register(GitHubChannel channel) {
        channels.put(channel.channelId(), channel);
    }

    public void unregister(String channelId) {
        channels.remove(channelId);
    }

    public GitHubChannel get(String channelId) {
        return channels.get(channelId);
    }
}
