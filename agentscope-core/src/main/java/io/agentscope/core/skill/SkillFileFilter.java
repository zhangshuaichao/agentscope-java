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

package io.agentscope.core.skill;

/**
 * 判断是否上传技能资源的过滤器。
 */
@FunctionalInterface
public interface SkillFileFilter {

    /**
     * 判断是否接受上传指定资源路径。
     *
     * @param resourcePath 资源路径（相对于技能根目录）
     * @return 应上传时为 true，否则为 false
     */
    boolean accept(String resourcePath);

    /**
     * 返回接受全部文件的过滤器。
     *
     * @return 接受全部文件的过滤器
     */
    static SkillFileFilter acceptAll() {
        return path -> true;
    }
}
