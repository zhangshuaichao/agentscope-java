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
package io.agentscope.core.e2e.providers;

/** E2E 测试模型能力枚举，按支持的功能分类提供方，以便针对测试场景动态过滤。 */
public enum ModelCapability {

    /** 基本对话能力，测试矩阵中的所有模型均应支持。 */
    BASIC,

    /** 工具调用（函数调用）能力。 */
    TOOL_CALLING,

    /** 图片理解能力。 */
    IMAGE,

    /** 音频理解能力。 */
    AUDIO,

    /** 视频理解能力。 */
    VIDEO,

    /** 思考模式（扩展推理）能力。 */
    THINKING,

    /** 思考预算控制能力。 */
    THINKING_BUDGET,

    /** 供 MsgHub 对话使用的多智能体格式化器支持。 */
    MULTI_AGENT_FORMATTER
}
