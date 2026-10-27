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

package io.agentscope.core.training.strategy;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 训练选择结果，封装是否训练及标签、原因等元数据。
 */
public class SelectionDecision {

    /**
     * 是否进行训练。
     */
    private final boolean shouldTrain;

    /**
     * 决策原因，用于日志与调试。
     */
    private final String reason;

    /**
     * 可选训练分类标签。
     */
    private final List<String> labels;

    /**
     * 可选附加元数据。
     */
    private final Map<String, Object> metadata;

    private SelectionDecision(
            boolean shouldTrain, String reason, List<String> labels, Map<String, Object> metadata) {
        this.shouldTrain = shouldTrain;
        this.reason = reason;
        this.labels =
                labels != null ? Collections.unmodifiableList(labels) : Collections.emptyList();
        this.metadata =
                metadata != null ? Collections.unmodifiableMap(metadata) : Collections.emptyMap();
    }

    /**
     * 创建应训练的决策。
     */
    public static SelectionDecision accept(String reason) {
        return new SelectionDecision(true, reason, null, null);
    }

    /**
     * 创建带标签的应训练决策。
     */
    public static SelectionDecision accept(String reason, String... labels) {
        return new SelectionDecision(true, reason, Arrays.asList(labels), null);
    }

    /**
     * 创建带标签和元数据的应训练决策。
     */
    public static SelectionDecision accept(
            String reason, List<String> labels, Map<String, Object> metadata) {
        return new SelectionDecision(true, reason, labels, metadata);
    }

    /**
     * 创建不应训练的决策。
     */
    public static SelectionDecision reject(String reason) {
        return new SelectionDecision(false, reason, null, null);
    }

    // 属性访问器
    public boolean shouldTrain() {
        return shouldTrain;
    }

    public String getReason() {
        return reason;
    }

    public List<String> getLabels() {
        return labels;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    @Override
    public String toString() {
        return "SelectionDecision{"
                + "shouldTrain="
                + shouldTrain
                + ", reason='"
                + reason
                + '\''
                + ", labels="
                + labels
                + ", metadata="
                + metadata
                + '}';
    }
}
