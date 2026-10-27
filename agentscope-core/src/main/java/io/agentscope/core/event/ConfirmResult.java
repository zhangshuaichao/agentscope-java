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
package io.agentscope.core.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionRule;
import java.util.List;

/**
 * 表示用户针对某个工具调用作出的一次确认决定。
 *
 * <p>确认时，调用方可以提供修改后的 {@link #toolCall}（允许用户调整输入），
 * 和／或提供新的 {@link #rules}，由 {@code PermissionEngine} 记住并用于未来调用，
 * 例如“今后始终允许此命令”。
 *
 * <p>拒绝时，调用方可提供便于人理解的 {@link #reason} 说明原因；
 * 它会替代默认的 {@code "Permission denied by user"} 消息，成为写回模型上下文
 * （并在事件流中发出）的 DENIED 工具结果文本。
 */
public class ConfirmResult {

    private final boolean confirmed;
    private final ToolUseBlock toolCall;
    private final List<PermissionRule> rules;
    private final String reason;

    @JsonCreator
    public ConfirmResult(
            @JsonProperty("confirmed") boolean confirmed,
            @JsonProperty("toolCall") ToolUseBlock toolCall,
            @JsonProperty("rules") List<PermissionRule> rules,
            @JsonProperty("reason") String reason) {
        this.confirmed = confirmed;
        this.toolCall = toolCall;
        this.rules = rules;
        this.reason = reason;
    }

    /** 不指定拒绝原因的便捷构造器。 */
    public ConfirmResult(boolean confirmed, ToolUseBlock toolCall, List<PermissionRule> rules) {
        this(confirmed, toolCall, rules, null);
    }

    /** 不指定规则的便捷构造器。 */
    public ConfirmResult(boolean confirmed, ToolUseBlock toolCall) {
        this(confirmed, toolCall, null, null);
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public ToolUseBlock getToolCall() {
        return toolCall;
    }

    /**
     * 当 {@link #confirmed} 为 true 时，要向引擎注册的新权限规则。
     * 每条规则根据其 {@code behavior} 扩展引擎的允许／拒绝／询问规则表。
     *
     * @return 规则列表（可为 null 或空列表）
     */
    public List<PermissionRule> getRules() {
        return rules;
    }

    /**
     * 可选的、便于人理解的拒绝原因（{@link #confirmed} == false）。提供此值时，
     * 用它作为 DENIED 工具结果文本，替代默认的 {@code "Permission denied by user"}
     * 消息，使模型及下游事件消费方能知道用户拒绝调用的原因。
     *
     * @return 拒绝原因；未提供时为 null
     */
    public String getReason() {
        return reason;
    }
}
