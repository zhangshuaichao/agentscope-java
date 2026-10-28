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
package io.agentscope.extensions.model.openai.dto;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证构建器模式生成对象相互独立的测试。 */
class OpenAIBuilderIndependenceTest {

    @Test
    void testOpenAIMessageBuilderDoesNotReuseInstances() {
        OpenAIMessage.Builder builder = new OpenAIMessage.Builder();

        // 构建第一条消息
        OpenAIMessage msg1 = builder.role("user").content("Hello").build();
        // 用不同内容构建第二条消息
        OpenAIMessage msg2 = builder.role("assistant").content("Hi").build();

        // 验证两者是不同对象
        assertNotSame(msg1, msg2, "Builder should create new instances");
        // 验证两者具有不同值
        assertNotEquals(msg1.getRole(), msg2.getRole());
        assertNotEquals(msg1.getContent(), msg2.getContent());
    }

    @Test
    void testOpenAIRequestBuilderDoesNotReuseInstances() {
        OpenAIRequest.Builder builder = new OpenAIRequest.Builder();

        // 构建第一个请求
        OpenAIRequest req1 = builder.model("gpt-4").temperature(0.7).build();
        // 使用不同参数构建第二个请求
        OpenAIRequest req2 = builder.model("gpt-3.5-turbo").temperature(0.5).build();

        // 验证两者是不同对象
        assertNotSame(req1, req2, "Builder should create new instances");
        // 验证两者具有不同值
        assertNotEquals(req1.getModel(), req2.getModel());
        assertNotEquals(req1.getTemperature(), req2.getTemperature());
    }

    @Test
    void testOpenAIContentPartBuilderDoesNotReuseInstances() {
        OpenAIContentPart.Builder builder = new OpenAIContentPart.Builder();

        // 构建第一个内容部分
        OpenAIContentPart part1 = builder.type("text").text("First").build();
        // 构建第二个内容部分
        OpenAIContentPart part2 = builder.type("image").text("Second").build();

        // 验证两者是不同对象
        assertNotSame(part1, part2, "Builder should create new instances");
        // 验证两者具有不同值
        assertNotEquals(part1.getType(), part2.getType());
        assertNotEquals(part1.getText(), part2.getText());
    }

    @Test
    void testOpenAIToolCallBuilderDoesNotReuseInstances() {
        OpenAIToolCall.Builder builder = new OpenAIToolCall.Builder();

        // 构建第一个工具调用
        OpenAIToolCall call1 = builder.id("call-1").type("function").index(0).build();
        // 构建第二个工具调用
        OpenAIToolCall call2 = builder.id("call-2").type("function").index(1).build();

        // 验证两者是不同对象
        assertNotSame(call1, call2, "Builder should create new instances");
        // 验证两者具有不同值
        assertNotEquals(call1.getId(), call2.getId());
        assertNotEquals(call1.getIndex(), call2.getIndex());
    }

    @Test
    void testOpenAIToolFunctionBuilderDoesNotReuseInstances() {
        OpenAIToolFunction.Builder builder = new OpenAIToolFunction.Builder();

        // 构建第一个函数
        OpenAIToolFunction func1 = builder.name("search").description("Search tool").build();
        // 构建第二个函数
        OpenAIToolFunction func2 = builder.name("math").description("Math tool").build();

        // 验证两者是不同对象
        assertNotSame(func1, func2, "Builder should create new instances");
        // 验证两者具有不同值
        assertNotEquals(func1.getName(), func2.getName());
        assertNotEquals(func1.getDescription(), func2.getDescription());
    }

    @Test
    void testOpenAIMessageBuilderWithComplexState() {
        OpenAIMessage.Builder builder = new OpenAIMessage.Builder();
        List<OpenAIContentPart> parts1 = new ArrayList<>();
        parts1.add(new OpenAIContentPart());

        // 构建带内容列表的消息
        OpenAIMessage msg1 = builder.role("user").content(parts1).toolCallId("tc1").build();

        List<OpenAIContentPart> parts2 = new ArrayList<>();
        parts2.add(new OpenAIContentPart());
        parts2.add(new OpenAIContentPart());

        // 用不同内容列表构建另一条消息
        OpenAIMessage msg2 = builder.role("assistant").content(parts2).toolCallId("tc2").build();

        // 验证相互独立
        assertNotSame(msg1, msg2);
        assertNotEquals(msg1.getToolCallId(), msg2.getToolCallId());
    }

    @Test
    void testOpenAIRequestBuilderWithComplexState() {
        OpenAIRequest.Builder builder = new OpenAIRequest.Builder();
        List<OpenAIMessage> msgs1 = new ArrayList<>();
        msgs1.add(new OpenAIMessage());

        // 构建带消息的请求
        OpenAIRequest req1 = builder.model("gpt-4").messages(msgs1).build();

        List<OpenAIMessage> msgs2 = new ArrayList<>();
        msgs2.add(new OpenAIMessage());
        msgs2.add(new OpenAIMessage());

        // 用不同消息构建另一请求
        OpenAIRequest req2 = builder.model("gpt-3.5").messages(msgs2).build();

        // 验证相互独立
        assertNotSame(req1, req2);
        assertNotEquals(req1.getModel(), req2.getModel());
    }
}
