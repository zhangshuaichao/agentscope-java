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
package io.agentscope.dataagent.web.audit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * 智能体逐命名空间活动日志的一条记录。
 * <p>在智能体命名空间工作区内的 {@code activity.jsonl} 中，仅追加写入，每行一个 JSON 对象，
 * 因而继承相应隔离规则。参见计划 §1.6 / §G。
 * @param id 稳定标识符（ULID 或随机十六进制）
 * @param timestampMs 事件记录时间（纪元毫秒）
 * @param actorUserId 执行动作的用户 ID
 * @param actorUsername 已解析的显示用户名，记录时未知则为 {@code null}
 * @param action 高层事件类别，规范集合见 {@link Action}
 * @param target 可选事件专用目标（文件路径、通道 ID、sessionKey、granteeId 等）
 * @param metadata 可选结构化详情，序列化为 JSON 对象
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ActivityEvent(
        String id,
        long timestampMs,
        String actorUserId,
        String actorUsername,
        String action,
        String target,
        Map<String, Object> metadata) {

    /** 规范动作标签。可增加新标签，读取方应容忍未知值。 */
    public static final class Action {
        public static final String CREATE = "CREATE";
        public static final String EDIT_SETTINGS = "EDIT_SETTINGS";
        public static final String DELETE_AGENT = "DELETE_AGENT";
        public static final String EDIT_FILE = "EDIT_FILE";
        public static final String CREATE_FILE = "CREATE_FILE";
        public static final String DELETE_FILE = "DELETE_FILE";
        public static final String RENAME_FILE = "RENAME_FILE";
        public static final String UPLOAD_FILE = "UPLOAD_FILE";
        public static final String GRANT_SHARE = "GRANT_SHARE";
        public static final String REVOKE_SHARE = "REVOKE_SHARE";
        public static final String CLONE_FROM = "CLONE_FROM";
        public static final String CLONE_TO = "CLONE_TO";
        public static final String BIND_CHANNEL = "BIND_CHANNEL";
        public static final String UNBIND_CHANNEL = "UNBIND_CHANNEL";
        public static final String EDIT_BINDING = "EDIT_BINDING";
        public static final String RUN_SESSION = "RUN_SESSION";

        private Action() {}
    }
}
