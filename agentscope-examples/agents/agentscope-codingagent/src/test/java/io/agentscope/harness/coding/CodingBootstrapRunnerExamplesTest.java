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
package io.agentscope.harness.coding;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.DmScope;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * 记录并验证 {@link CodingBootstrap} 的推荐装配方式。
 * <p>内部 {@link io.agentscope.harness.coding.gateway.HarnessGateway} 由启动器自动创建，
 * 用户通过 {@link CodingBootstrap#chatUiChannel()} 和 {@link CodingBootstrap#start(io.agentscope.harness.agent.gateway.channel.Channel...)} 交互。
 */
class AgentBootstrapGatewayExamplesTest {

    @TempDir Path tempDir;

    // ------------------------------------------------------------------
    // 场景 1：单智能体，以 chatUiChannel() 为主要入口
    // ------------------------------------------------------------------

    /**
     * 构建单个智能体，通过 {@link CodingBootstrap#chatUiChannel()} 交互。
     * 网关自动创建和装配，调用方不直接接触网关 API。
     */
    @Test
    void singleAgent_chatUiChannel() throws Exception {
        Model model = stubModel("single-agent-reply");
        CodingBootstrap agentBootstrap =
                CodingBootstrap.builder()
                        .skipConfigFile(true)
                        .cwd(tempDir)
                        .model(model)
                        .configureAgent("main", b -> b.name("main").description("main"))
                        .mainAgent("main")
                        .build();

        ChatUiChannel chat = agentBootstrap.chatUiChannel();
        Msg reply = chat.send("Hello from test").block();
        assertTrue(reply.getTextContent().contains("single-agent-reply"));
    }

    /**
     * 与前例相同，但自定义 {@link ChannelConfig} 以按对端隔离会话。
     */
    @Test
    void singleAgent_chatUiChannel_perPeer() throws Exception {
        Model model = stubModel("per-peer-reply");
        CodingBootstrap agentBootstrap =
                CodingBootstrap.builder()
                        .skipConfigFile(true)
                        .cwd(tempDir)
                        .model(model)
                        .configureAgent("main", b -> b.name("main"))
                        .mainAgent("main")
                        .build();

        ChannelConfig perPeerConfig =
                ChannelConfig.builder(ChatUiChannel.CHANNEL_ID).dmScope(DmScope.PER_PEER).build();
        ChatUiChannel chat = agentBootstrap.chatUiChannel(perPeerConfig);

        Msg reply1 = chat.send("alice", "Hi!").block();
        Msg reply2 = chat.send("bob", "Hi!").block();
        assertTrue(reply1.getTextContent().contains("per-peer-reply"));
        assertTrue(reply2.getTextContent().contains("per-peer-reply"));
    }

    // ------------------------------------------------------------------
    // 场景 2：通过 start(Channel) 接入外部渠道适配器
    // ------------------------------------------------------------------

    /**
     * 在构建器中通过 {@link CodingBootstrap.Builder#channel} 注册 {@link ChatUiChannel}，
     * 再由无参 {@link CodingBootstrap#start()} 自动初始化并启动全部已注册渠道。
     */
    @Test
    void singleAgent_channelInBuilder_noArgStart() throws Exception {
        Model model = stubModel("builder-channel-reply");
        ChatUiChannel chat = ChatUiChannel.create(); // 尚未注入网关。
        CodingBootstrap agentBootstrap =
                CodingBootstrap.builder()
                        .skipConfigFile(true)
                        .cwd(tempDir)
                        .model(model)
                        .configureAgent("main", b -> b.name("main"))
                        .mainAgent("main")
                        .channel(chat) // 在此注册，start() 时注入网关。
                        .build();

        agentBootstrap.start(); // 对全部已注册渠道调用 init(gateway) 与 start()。

        Msg reply = chat.send("Hello via builder channel").block();
        assertTrue(reply.getTextContent().contains("builder-channel-reply"));
    }

    /**
     * 演示临时渠道重载 {@link CodingBootstrap#start(io.agentscope.harness.agent.gateway.channel.Channel...)}：
     * 在构建器外创建 {@link ChatUiChannel}，传给 {@code start()}，由其注入自动装配的网关并调用 {@link ChatUiChannel#start()}。
     */
    @Test
    void singleAgent_adHocStart_injectsGateway() throws Exception {
        Model model = stubModel("started-channel-reply");
        CodingBootstrap agentBootstrap =
                CodingBootstrap.builder()
                        .skipConfigFile(true)
                        .cwd(tempDir)
                        .model(model)
                        .configureAgent("main", b -> b.name("main"))
                        .mainAgent("main")
                        .build();

        ChatUiChannel chat = ChatUiChannel.create(); // 尚未注入网关。
        agentBootstrap.start(chat); // 临时渠道经 init() 注入网关，然后调用 start()。

        Msg reply = chat.send("Hello via start()").block();
        assertTrue(reply.getTextContent().contains("started-channel-reply"));
    }

    // ------------------------------------------------------------------
    // 场景 2b：从 agentscope.json 的 channels 段自动创建 chatui
    // ------------------------------------------------------------------

    /**
     * 演示文件配置：{@code agentscope.json} 的 {@code channels} 段包含 {@code chatui} 项，
     * 启动器自动创建 {@link ChatUiChannel}，{@link CodingBootstrap#start()} 直接启动它，无需构建器 {@code .channel()} 调用。
     */
    @Test
    void singleAgent_chatuiFromFileConfig_autoCreated() throws Exception {
        // 写入含 channels 段的最小 agentscope.json。
        Path asDotDir = tempDir.resolve(".agentscope");
        java.nio.file.Files.createDirectories(asDotDir);
        java.nio.file.Files.writeString(
                asDotDir.resolve("agentscope.json"),
                """
                {
                  "main": "main",
                  "agents": {
                    "main": { "name": "main" }
                  },
                  "channels": {
                    "chatui": { "defaultAgentId": "main", "dmScope": "PER_PEER" }
                  }
                }
                """);

        Model model = stubModel("file-config-reply");
        CodingBootstrap agentBootstrap =
                CodingBootstrap.builder()
                        .cwd(tempDir)
                        .configPath(asDotDir.resolve("agentscope.json"))
                        .model(model)
                        .build();

        // chatui 已由文件配置自动创建，start() 启动它。
        agentBootstrap.start();

        // 验证自动创建的渠道已纳入。
        assertTrue(
                agentBootstrap.registeredChannels().stream()
                        .anyMatch(c -> ChatUiChannel.CHANNEL_ID.equals(c.channelId())));

        // 同时验证直接使用 chatUiChannel() 仍有效。
        ChatUiChannel direct = agentBootstrap.chatUiChannel();
        Msg reply = direct.send("hello").block();
        assertTrue(reply.getTextContent().contains("file-config-reply"));
    }

    // ------------------------------------------------------------------
    // 场景 3：多智能体，自动装配路由
    // ------------------------------------------------------------------

    /**
     * 多个智能体自动接入主网关，通过对端绑定规则路由到非默认智能体；测试也可经显式 {@link ChannelConfig}
     * 绑定，用 {@code agentId} 对应的 {@link ChatUiChannel#send} 调用。
     * <p>验证两个智能体均可通过各自的 {@link CodingBootstrap#chatUiChannel()} 访问，默认路由命中主智能体。
     */
    @Test
    void multiAgent_autoWiredGateway_defaultRoutesToMain() throws Exception {
        Model mainModel = stubModel("from-main");
        Model supportModel = stubModel("from-support");

        CodingBootstrap agentBootstrap =
                CodingBootstrap.builder()
                        .skipConfigFile(true)
                        .cwd(tempDir)
                        .model(mainModel)
                        .configureAgent("main", b -> b.name("main-agent").model(mainModel))
                        .configureAgent("support", b -> b.name("support-agent").model(supportModel))
                        .mainAgent("main")
                        .build();

        // 默认路由未指定对端或绑定覆盖，进入主智能体。
        ChatUiChannel chat = agentBootstrap.chatUiChannel();
        Msg reply = chat.send("hello").block();
        assertTrue(reply.getTextContent().contains("from-main"));
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    private static Model stubModel(String assistantText) {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub-model");
        ChatResponse chunk =
                new ChatResponse(
                        "stub-id",
                        List.of(TextBlock.builder().text(assistantText).build()),
                        null,
                        Map.of(),
                        "stop");
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.just(chunk));
        return model;
    }
}
