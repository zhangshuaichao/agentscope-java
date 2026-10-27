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
package io.agentscope.harness.agent.tools;

/** 接收 MCP 服务器注册尝试的终态结果。 */
@FunctionalInterface
public interface McpServerRegistrationListener {

    /**
     * 在 MCP 服务器配置项注册成功、注册失败或被跳过后调用一次。
     *
     * @param result 注册终态结果
     */
    void onCompleted(McpServerRegistrationResult result);
}
