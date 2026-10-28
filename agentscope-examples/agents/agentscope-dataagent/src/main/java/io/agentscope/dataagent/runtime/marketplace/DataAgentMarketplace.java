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

import java.util.List;

/** 由应用管理的技能市场，通过界面浏览和配置，独立于智能体自身加载的运行时 {@code skillRepositories}。<p>实现持有状态（已打开的 Git 克隆、Nacos 客户端），注册表替换或移除它们时必须关闭。 */
public interface DataAgentMarketplace extends AutoCloseable {

    /** 用户创建市场时选择的稳定 ID。 */
    String id();

    /** 界面用于徽标和配置表单的类型判别值（{@code "git"} / {@code "nacos"}）。 */
    String type();

    /** 界面显示的可读位置（URL、服务器地址等），绝不包含凭据。 */
    String displayLocation();

    /** 市场是否接受写入；应用当前将市场视为只读，但界面可展示此能力。 */
    default boolean writable() {
        return false;
    }

    /** 列出市场暴露的全部技能。实现可能缓存结果，也可能每次访问上游；调用方应视为潜在慢操作，并让界面延迟加载。 */
    List<MarketSkillSummary> list();

    /** 获取指定技能的完整内容（SKILL.md 及附属资源）；不存在时返回 {@code null}。 */
    MarketSkillContent fetch(String name);

    /** 释放上游资源（关闭 Git、停止 Nacos 客户端），可安全重复调用。 */
    @Override
    void close();
}
