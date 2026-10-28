/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.model.dashscope.tool;

import io.agentscope.core.message.ToolResultBlock;

/**
 * DashScope TTS 端到端测试。
 *
 * <p>用法：export DASHSCOPE_API_KEY=sk-xxx &amp;&amp; mvn exec:java -pl agentscope-core \
 * -Dexec.mainClass="io.agentscope.core.tool.multimodal.DashScopeTTSE2ETest"
 * <p>【学习提示】上方命令保留历史示例，模块与主类名已过时；当前类位于 DashScope 扩展测试源码中，
 * 运行前需按本文件 package 和测试类路径调整。该方法会调用真实服务，不属于已执行的本地静态验证。
 */
public class DashScopeTTSE2ETest {

    public static void main(String[] args) {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("Please set DASHSCOPE_API_KEY");
            return;
        }

        DashScopeMultiModalTool tool = new DashScopeMultiModalTool(apiKey);

        // 测试 Qwen TTS
        System.out.println("=== Testing Qwen TTS (qwen3-tts-flash) ===");
        String text = "你好，欢迎使用语音合成功能。";
        System.out.println("Text: " + text);

        ToolResultBlock result =
                tool.dashscopeTextToAudio(text, "qwen3-tts-flash", "Cherry", "Chinese", null)
                        .block();

        if (result.getOutput() != null && !result.getOutput().isEmpty()) {
            System.out.println("Result: SUCCESS");
            System.out.println("Audio generated successfully!");
        } else {
            System.out.println("Result: ERROR - No output");
        }

        System.out.println("\nDone!");
    }
}
