/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.extensions.higress;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 用于集成 Higress AI 网关的工具包扩展。
 * <p>扩展 {@link Toolkit}，在继承全部标准工具包功能的同时提供 Higress 专用能力。
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 创建 Higress MCP 客户端
 * HigressMcpClientWrapper client = HigressMcpClientBuilder
 *     .create("higress")
 *     .streamableHttpEndpoint("http://gateway/mcp-servers/union-tools-search")
 *     .build();
 *
 * // 注册到工具包
 * HigressToolkit toolkit = new HigressToolkit();
 * toolkit.registerMcpClient(client).block();
 *
 * // 或使用流式 API 配置更多选项
 * toolkit.registration()
 *     .mcpClient(client)
 *     .enableTools(List.of("tool1", "tool2"))
 *     .group("myGroup")
 *     .apply();
 * }</pre>
 * @see Toolkit
 * @see HigressMcpClientWrapper
 * @see HigressMcpClientBuilder
 */
public class HigressToolkit extends Toolkit {

    private static final Logger logger = LoggerFactory.getLogger(HigressToolkit.class);

    /** 已注册 Higress MCP 客户端的引用。 */
    private HigressMcpClientWrapper higressMcpClient;

    /** 使用默认配置创建 HigressToolkit。 */
    public HigressToolkit() {
        super();
    }

    /**
     * 使用自定义配置创建 HigressToolkit。
     * @param config 工具包配置
     */
    public HigressToolkit(ToolkitConfig config) {
        super(config);
    }

    /**
     * 向工具包注册 MCP 客户端。
     * <p>客户端为 {@link HigressMcpClientWrapper} 时会被缓存，
     * 供后续通过 {@link #getHigressMcpClient()} 访问。
     * <p>使用流式 API 配置高级注册选项（过滤、分组）：
     * <pre>{@code
     * toolkit.registration()
     *     .mcpClient(client)
     *     .enableTools(List.of("tool1"))
     *     .group("myGroup")
     *     .apply();
     * }</pre>
     * @param mcpClientWrapper 待注册的 MCP 客户端包装器
     * @return 注册结束时完成的 Mono
     */
    @Override
    public Mono<Void> registerMcpClient(McpClientWrapper mcpClientWrapper) {
        return super.registerMcpClient(mcpClientWrapper)
                .doOnSuccess(unused -> cacheHigressClient(mcpClientWrapper));
    }

    private void cacheHigressClient(McpClientWrapper mcpClientWrapper) {
        if (mcpClientWrapper instanceof HigressMcpClientWrapper higressClient) {
            this.higressMcpClient = higressClient;
            logger.info(
                    "Higress MCP client '{}' registered successfully", mcpClientWrapper.getName());
        }
    }

    /**
     * 获取已注册的 Higress MCP 客户端。
     * @return Higress MCP 客户端，未注册时为 null
     */
    public HigressMcpClientWrapper getHigressMcpClient() {
        return higressMcpClient;
    }
}
