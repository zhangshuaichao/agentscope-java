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
package io.agentscope.builder.web.share;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.builder.web.catalog.AgentDefinition;
import io.agentscope.builder.web.share.AgentAclService.Tier;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentAclServiceTest {

    private final AgentAclService acl = new AgentAclService();

    @Test
    void ownerOfUserScopedAgentGetsEdit() {
        AgentDefinition def = userAgent("alice", null);
        assertThat(acl.tierFor("alice", def)).isEqualTo(Tier.EDIT);
        assertThat(acl.can("alice", def, Tier.EDIT)).isTrue();
    }

    @Test
    void nonOwnerWithoutGrantHasNoTier() {
        AgentDefinition def = userAgent("alice", null);
        assertThat(acl.tierFor("bob", def)).isNull();
        assertThat(acl.can("bob", def, Tier.CLONE)).isFalse();
    }

    @Test
    void globalAgentsGrantEditToEveryAuthenticatedUser() {
        AgentDefinition global = globalAgent();
        // 全局智能体为每位已登录用户授予 EDIT；写入经 HarnessAgent.workspaceFor(userId, null)
        // 进入用户专属覆盖层，不修改共享 agentscope.json。
        assertThat(acl.tierFor("alice", global)).isEqualTo(Tier.EDIT);
        assertThat(acl.tierFor("bob", global)).isEqualTo(Tier.EDIT);
        assertThat(acl.can("alice", global, Tier.RUN)).isTrue();
        assertThat(acl.can("alice", global, Tier.EDIT)).isTrue();
        // 匿名调用者没有访问权。
        assertThat(acl.tierFor(null, global)).isNull();
    }

    @Test
    void directUserGrantApplies() {
        AgentDefinition def = userAgent("alice", List.of(userGrant("bob", "RUN")));
        assertThat(acl.tierFor("bob", def)).isEqualTo(Tier.RUN);
        assertThat(acl.tierFor("carol", def)).isNull();
    }

    @Test
    void workspaceGrantAppliesToEveryLoggedInUser() {
        AgentDefinition def = userAgent("alice", List.of(workspaceGrant("CLONE")));
        assertThat(acl.tierFor("bob", def)).isEqualTo(Tier.CLONE);
        assertThat(acl.tierFor("carol", def)).isEqualTo(Tier.CLONE);
        // 拥有者仍优先获得 EDIT。
        assertThat(acl.tierFor("alice", def)).isEqualTo(Tier.EDIT);
    }

    @Test
    void highestMatchingTierWins() {
        AgentDefinition def =
                userAgent(
                        "alice",
                        List.of(
                                workspaceGrant("CLONE"),
                                userGrant("bob", "RUN"),
                                userGrant("bob", "EDIT")));
        assertThat(acl.tierFor("bob", def)).isEqualTo(Tier.EDIT);
    }

    @Test
    void tierImpliesLowerTiers() {
        assertThat(Tier.EDIT.implies(Tier.RUN)).isTrue();
        assertThat(Tier.EDIT.implies(Tier.CLONE)).isTrue();
        assertThat(Tier.RUN.implies(Tier.CLONE)).isTrue();
        assertThat(Tier.RUN.implies(Tier.EDIT)).isFalse();
        assertThat(Tier.CLONE.implies(Tier.RUN)).isFalse();
    }

    @Test
    void filterVisibleKeepsOnlyAgentsAtOrAboveMin() {
        AgentDefinition ownByAlice = userAgent("alice", null);
        AgentDefinition sharedRunToBob = userAgent("alice", List.of(userGrant("bob", "RUN")));
        AgentDefinition sharedCloneToBob = userAgent("alice", List.of(userGrant("bob", "CLONE")));
        AgentDefinition unrelated = userAgent("carol", null);

        List<AgentDefinition> all =
                List.of(ownByAlice, sharedRunToBob, sharedCloneToBob, unrelated);

        // Bob 只看见自己持有的两项授权，不应看到无关智能体或 Alice 的私有智能体。
        assertThat(acl.filterVisible("bob", all, Tier.CLONE))
                .containsExactly(sharedRunToBob, sharedCloneToBob);
        // 要求 RUN 时会过滤仅 CLONE 的授权。
        assertThat(acl.filterVisible("bob", all, Tier.RUN)).containsExactly(sharedRunToBob);
    }

    @Test
    void unknownTierStringIsIgnored() {
        AgentDefinition def = userAgent("alice", List.of(userGrant("bob", "BOGUS")));
        assertThat(acl.tierFor("bob", def)).isNull();
    }

    @Test
    void nullUserIdNeverMatchesUserOrWorkspaceGrants() {
        // ACL 是内层关卡，JWT 认证由上游执行，运行时不应出现 null userId。
        // 服务本身仍采取安全行为：按用户的授权不匹配 null，
        // 工作区级授权也要求非 null userId。
        AgentDefinition def = userAgent("alice", List.of(workspaceGrant("RUN")));
        assertThat(acl.tierFor(null, def)).isNull();
    }

    // ---------- 辅助方法 ----------

    private static AgentDefinition userAgent(String ownerId, List<AgentShareGrant> shares) {
        return new AgentDefinition(
                "agent-1",
                "Agent 1",
                null, // description：描述：描述
                null, // system：系统提示：系统提示
                null, // model：模型：模型
                null, // maxIters：最大迭代数：最大迭代数
                null, // tools：工具：工具
                null, // mcpServers：MCP 服务器：MCP 服务器
                null, // skills：技能：技能
                null, // multiagent：多智能体：多智能体
                null, // identityName：身份名称：身份名称
                null, // identityEmoji：身份图标：身份图标
                null, // groupChatMentionPatterns：群聊提及模式：群聊提及模式
                null, // groupChatRequireMention：群聊是否要求提及：群聊是否要求提及
                AgentDefinition.SCOPE_USER,
                ownerId,
                0L,
                0L,
                shares,
                AgentDefinition.RUN_AS_INVOKER,
                null, // forkOf：来源智能体：来源智能体
                null, // workspacePath：工作区路径：工作区路径
                null, // sandboxMode：沙箱模式：沙箱模式
                null, // sandboxScope：沙箱隔离范围：沙箱隔离范围
                null, // version：版本：版本
                null, // archivedAt：归档时间：归档时间
                null, // metadata：元数据：元数据
                null); // tierForCurrentUser：当前用户权限级别：当前用户权限级别
    }

    private static AgentDefinition globalAgent() {
        return new AgentDefinition(
                "default",
                "Default",
                null, // description
                null, // system
                null, // model
                null, // maxIters
                null, // tools
                null, // mcpServers
                null, // skills
                null, // multiagent
                null, // identityName
                null, // identityEmoji
                null, // groupChatMentionPatterns
                null, // groupChatRequireMention
                AgentDefinition.SCOPE_GLOBAL,
                null,
                0L,
                0L,
                null,
                AgentDefinition.RUN_AS_INVOKER,
                null, // forkOf
                null, // workspacePath
                null, // sandboxMode
                null, // sandboxScope
                null, // version
                null, // archivedAt
                null, // metadata
                null); // tierForCurrentUser
    }

    private static AgentShareGrant userGrant(String userId, String tier) {
        return new AgentShareGrant(AgentShareGrant.GRANTEE_USER, userId, tier, 0L, "admin");
    }

    private static AgentShareGrant workspaceGrant(String tier) {
        return new AgentShareGrant(
                AgentShareGrant.GRANTEE_WORKSPACE, AgentShareGrant.WORKSPACE_ID, tier, 0L, "admin");
    }
}
