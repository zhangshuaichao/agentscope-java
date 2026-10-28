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
package io.agentscope.examples.documentation2.copilotkit.a2ui;

import io.agentscope.core.util.JsonUtils;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

/**
 * 将自然语言意图转换为可渲染 A2UI 界面。
 * <p>依次尝试 Gemini、DashScope；缺少凭据、网络错误、JSON 格式错误、组件不在目录中等失败都会尝试下一客户端，最终回退到确定性的 {@link A2uiFallbackSurface}，使 {@code render_dashboard} 返回可供浏览器绘制的结果。
 */
@Component
public class A2uiComposer {

    private static final Logger logger = LoggerFactory.getLogger(A2uiComposer.class);

    private final A2uiSchemaSource schemaSource;
    private final List<A2uiModelClient> modelClients;

    public A2uiComposer(A2uiSchemaSource schemaSource, List<A2uiModelClient> modelClients) {
        this.schemaSource = schemaSource;
        this.modelClients =
                modelClients.stream().sorted(AnnotationAwareOrderComparator.INSTANCE).toList();
    }

    public String schemaSourceName() {
        return schemaSource.name();
    }

    /**
     * 为 {@code intent} 组合界面。
     * @param intent 用户想看到的内容
     * @param stateSnapshot 当前工作台状态，既是模型输入，也是确定性回退的事实来源
     * @param clientComponents 浏览器声明可渲染的自定义组件；为空时公布服务端完整目录
     */
    public A2uiSurface compose(
            String intent, Map<String, Object> stateSnapshot, Set<String> clientComponents) {
        String surfaceId = "workbench-" + UUID.randomUUID().toString().substring(0, 8);
        String systemPrompt = schemaSource.systemPrompt(clientComponents);
        String userPrompt = buildUserPrompt(intent, surfaceId, stateSnapshot);

        for (A2uiModelClient client : modelClients) {
            if (!client.isAvailable()) {
                continue;
            }
            try {
                String raw = client.generate(systemPrompt, userPrompt);
                List<Map<String, Object>> operations =
                        schemaSource.parse(raw, surfaceId, clientComponents);
                return new A2uiSurface(
                        surfaceId,
                        WorkbenchCatalog.CATALOG_ID,
                        intent,
                        client.name(),
                        A2uiOperations.countComponents(operations),
                        operations);
            } catch (Exception e) {
                logger.warn("A2UI 生成失败，切换下一个模型：client={}, reason={}", client.name(), e.toString());
            }
        }

        return A2uiFallbackSurface.build(surfaceId, intent, stateSnapshot);
    }

    private static String buildUserPrompt(
            String intent, String surfaceId, Map<String, Object> stateSnapshot) {
        return """
        请生成界面。

        surfaceId: %s
        用户意图: %s

        可用数据（JSON，可通过 updateDataModel 绑定，也可直接内联为字面量）：
        %s
        """
                .formatted(surfaceId, intent, toJson(stateSnapshot));
    }

    private static String toJson(Object value) {
        try {
            return JsonUtils.getJsonCodec().toJson(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
