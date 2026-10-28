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
package io.agentscope.examples.documentation2.rag;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Toolkit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * ApplicationLayerRagExample：最小 v2 检索增强生成模式。检索由应用层负责：检索相关文档，构建含检索上下文的应用提示词，再发送给普通 {@link ReActAgent}。
 * <p>内存关键词检索不依赖向量数据库或嵌入服务。生产中可用应用的嵌入模型与向量存储替换 {@link KeywordRetriever#retrieve(String, int)}，提示构建及调用方式保持不变。运行：
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.rag.ApplicationLayerRagExample
 * </pre>
 */
public class ApplicationLayerRagExample {

    private static final List<KnowledgeDocument> DOCUMENTS =
            List.of(
                    new KnowledgeDocument(
                            "refund-policy",
                            "Refunds are available within 30 days of purchase. "
                                    + "Annual subscriptions are refunded prorated."),
                    new KnowledgeDocument(
                            "support-hours",
                            "Customer support is available Monday through Friday, "
                                    + "09:00 to 18:00 China Standard Time."),
                    new KnowledgeDocument(
                            "security",
                            "Production data is encrypted in transit and at rest. "
                                    + "Support staff never request account passwords."));

    public static void main(String[] args) {
        requireDashScopeApiKey();

        String question = "I bought an annual subscription 10 days ago. Can I receive a refund?";
        KeywordRetriever retriever = new KeywordRetriever(DOCUMENTS);
        List<KnowledgeDocument> matches = retriever.retrieve(question, 2);

        System.out.println("Question: " + question);
        System.out.println(
                "Retrieved documents: " + matches.stream().map(KnowledgeDocument::id).toList());
        System.out.println("\nAnswer: ");

        ReActAgent agent =
                ReActAgent.builder()
                        .name("SupportAssistant")
                        .sysPrompt(
                                "You are a customer-support assistant. Answer only with the "
                                        + "retrieved context. If it does not contain the answer, "
                                        + "say that you do not know.")
                        .model("dashscope:qwen-plus")
                        .toolkit(new Toolkit())
                        .build();

        agent.streamEvents(new UserMessage(buildRagPrompt(question, matches)))
                .doOnNext(
                        event -> {
                            if (event instanceof TextBlockDeltaEvent textDelta) {
                                System.out.print(textDelta.getDelta());
                            }
                        })
                .blockLast();
        System.out.println();
    }

    private static void requireDashScopeApiKey() {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "DASHSCOPE_API_KEY is required. Set it before running this example.");
        }
    }

    private static String buildRagPrompt(String question, List<KnowledgeDocument> matches) {
        String context =
                matches.isEmpty()
                        ? "(No relevant documents were retrieved.)"
                        : matches.stream()
                                .map(document -> "[" + document.id() + "]\n" + document.content())
                                .collect(Collectors.joining("\n\n"));
        return """
        Retrieved context:
        %s

        User question:
        %s
        """
                .formatted(context, question);
    }

    private record KnowledgeDocument(String id, String content) {}

    /**
     * 为此独立示例实现的小型应用级检索器。
     */
    private static final class KeywordRetriever {

        private static final Pattern TOKEN_SPLITTER = Pattern.compile("[^a-z0-9]+");

        private final List<KnowledgeDocument> documents;

        private KeywordRetriever(List<KnowledgeDocument> documents) {
            this.documents = List.copyOf(documents);
        }

        private List<KnowledgeDocument> retrieve(String query, int limit) {
            Set<String> queryTerms = terms(query);
            return documents.stream()
                    .map(document -> Map.entry(document, score(queryTerms, document.content())))
                    .filter(entry -> entry.getValue() > 0)
                    .sorted(
                            Map.Entry.<KnowledgeDocument, Integer>comparingByValue(
                                            Comparator.reverseOrder())
                                    .thenComparing(entry -> entry.getKey().id()))
                    .limit(limit)
                    .map(Map.Entry::getKey)
                    .toList();
        }

        private static int score(Set<String> queryTerms, String document) {
            Set<String> documentTerms = terms(document);
            return (int) queryTerms.stream().filter(documentTerms::contains).count();
        }

        private static Set<String> terms(String text) {
            return TOKEN_SPLITTER
                    .splitAsStream(text.toLowerCase(Locale.ROOT))
                    .filter(term -> term.length() > 2)
                    .collect(Collectors.toUnmodifiableSet());
        }
    }
}
