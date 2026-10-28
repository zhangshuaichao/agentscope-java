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
package io.agentscope.dataagent.web.share;

import io.agentscope.dataagent.web.catalog.AgentDefinition;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/** 逐智能体权限的唯一权威来源。<p>权限级别按 {@code EDIT > RUN > CLONE} 排序：<ul><li>{@code SCOPE_USER} 智能体所有者获得 {@code EDIT}。<li>已登录用户对 {@code SCOPE_GLOBAL} 智能体获得 {@code RUN}；全局智能体在界面保持只读，修改通过提交 {@code agentscope.json} 完成，不经过 ACL。<li>{@code (USER, userId)} 授权立即适用于指定用户。<li>{@code (WORKSPACE, "*")} 授权适用于所有已登录用户。<li>取匹配的最高权限级别。<li>{@code admin} 角色只授予用户管理能力，<strong>不会</strong>自动获得智能体级访问权。</ul><p>服务刻意保持无状态，唯一输入是每请求 {@link AgentDefinition} 快照。目录/存储层必须原样提供 {@code shares} 列表；权限门控在此执行，而非目录或控制器路径中。 */
@Service
public class AgentAclService {

    public enum Tier {
        CLONE(1),
        RUN(2),
        EDIT(3);

        private final int rank;

        Tier(int rank) {
            this.rank = rank;
        }

        public boolean implies(Tier other) {
            return this.rank >= other.rank;
        }
    }

    /** 返回 {@code userId} 对 {@code def} 拥有的最高权限级别；无权限时为 {@code null}。 */
    public Tier tierFor(String userId, AgentDefinition def) {
        if (def == null) {
            return null;
        }
        if (AgentDefinition.SCOPE_GLOBAL.equals(def.scope())) {
            return Tier.RUN;
        }
        if (userId != null && userId.equals(def.ownerId())) {
            return Tier.EDIT;
        }
        return highestMatchingGrant(userId, def.shares());
    }

    /** 当且仅当 {@code userId} 对 {@code def} 至少拥有 {@code required} 权限时为 {@code true}。 */
    public boolean can(String userId, AgentDefinition def, Tier required) {
        Tier held = tierFor(userId, def);
        return held != null && held.implies(required);
    }

    /** 从候选列表筛出 {@code userId} 至少拥有 {@code min} 权限的智能体。 */
    public List<AgentDefinition> filterVisible(String userId, List<AgentDefinition> all, Tier min) {
        List<AgentDefinition> out = new ArrayList<>(all.size());
        for (AgentDefinition def : all) {
            if (can(userId, def, min)) {
                out.add(def);
            }
        }
        return out;
    }

    /** 返回 {@code grants} 中通过直接 USER 授权或 WORKSPACE 授权适用于 {@code userId} 的最高级别。没有匹配项时为 {@code null}。 */
    private Tier highestMatchingGrant(String userId, List<AgentShareGrant> grants) {
        if (grants == null || grants.isEmpty()) {
            return null;
        }
        Tier best = null;
        for (AgentShareGrant g : grants) {
            if (!applies(userId, g)) {
                continue;
            }
            Tier t = parseTier(g.tier());
            if (t == null) {
                continue;
            }
            if (best == null || t.implies(best)) {
                best = t;
            }
        }
        return best;
    }

    private static boolean applies(String userId, AgentShareGrant g) {
        if (g == null || g.granteeType() == null || g.tier() == null) {
            return false;
        }
        if (AgentShareGrant.GRANTEE_WORKSPACE.equals(g.granteeType())) {
            // 工作区授权适用于所有已登录用户。
            return userId != null;
        }
        if (AgentShareGrant.GRANTEE_USER.equals(g.granteeType())) {
            return userId != null && userId.equals(g.granteeId());
        }
        return false;
    }

    private static Tier parseTier(String raw) {
        if (raw == null) return null;
        try {
            return Tier.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
