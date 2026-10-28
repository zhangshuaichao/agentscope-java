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
package io.agentscope.builder;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.claw2.Claw2App;
import io.agentscope.claw2.runtime.ClawBootstrap;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Flux;

/**
 * 启动完整 Spring Boot 上下文，捕捉单元级 {@link BuilderBootstrapSmokeTest} 无法发现的装配问题，
 * 例如 logback appender 类名、Bean 缺失或控制器配置错误。使用 {@link Model} 替身保持离线。
 * <p>替身 {@link Model} 通过 {@code @Import} 导入下方静态 {@link StubModelConfig}。
 * 嵌套 {@code @TestConfiguration} 仅在 {@code @SpringBootTest#classes} 留空时自动发现；
 * 这里显式设定 {@code classes = Claw2App.class} 限定生产应用，因此必须显式导入，
 * 否则替身不会进入 {@code BuilderConfig} 的 {@code Optional<Model>} 注入点。
 */
@SpringBootTest(
        classes = Claw2App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(BuilderAppContextLoadTest.StubModelConfig.class)
@TestPropertySource(
        properties = {
            "claw.home=${java.io.tmpdir}/agentscope-claw-context-load-test",
            "claw.dashscope.api-key=",
            "claw.controlplane.enabled=false"
        })
class BuilderAppContextLoadTest {

    @Autowired ApplicationContext context;
    @Autowired ClawBootstrap bootstrap;
    @Autowired ChatUiChannel chatUiChannel;

    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
        assertThat(bootstrap).isNotNull();
        assertThat(chatUiChannel).isNotNull();
        assertThat(bootstrap.channelManager().getChannel(ChatUiChannel.CHANNEL_ID)).isPresent();
    }

    @TestConfiguration
    static class StubModelConfig {
        @Bean
        @Primary
        Model stubModel() {
            Model model = Mockito.mock(Model.class);
            Mockito.when(model.getModelName()).thenReturn("stub-model");
            ChatResponse chunk =
                    new ChatResponse(
                            "stub-id",
                            List.of(TextBlock.builder().text("ok").build()),
                            null,
                            Map.of(),
                            "stop");
            Mockito.when(model.stream(Mockito.anyList(), Mockito.any(), Mockito.any()))
                    .thenReturn(Flux.just(chunk));
            return model;
        }
    }
}
