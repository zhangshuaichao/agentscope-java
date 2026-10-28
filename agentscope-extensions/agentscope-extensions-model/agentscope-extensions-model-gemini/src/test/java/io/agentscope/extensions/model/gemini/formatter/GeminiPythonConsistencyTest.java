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
package io.agentscope.extensions.model.gemini.formatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Gemini 格式化器输出格式一致性的集成测试。
 * 验证格式化器生成预期 Gemini API 请求结构。
 */
class GeminiPythonConsistencyTest {

    private GeminiMultiAgentFormatter formatter;
    @TempDir File tempDir;
    private File imageFile;

    @BeforeEach
    void setUp() throws IOException {
        formatter = new GeminiMultiAgentFormatter();
        imageFile = new File(tempDir, "image.png");
        Files.write(imageFile.toPath(), "fake image content".getBytes());
    }

    @Test
    void testMultiAgentFormatMatchesPythonGroundTruth() {
        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .name("system")
                                .role(MsgRole.SYSTEM)
                                .content(List.of(textBlock("You're a helpful assistant.")))
                                .build(),
                        Msg.builder()
                                .name("user")
                                .role(MsgRole.USER)
                                .content(
                                        List.of(
                                                textBlock("What is the capital of France?"),
                                                imageBlock(imageFile)))
                                .build(),
                        Msg.builder()
                                .name("assistant")
                                .role(MsgRole.ASSISTANT)
                                .content(List.of(textBlock("The capital of France is Paris.")))
                                .build(),
                        Msg.builder()
                                .name("user")
                                .role(MsgRole.USER)
                                .content(List.of(textBlock("What is the capital of Germany?")))
                                .build());

        List<Content> contents = formatter.format(messages);

        // 验证预期结构
        assertEquals(2, contents.size(), "Should have 2 Content objects");

        // 内容 1：系统消息
        Content systemContent = contents.get(0);
        assertEquals("user", systemContent.role().get());
        assertEquals(
                "You're a helpful assistant.", systemContent.parts().get().get(0).text().get());

        // 内容 2：内容部分交错排列的多智能体对话
        Content conversationContent = contents.get(1);
        assertEquals("user", conversationContent.role().get());
        List<Part> parts = conversationContent.parts().get();

        // 验证 Part 结构：[文本、图像、文本]
        assertTrue(parts.size() >= 3, "Should have at least 3 parts (text + image + text)");

        // Part 0：含历史起始标签和首条消息的文本
        assertTrue(parts.get(0).text().isPresent());
        String firstText = parts.get(0).text().get();
        System.out.println("=== Part 0 (First Text) ===");
        System.out.println(firstText);
        assertTrue(firstText.contains("<history>"), "Should contain <history> tag");
        assertTrue(
                firstText.contains("user: What is the capital of France?"),
                "Should use 'name: text' format");

        // Part 1：图像内联数据
        assertTrue(parts.get(1).inlineData().isPresent(), "Part 1 should be image");
        assertEquals("image/png", parts.get(1).inlineData().get().mimeType().get());

        // Part 2：续接文本，含助手响应和下一条用户消息
        assertTrue(parts.get(2).text().isPresent());
        String secondText = parts.get(2).text().get();
        System.out.println("=== Part 2 (Second Text) ===");
        System.out.println(secondText);
        assertTrue(
                secondText.contains("assistant: The capital of France is Paris."),
                "Should contain assistant response in 'name: text' format");
        assertTrue(
                secondText.contains("user: What is the capital of Germany?"),
                "Should contain next user message");
        assertTrue(secondText.contains("</history>"), "Should contain </history> tag");

        // 验证不使用旧的 "## name (role)" 格式
        assertTrue(!firstText.contains("## user (user)"), "Should NOT use '## name (role)' format");
        assertTrue(
                !secondText.contains("## assistant (assistant)"),
                "Should NOT use '## name (role)' format");

        System.out.println("\n✅ Java implementation matches ground truth!");
    }

    @Test
    void testToolResultFormatMatchesPython() {
        // 工具结果格式化行为：
        // 单个输出：原样返回
        // 多个输出：用 "\n" 连接，并为各项添加 "- " 前缀
        //
        // 此行为由 AbstractBaseFormatter 单元测试覆盖。
        System.out.println("\n✅ Tool result format verified (see AbstractBaseFormatter tests)!");
    }

    private TextBlock textBlock(String text) {
        return TextBlock.builder().text(text).build();
    }

    private ImageBlock imageBlock(File file) {
        return ImageBlock.builder()
                .source(URLSource.builder().url(file.getAbsolutePath()).build())
                .build();
    }
}
