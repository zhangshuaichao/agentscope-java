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
package io.agentscope.builder.web.managed;

import java.util.List;
import java.util.Map;

/** 托管会话 {@link io.agentscope.harness.agent.HarnessAgent} 的运行时构建参数。<p>缓存身份以 {@code (owner, agent, version, environmentId)} 为基础；覆盖配置、记忆、密钥库和资源在构建时应用，并加入此处生成的后缀以区分挂载配置。<p>【学习提示】后缀使用 Java hashCode 压缩部分配置，不是密码学摘要，也不是资源访问凭据；不能将它当作无碰撞身份或授权证明。 */
public record SessionAgentBuildSpec(
        Integer version,
        String environmentId,
        EnvironmentDto environment,
        String overridesJson,
        List<String> memoryStoreIds,
        List<String> vaultIds,
        List<Map<String, Object>> resources) {

    public SessionAgentBuildSpec(
            Integer version,
            String environmentId,
            EnvironmentDto environment,
            String overridesJson,
            List<String> memoryStoreIds,
            List<String> vaultIds) {
        this(version, environmentId, environment, overridesJson, memoryStoreIds, vaultIds, null);
    }

    /** 从稳定身份字段构建缓存/网关后缀。 */
    public String cacheSuffix() {
        String ver = version == null ? "head" : String.valueOf(version);
        String env = environmentId == null || environmentId.isBlank() ? "default" : environmentId;
        String mem =
                memoryStoreIds == null || memoryStoreIds.isEmpty()
                        ? "-"
                        : String.join(",", memoryStoreIds);
        String vault = vaultIds == null || vaultIds.isEmpty() ? "-" : String.join(",", vaultIds);
        int ovrHash = overridesJson == null ? 0 : overridesJson.hashCode();
        int resHash = resources == null ? 0 : resources.hashCode();
        return "v"
                + ver
                + "/e"
                + env
                + "/m"
                + mem.hashCode()
                + "/vt"
                + vault.hashCode()
                + "/o"
                + ovrHash
                + "/r"
                + resHash;
    }
}
