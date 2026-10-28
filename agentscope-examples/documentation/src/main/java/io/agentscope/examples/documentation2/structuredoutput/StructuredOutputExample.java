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
package io.agentscope.examples.documentation2.structuredoutput;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import java.util.List;
import reactor.core.publisher.Flux;

/**
 * StructuredOutputExample：演示结构化输出。迁移时移除 {@code .memory(new InMemoryMemory())}，2.0 不再需要。
 */
public class StructuredOutputExample {

    /**
     * 运行结构化输出示例。
     * @param args 命令行参数，此处忽略
     * @throws Exception I/O 错误时
     */
    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Structured Output Example");
        System.out.println("=".repeat(60));
        System.out.println(
                "This example demonstrates how to generate structured output from agents.\n"
                        + "The agent will analyze user queries and return structured data.");
        System.out.println("=".repeat(60) + "\n");

        String apiKey = System.getenv("DASHSCOPE_API_KEY");

        ReActAgent agent =
                ReActAgent.builder()
                        .name("AnalysisAgent")
                        .sysPrompt(
                                "You are an intelligent analysis assistant. "
                                        + "Analyze user requests and provide structured responses.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-max")
                                        .stream(true)
                                        .enableThinking(false)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .toolkit(new Toolkit())
                        .build();

        System.out.println("=== Example 1: Product Information ===\n");
        runProductAnalysisExample(agent);

        System.out.println("\n=== Example 2: Contact Information ===\n");
        runContactExtractionExample(agent);

        System.out.println("\n=== Example 3: Sentiment Analysis ===\n");
        runSentimentAnalysisExample(agent);

        System.out.println("\n=== Example 4: Streaming Structured Output ===\n");
        runStreamProductAnalysisExample(agent);

        System.out.println("\n=== All examples completed ===");
    }

    private static void runProductAnalysisExample(ReActAgent agent) {
        String query =
                "I'm looking for a laptop. I need at least 16GB RAM, "
                        + "prefer Apple brand, and my budget is around $2000. "
                        + "It should be lightweight for travel.";

        System.out.println("Query: " + query);
        System.out.println("\nRequesting structured output...\n");

        Msg userMsg = new UserMessage("Extract the product requirements from this query: " + query);

        try {
            Msg msg = agent.call(userMsg, ProductRequirements.class).block();
            ProductRequirements result = msg.getStructuredData(ProductRequirements.class);

            System.out.println("Extracted structured data:");
            System.out.println("  Product Type: " + result.productType);
            System.out.println("  Brand: " + result.brand);
            System.out.println("  Min RAM: " + result.minRam + " GB");
            System.out.println("  Max Budget: $" + result.maxBudget);
            System.out.println("  Features: " + result.features);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void runContactExtractionExample(ReActAgent agent) {
        String query =
                "Please contact John Smith at john.smith@example.com or "
                        + "call him at +1-555-123-4567. His company is TechCorp Inc.";

        System.out.println("Text: " + query);
        System.out.println("\nExtracting contact information...\n");

        Msg userMsg = new UserMessage("Extract contact information from: " + query);

        try {
            Msg msg = agent.call(userMsg, ContactInfo.class).block();
            ContactInfo result = msg.getStructuredData(ContactInfo.class);

            System.out.println("Extracted contact information:");
            System.out.println("  Name: " + result.name);
            System.out.println("  Email: " + result.email);
            System.out.println("  Phone: " + result.phone);
            System.out.println("  Company: " + result.company);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void runSentimentAnalysisExample(ReActAgent agent) {
        String review =
                "This product exceeded my expectations! The quality is amazing "
                        + "and the customer service was very helpful. However, "
                        + "the shipping took a bit longer than expected.";

        System.out.println("Review: " + review);
        System.out.println("\nAnalyzing sentiment...\n");

        Msg userMsg =
                new UserMessage(
                        "Analyze the sentiment of this review and provide scores: " + review);

        try {
            Msg msg = agent.call(userMsg, SentimentAnalysis.class).block();
            SentimentAnalysis result = msg.getStructuredData(SentimentAnalysis.class);

            System.out.println("Sentiment analysis results:");
            System.out.println("  Overall Sentiment: " + result.overallSentiment);
            System.out.println("  Positive Score: " + result.positiveScore);
            System.out.println("  Negative Score: " + result.negativeScore);
            System.out.println("  Neutral Score: " + result.neutralScore);
            System.out.println("  Key Topics: " + result.keyTopics);
            System.out.println("  Summary: " + result.summary);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void runStreamProductAnalysisExample(ReActAgent agent) {
        String query =
                "I'm looking for a laptop. I need at least 16GB RAM, "
                        + "prefer Apple brand, and my budget is around $2000. "
                        + "It should be lightweight for travel.";

        System.out.println("Query: " + query);
        System.out.println("\nRequesting structured output via stream...\n");

        Msg userMsg = new UserMessage("Extract the product requirements from this query: " + query);

        try {
            Flux<Event> eventFlux =
                    agent.stream(userMsg, StreamOptions.defaults(), ProductRequirements.class);
            ProductRequirements result =
                    eventFlux.blockLast().getMessage().getStructuredData(ProductRequirements.class);

            System.out.println("Extracted structured data:");
            System.out.println("  Product Type: " + result.productType);
            System.out.println("  Brand: " + result.brand);
            System.out.println("  Min RAM: " + result.minRam + " GB");
            System.out.println("  Max Budget: $" + result.maxBudget);
            System.out.println("  Features: " + result.features);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ==================== 模式类 ====================

    /**
     * 产品需求提取模式。
     */
    public static class ProductRequirements {
        /**
         * 产品类型。
         */
        public String productType;

        /**
         * 品牌偏好。
         */
        public String brand;

        /**
         * 最低内存容量，单位 GB。
         */
        public Integer minRam;

        /**
         * 最高预算，单位美元。
         */
        public Double maxBudget;

        /**
         * 期望功能列表。
         */
        public List<String> features;

        /**
         * 默认构造函数。
         */
        public ProductRequirements() {}
    }

    /**
     * 联系人信息提取模式。
     */
    public static class ContactInfo {
        /**
         * 全名。
         */
        public String name;

        /**
         * 电子邮件地址。
         */
        public String email;

        /**
         * 电话号码。
         */
        public String phone;

        /**
         * 公司名称。
         */
        public String company;

        /**
         * 默认构造函数。
         */
        public ContactInfo() {}
    }

    /**
     * 情感分析结果模式。
     */
    public static class SentimentAnalysis {
        /**
         * 总体情感："positive"、"negative" 或 "neutral"。
         */
        public String overallSentiment;

        /**
         * 正向分数，范围 [0.0, 1.0]。
         */
        public Double positiveScore;

        /**
         * 负向分数，范围 [0.0, 1.0]。
         */
        public Double negativeScore;

        /**
         * 中性分数，范围 [0.0, 1.0]。
         */
        public Double neutralScore;

        /**
         * 文本中的关键主题。
         */
        public List<String> keyTopics;

        /**
         * 情感分析简要摘要。
         */
        public String summary;

        /**
         * 默认构造函数。
         */
        public SentimentAnalysis() {}
    }
}
