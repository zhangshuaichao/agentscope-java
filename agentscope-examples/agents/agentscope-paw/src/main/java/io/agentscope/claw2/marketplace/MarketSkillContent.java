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
package io.agentscope.claw2.marketplace;

import java.util.Map;

/**
 * {@link ClawMarketplace#fetch(String)} 返回的完整技能载荷。{@code markdown} 为 SKILL.md 正文；{@code resources} 以工作区相对路径为键保存附属文件（例如 {@code "templates/intro.md"} 对应文件内容）。
 *
 * @param name 技能标识，与请求的 {@link MarketSkillSummary#name()} 一致
 * @param description 从摘要复制的单行说明，避免调用方二次查询
 * @param markdown SKILL.md 正文，必须存在且非空
 * @param resources 附属文件的相对路径到内容映射；不为 null，可为空
 */
public record MarketSkillContent(
        String name, String description, String markdown, Map<String, String> resources) {}
