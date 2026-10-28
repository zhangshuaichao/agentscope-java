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
package io.agentscope.extensions.higress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.tool.ToolkitConfig;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class HigressToolkitTest {

    @Test
    void testDefaultConstructor() {
        HigressToolkit toolkit = new HigressToolkit();
        assertNotNull(toolkit);
        assertNull(toolkit.getHigressMcpClient());
    }

    @Test
    void testConstructorWithConfig() {
        ToolkitConfig config = ToolkitConfig.builder().build();
        HigressToolkit toolkit = new HigressToolkit(config);
        assertNotNull(toolkit);
    }

    @Test
    void testRegisterMcpClient_HigressClient() {
        HigressToolkit toolkit = new HigressToolkit();

        // 创建 HigressMcpClientWrapper 模拟对象
        McpClientWrapper mockDelegateClient = mock(McpClientWrapper.class);
        when(mockDelegateClient.initialize()).thenReturn(Mono.empty());
        when(mockDelegateClient.listTools()).thenReturn(Mono.just(Collections.emptyList()));
        when(mockDelegateClient.isInitialized()).thenReturn(true);

        HigressMcpClientWrapper higressClient =
                new HigressMcpClientWrapper("higress-client", mockDelegateClient, false, null, 10);

        // 注册
        toolkit.registerMcpClient(higressClient).block();

        // 验证已缓存
        HigressMcpClientWrapper cachedClient = toolkit.getHigressMcpClient();
        assertNotNull(cachedClient);
        assertEquals("higress-client", cachedClient.getName());
    }

    @Test
    void testRegisterMcpClient_NonHigressClient() {
        HigressToolkit toolkit = new HigressToolkit();

        // 创建普通 McpClientWrapper 模拟对象，非 Higress
        McpClientWrapper mockClient = mock(McpClientWrapper.class);
        when(mockClient.getName()).thenReturn("regular-client");
        when(mockClient.initialize()).thenReturn(Mono.empty());
        when(mockClient.listTools()).thenReturn(Mono.just(Collections.emptyList()));
        when(mockClient.isInitialized()).thenReturn(true);

        // 注册
        toolkit.registerMcpClient(mockClient).block();

        // Higress 客户端仍应为 null
        assertNull(toolkit.getHigressMcpClient());
    }

    @Test
    void testRegisterMcpClient_WithTools() {
        HigressToolkit toolkit = new HigressToolkit();

        McpClientWrapper mockDelegateClient = mock(McpClientWrapper.class);
        McpSchema.Tool tool =
                McpSchema.Tool.builder("test_tool", Map.of("type", "object"))
                        .description("Description")
                        .build();
        when(mockDelegateClient.initialize()).thenReturn(Mono.empty());
        when(mockDelegateClient.listTools()).thenReturn(Mono.just(List.of(tool)));
        when(mockDelegateClient.isInitialized()).thenReturn(true);

        HigressMcpClientWrapper higressClient =
                new HigressMcpClientWrapper("higress-client", mockDelegateClient, false, null, 10);

        toolkit.registerMcpClient(higressClient).block();

        // 验证工具已注册到工具集
        assertNotNull(toolkit.getToolNames());
        assertEquals(1, toolkit.getToolNames().size());
        assertNotNull(toolkit.getTool("test_tool"));
    }

    @Test
    void testGetHigressMcpClient_NotRegistered() {
        HigressToolkit toolkit = new HigressToolkit();
        assertNull(toolkit.getHigressMcpClient());
    }

    @Test
    void testMultipleRegistrations_LastHigressClientCached() {
        HigressToolkit toolkit = new HigressToolkit();

        // 第一个 Higress 客户端
        McpClientWrapper mockDelegate1 = mock(McpClientWrapper.class);
        when(mockDelegate1.initialize()).thenReturn(Mono.empty());
        when(mockDelegate1.listTools()).thenReturn(Mono.just(Collections.emptyList()));
        when(mockDelegate1.isInitialized()).thenReturn(true);
        HigressMcpClientWrapper client1 =
                new HigressMcpClientWrapper("client1", mockDelegate1, false, null, 10);

        // 第二个 Higress 客户端
        McpClientWrapper mockDelegate2 = mock(McpClientWrapper.class);
        when(mockDelegate2.initialize()).thenReturn(Mono.empty());
        when(mockDelegate2.listTools()).thenReturn(Mono.just(Collections.emptyList()));
        when(mockDelegate2.isInitialized()).thenReturn(true);
        HigressMcpClientWrapper client2 =
                new HigressMcpClientWrapper("client2", mockDelegate2, false, null, 10);

        toolkit.registerMcpClient(client1).block();
        toolkit.registerMcpClient(client2).block();

        // 应缓存最后注册者
        assertEquals("client2", toolkit.getHigressMcpClient().getName());
    }

    @Test
    void testInheritedToolkitFunctionality() {
        HigressToolkit toolkit = new HigressToolkit();

        // 测试继承的方法正常工作
        assertNotNull(toolkit.getToolNames());
        assertTrue(toolkit.getToolNames().isEmpty());
    }
}
