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
package io.agentscope.core.tool;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;

/**
 * 工具调用生命周期通知回调。
 *
 * <p>所有方法默认不执行操作，可按需覆写。
 */
public interface ToolExecutionCallback {
    /**
     * 通知工具调用即将开始：在执行器开始调用（轮到此调用执行）时触发，而非批次被接纳时。
     *
     * @param call 工具调用
     */
    default void onStart(ToolUseBlock call) {}

    /**
     * 报告中间进度。一次工具调用可产生多次进度通知。
     *
     * @param call 工具调用
     * @param progress 进度内容
     */
    default void onProgress(ToolUseBlock call, ToolResultBlock progress) {}

    /**
     * 报告工具调用结果，可能为成功、错误或挂起。每次调用最多通知一次，结果解析完成后立即通知，早于按序批次聚合。
     *
     * @param call 工具调用
     * @param result 调用结果
     */
    default void onResult(ToolUseBlock call, ToolResultBlock result) {}
}
