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
package io.agentscope.core.rag.integration.haystack;

/** 确定检索器与文档存储交互时如何应用过滤条件的策略。 */
public enum FilterPolicy {

    /** 调用检索器时，运行时过滤条件替换初始化过滤条件。 */
    REPLACE("replace"),

    /** 运行时过滤条件与初始化条件合并，同名值由运行时条件覆盖。 */
    MERGE("merge");

    private final String policy;

    FilterPolicy(String policy) {
        this.policy = policy;
    }

    public String getPolicy() {
        return policy;
    }
}
