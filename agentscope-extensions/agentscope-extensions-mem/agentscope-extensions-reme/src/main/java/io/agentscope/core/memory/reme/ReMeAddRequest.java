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
package io.agentscope.core.memory.reme;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * ReMe API 的添加记忆请求。
 *
 * <p>发送到 {@code POST /summary_personal_memory}，由 ReMe 处理轨迹并提取值得记忆的信息。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReMeAddRequest {

    /** 用于组织记忆的工作区标识。 */
    @JsonProperty("workspace_id")
    private String workspaceId;

    /** 待处理轨迹，即对话序列列表。 */
    private List<ReMeTrajectory> trajectories;

    /** 供 Jackson 使用的默认构造器。 */
    public ReMeAddRequest() {}

    /**
     * 使用指定工作区 ID 与轨迹创建 ReMeAddRequest。
     *
     * @param workspaceId 工作区标识
     * @param trajectories 轨迹列表
     */
    public ReMeAddRequest(String workspaceId, List<ReMeTrajectory> trajectories) {
        this.workspaceId = workspaceId;
        this.trajectories = trajectories;
    }

    // 读取与设置方法。

    public String getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(String workspaceId) {
        this.workspaceId = workspaceId;
    }

    public List<ReMeTrajectory> getTrajectories() {
        return trajectories;
    }

    public void setTrajectories(List<ReMeTrajectory> trajectories) {
        this.trajectories = trajectories;
    }

    /**
     * 创建 ReMeAddRequest 构建器。
     *
     * @return 新构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /** ReMeAddRequest 的构建器。 */
    public static class Builder {
        private String workspaceId;
        private List<ReMeTrajectory> trajectories;

        public Builder workspaceId(String workspaceId) {
            this.workspaceId = workspaceId;
            return this;
        }

        public Builder trajectories(List<ReMeTrajectory> trajectories) {
            this.trajectories = trajectories;
            return this;
        }

        public ReMeAddRequest build() {
            return new ReMeAddRequest(workspaceId, trajectories);
        }
    }
}
