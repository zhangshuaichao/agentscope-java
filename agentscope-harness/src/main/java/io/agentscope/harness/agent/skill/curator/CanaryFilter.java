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
package io.agentscope.harness.agent.skill.curator;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import java.nio.charset.StandardCharsets;

/**
 * 针对智能体编写技能的百分比灰度发布。对 {@code userId × skillName} 做哈希，仅当 {@code hash mod 100 < percent} 时允许技能。
 *
 * <p>仅通过 {@link AbstractAgentCreatedFilter} 作用于智能体编写的技能，手工编写或技能中心安装的技能始终通过。
 *
 * <p>{@code rampUpDays} 当前只是占位参数：构造时接受，但实现尚未支持逐步放量，始终采用配置的静态百分比。未来版本计划根据 {@code SkillUsageRecord.promotedAt} 插值。
 */
public class CanaryFilter extends AbstractAgentCreatedFilter {

    private final int percent;
    private final int rampUpDays;

    public CanaryFilter(int percent, SkillUsageStore usageStore) {
        this(percent, 0, usageStore);
    }

    public CanaryFilter(int percent, int rampUpDays, SkillUsageStore usageStore) {
        super(usageStore);
        this.percent = clamp(percent);
        this.rampUpDays = Math.max(0, rampUpDays);
    }

    private static int clamp(int p) {
        if (p < 0) return 0;
        if (p > 100) return 100;
        return p;
    }

    @Override
    @SuppressWarnings("deprecation")
    protected boolean shouldPassForAgentCreated(
            AgentSkill skill, SkillUsageRecord rec, RuntimeContext ctx) {
        if (percent >= 100) {
            return true;
        }
        if (percent <= 0) {
            return false;
        }
        String key =
                (ctx != null && ctx.getUserId() != null ? ctx.getUserId() : "anonymous")
                        + "|"
                        + skill.getName();
        int bucket = stableBucket(key, 100);
        return bucket < percent;
    }

    /**
     * 稳定哈希映射到桶，使用 Java String.hashCode 保证确定性。
     */
    static int stableBucket(String key, int modulus) {
        // 取 32 位滚动哈希的绝对值，避免有符号溢出带来的意外。
        int h = 0;
        for (byte b : key.getBytes(StandardCharsets.UTF_8)) {
            h = 31 * h + (b & 0xFF);
        }
        if (h < 0) {
            h = -(h + 1); // 处理 Math.abs(Integer.MIN_VALUE) 边界情况。
        }
        return h % modulus;
    }

    public int getRampUpDays() {
        return rampUpDays;
    }
}
