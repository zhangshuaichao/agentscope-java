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
package io.agentscope.examples.documentation2.model;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ModelRegistry;

/**
 * ModelRegistryExample：通过模型 ID 字符串创建智能体。
 * <p>加载对应扩展提供方后，传给 {@link ReActAgent.Builder#model(String)} 的 {@code "provider:model-name"} 由框架工厂解析，无需手工创建模型。下方保留原提供方／环境变量对照及运行命令；模型可用性以实际提供方为准。
 * <pre>
 *   "openai:gpt-4o"               OPENAI_API_KEY
 *   "dashscope:qwen-max"          DASHSCOPE_API_KEY
 *   "qwen-plus"                   DASHSCOPE_API_KEY   (qwen-* shorthand, no prefix needed)
 *   "anthropic:claude-opus-4-5"   ANTHROPIC_API_KEY
 *   "gemini:gemini-2.0-flash"     GEMINI_API_KEY
 *   "ollama:llama3"               OLLAMA_BASE_URL     (default: http://localhost:11434)
 * </pre>
 * <p>对照表中文说明：各模型 ID 右侧为所需环境变量；qwen-* 可使用无提供方前缀的简写。Ollama 的 OLLAMA_BASE_URL 缺省地址为 http://localhost:11434。模型名称与密钥变量保留原样，能否调用还取决于已安装提供方及账号权限。
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.model.ModelRegistryExample
 * </pre>
 */
public class ModelRegistryExample {

    /**
     * 运行模型字符串解析演示。
     * @param args 命令行参数，此处忽略
     */
    public static void main(String[] args) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("ModelRegistry Example");
        System.out.println("=".repeat(60));
        System.out.println(
                "Shows how to specify a model with a plain string — no manual construction"
                        + " needed.");
        System.out.println("=".repeat(60) + "\n");

        // ── 1. 检查可用提供方 ──
        //
        // ModelRegistry.canResolve() 只探测注册表，不实际创建模型。
        // 可在启动时给出清晰的早期错误。
        System.out.println(
                "Available provider extensions (requires corresponding module and env var):");
        System.out.println(
                "  openai:gpt-4o               → " + ModelRegistry.canResolve("openai:gpt-4o"));
        System.out.println(
                "  dashscope:qwen-max           → "
                        + ModelRegistry.canResolve("dashscope:qwen-max"));
        System.out.println(
                "  qwen-plus  (shorthand)       → " + ModelRegistry.canResolve("qwen-plus"));
        System.out.println(
                "  anthropic:claude-opus-4-5    → "
                        + ModelRegistry.canResolve("anthropic:claude-opus-4-5"));
        System.out.println(
                "  gemini:gemini-2.0-flash      → "
                        + ModelRegistry.canResolve("gemini:gemini-2.0-flash"));
        System.out.println(
                "  ollama:llama3                → " + ModelRegistry.canResolve("ollama:llama3"));
        System.out.println();

        // ── 2. 仅用模型 ID 字符串创建智能体 ──
        //
        // ReActAgent.Builder.model(String) 内部调用 ModelRegistry.resolve()。
        // 框架读取环境变量 DASHSCOPE_API_KEY 并组装
        // DashScope 模型，无需手工调用 DashScopeChatModel.builder()。
        System.out.println("Building agent with model string \"qwen-plus\" ...");
        ReActAgent agent =
                ReActAgent.builder()
                        .name("ModelStringDemo")
                        .sysPrompt("You are a concise assistant. Reply in one sentence.")
                        .model("dashscope:qwen-plus") // ← 此处即模型配置入口
                        // 模型由注册表解析
                        .build();

        Msg response = agent.call(new UserMessage("user", "What is 2 + 2?")).block();
        System.out.println("Agent: " + (response != null ? response.getTextContent() : "(null)"));
        System.out.println();

        // ── 3. 修改字符串以切换提供方 ──
        //
        // 提供方前缀选择后端；切换时修改模型 ID 即可，
        // 但仍须提供对应依赖、凭据并确认模型功能兼容。
        //
        // 取消下列某行注释，可尝试其他提供方：
        //
        //   .model("openai:gpt-4o")              // 需要 OPENAI_API_KEY
        //   .model("dashscope:qwen-max")          // 显式 DashScope 前缀
        //   .model("anthropic:claude-opus-4-5")   // 需要 ANTHROPIC_API_KEY
        //   .model("gemini:gemini-2.0-flash")     // 需要 GEMINI_API_KEY
        //   .model("ollama:llama3")               // 需要本地 Ollama 实例
        System.out.println(
                "To switch providers, change the model-ID string — no other code changes needed.");
    }
}
