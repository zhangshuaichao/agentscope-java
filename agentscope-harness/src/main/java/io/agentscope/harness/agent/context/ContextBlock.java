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
package io.agentscope.harness.agent.context;

import java.util.Objects;

/** 动态数据，绝不是系统指令。实例不可变，流式配置方法返回副本。 */
public final class ContextBlock {
    private final ContextItem item;

    private ContextBlock(ContextItem item) {
        this.item = item;
    }

    public static ContextBlock runtime(String id, String content) {
        return create("runtime", id, content, ContextItem.Placement.RUNTIME);
    }

    public static ContextBlock reference(String id, String content) {
        return create("reference", id, content, ContextItem.Placement.REFERENCE);
    }

    private static ContextBlock create(
            String kind, String id, String content, ContextItem.Placement placement) {
        Objects.requireNonNull(content);
        if (Objects.requireNonNull(id).isBlank())
            throw new IllegalArgumentException("Block ID must not be blank");
        ContextItem initial = new ContextItem(kind, id, content);
        return new ContextBlock(
                new ContextItem(kind, id, initial.revision(), placement, false, 0, content));
    }

    /** 业务修订号可选；默认修订号是内容哈希，不是验收证据。 */
    public ContextBlock withRevision(String revision) {
        if (Objects.requireNonNull(revision).isBlank())
            throw new IllegalArgumentException("Revision must not be blank");
        return new ContextBlock(
                new ContextItem(
                        item.kind(),
                        item.sourceId(),
                        revision,
                        item.placement(),
                        item.required(),
                        item.priority(),
                        item.content()));
    }

    /** 禁止因预算不足而淘汰；不能与 OMIT 来源搭配使用。 */
    public ContextBlock required() {
        return new ContextBlock(
                new ContextItem(
                        item.kind(),
                        item.sourceId(),
                        item.revision(),
                        item.placement(),
                        true,
                        item.priority(),
                        item.content()));
    }

    /** 在同类材料内，优先级较低的条目优先进入淘汰候选。 */
    public ContextBlock withPriority(int priority) {
        return new ContextBlock(
                new ContextItem(
                        item.kind(),
                        item.sourceId(),
                        item.revision(),
                        item.placement(),
                        item.required(),
                        priority,
                        item.content()));
    }

    ContextItem material() {
        return item;
    }
}
