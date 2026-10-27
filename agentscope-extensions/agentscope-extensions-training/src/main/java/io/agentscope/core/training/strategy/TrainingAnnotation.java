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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一次智能体调用的训练标记，包含：
 * <ul><li>enabled：是否用于训练。</li><li>taskId：可选用户指定任务 ID。</li>
 * <li>labels：训练数据分类标签。</li><li>metadata：附加上下文。</li>
 * <li>timestamp：标记创建时间。</li></ul>
 */
public class TrainingAnnotation {

    /**
     * 是否将此调用标记为训练。
     */
    private final boolean enabled;

    /**
     * 可选用户指定任务 ID，null 表示自动生成。
     */
    private final String taskId;

    /**
     * 训练样本的用户自定义标签。
     */
    private final List<String> labels;

    /**
     * 训练样本附加元数据。
     */
    private final Map<String, Object> metadata;

    /**
     * 标记创建时间戳。
     */
    private final long timestamp;

    /**
     * 供构建器使用的私有构造器。
     */
    private TrainingAnnotation(
            boolean enabled,
            String taskId,
            List<String> labels,
            Map<String, Object> metadata,
            long timestamp) {
        this.enabled = enabled;
        this.taskId = taskId;
        this.labels = labels != null ? labels : new ArrayList<>();
        this.metadata = metadata != null ? metadata : new HashMap<>();
        this.timestamp = timestamp;
    }

    // 属性访问器
    public boolean isEnabled() {
        return enabled;
    }

    public String getTaskId() {
        return taskId;
    }

    public List<String> getLabels() {
        return labels;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public long getTimestamp() {
        return timestamp;
    }

    /**
     * 按给定 TTL 检查标记是否过期。
     * @param ttlMillis 存活时间，单位毫秒
     * @return 过期为 true，否则为 false
     */
    public boolean isExpired(long ttlMillis) {
        return System.currentTimeMillis() - timestamp > ttlMillis;
    }

    /**
     * 创建不带标签和元数据的启用标记。
     */
    public static TrainingAnnotation enabled() {
        return TrainingAnnotation.builder().enabled(true).build();
    }

    /**
     * 创建带标签的启用标记。
     */
    public static TrainingAnnotation withLabels(String... labels) {
        return TrainingAnnotation.builder().enabled(true).labels(Arrays.asList(labels)).build();
    }

    /**
     * 创建带标签和元数据的启用标记。
     */
    public static TrainingAnnotation withLabelsAndMetadata(
            List<String> labels, Map<String, Object> metadata) {
        return TrainingAnnotation.builder()
                .enabled(true)
                .labels(labels != null ? new ArrayList<>(labels) : new ArrayList<>())
                .metadata(metadata != null ? new HashMap<>(metadata) : new HashMap<>())
                .build();
    }

    /**
     * 创建 TrainingAnnotation 构建器。
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TrainingAnnotation that = (TrainingAnnotation) o;
        return enabled == that.enabled
                && timestamp == that.timestamp
                && Objects.equals(taskId, that.taskId)
                && Objects.equals(labels, that.labels)
                && Objects.equals(metadata, that.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, taskId, labels, metadata, timestamp);
    }

    @Override
    public String toString() {
        return "TrainingAnnotation{"
                + "enabled="
                + enabled
                + ", taskId="
                + taskId
                + ", labels="
                + labels
                + ", metadata="
                + metadata
                + ", timestamp="
                + timestamp
                + '}';
    }

    /**
     * TrainingAnnotation 构建器。
     */
    public static class Builder {
        private boolean enabled;
        private String taskId;
        private List<String> labels = new ArrayList<>();
        private Map<String, Object> metadata = new HashMap<>();
        private long timestamp = System.currentTimeMillis();

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder labels(List<String> labels) {
            this.labels = labels != null ? labels : new ArrayList<>();
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata != null ? metadata : new HashMap<>();
            return this;
        }

        public Builder timestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public TrainingAnnotation build() {
            return new TrainingAnnotation(enabled, taskId, labels, metadata, timestamp);
        }
    }
}
