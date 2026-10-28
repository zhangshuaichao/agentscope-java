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
package io.agentscope.dataagent.runtime.marketplace;

/**
 * {@link DataAgentMarketplace#list()} 返回的轻量技能描述符。
 * 用于填充市场浏览界面，避免下载每个 SKILL.md 的开销。
 * @param name        用户安装时使用的稳定标识符
 * @param description 界面显示的单行摘要，可为空字符串但不可为 null
 * @param version     上游版本字符串；源没有版本概念时为 {@code null}（例如无标签 Git 仓库）
 */
public record MarketSkillSummary(String name, String description, String version) {}
