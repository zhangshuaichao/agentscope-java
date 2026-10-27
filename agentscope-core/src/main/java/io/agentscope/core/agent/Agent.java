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
package io.agentscope.core.agent;

import io.agentscope.core.message.Msg;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Path;

/**
 * 组合所有能力的完整智能体接口。
 *
 * <p>此接口定义智能体的核心契约，组合了：
 * <ul>
 * <li>{@link CallableAgent}：处理消息并生成回复</li>
 * <li>{@link StreamableAgent}：在执行期间流式发送事件</li>
 * <li>{@link ObservableAgent}：观察消息但不回复</li>
 * </ul>
 *
 * <p>设计思想：
 * <ul>
 * <li>记忆管理不属于核心 Agent 接口，而由具体实现（例如 ReActAgent）负责</li>
 * <li>结构化输出是由特定智能体提供的专门能力</li>
 * <li>观察模式允许智能体接收消息而不生成回复，从而支持多智能体协作</li>
 * </ul>
 *
 * <p>AgentScope 框架中的所有智能体都应实现此接口。
 *
 * <p><b>回复契约：</b>单次 {@code call(...)} 调用恰好产生一条终态 {@link Msg}。
 * 流式变体（见 {@link StreamableAgent}）可能发出多条事件，但最终仍归结为一条终态 Msg。
 * call 方法的 {@code Mono<Msg>} 返回类型约束了这一契约。
 */
public interface Agent extends CallableAgent, StreamableAgent, ObservableAgent {

    /**
     * 获取此智能体的唯一标识符。
     *
     * @return 智能体 ID
     */
    String getAgentId();

    /**
     * 获取此智能体的名称。
     *
     * @return 智能体名称
     */
    String getName();

    /**
     * 获取此智能体的描述。
     *
     * @return 智能体描述
     */
    default String getDescription() {
        return "Agent(" + getAgentId() + ") " + getName();
    }

    /**
     * 中断当前智能体执行。
     * 此方法设置中断标志，智能体会在执行过程中的适当检查点检查该标志。
     * 中断是协作式的，因此不一定立即生效。
     */
    void interrupt();

    /**
     * 携带用户消息中断当前智能体执行。
     * 此方法设置中断标志，并将用户消息与该中断关联。中断是协作式的，因此不一定立即生效。
     *
     * @param msg 与中断关联的用户消息
     */
    void interrupt(Msg msg);

    /**
     * 返回智能体运行时的 {@link io.agentscope.core.state.AgentState}；
     * 如果此类智能体不维护状态，则返回 {@code null}。
     *
     * <p>这是声明了 {@code @Tool(stateInjected=true)} 的工具方法使用的标准访问入口：
     * 框架会在调用时将实时状态绑定到 {@code AgentState} 参数。
     */
    default io.agentscope.core.state.AgentState getAgentState() {
        return null;
    }

    /**
     * 返回智能体当前使用的 {@link Toolkit}；如果此类智能体不维护工具集，则返回 {@code null}。
     *
     * <p>这是<em>运行时</em>工具集，即智能体向模型列举可用工具和分派工具调用时使用的同一实例。
     * 需要动态注册工具的中间件（例如技能加载器）必须使用此访问方法，而不是使用构建时捕获的工具集引用，
     * 因为智能体可能在构造期间深拷贝工具集。
     */
    default Toolkit getToolkit() {
        return null;
    }

    /**
     * 返回智能体的基准 {@link SkillFilter}；如果此类智能体不提供该过滤器，则返回 {@code null}。
     * 使用方在其上叠加每次调用的 {@code RuntimeContext} 过滤器；值为 {@code null} 时回退到自己的默认设置。
     */
    default SkillFilter getSkillFilter() {
        return null;
    }

    /**
     * 返回智能体用于技能实体化的稳定工作目录；如果此类智能体不提供工作目录，则返回 {@code null}。
     * 值为 {@code null} 时，调用方回退到自行分配的临时目录。
     */
    default Path getSkillWorkDir() {
        return null;
    }
}
