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
package io.agentscope.harness.agent;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.bus.AsyncToolRegistry;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.gateway.SessionTurnGate;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.team.TeamClient;
import java.util.Objects;

/**
 * 分布式与持久化存储组件的一站式配置。
 *
 * <p>无需分别配置 {@link AgentStateStore}、{@link BaseStore}、
 * {@link SandboxSnapshotSpec} 和 {@link SandboxExecutionGuard}，可以把一个
 * {@code DistributedStore} 传给 {@link HarnessAgent.Builder#distributedStore(DistributedStore)}：
 *
 * <pre>{@code
 * // Single store — all components from Redis
 * DistributedStore store = RedisDistributedStore.fromJedis(jedis);
 *
 * // Mixed stores — MySQL for state/files, Redis for sandbox lock/snapshot
 * DistributedStore store = DistributedStore.builder()
 *     .agentStateStore(mysqlStore.agentStateStore())
 *     .baseStore(mysqlStore.baseStore())
 *     .sandboxSnapshotSpec(redisStore.sandboxSnapshotSpec())
 *     .sandboxExecutionGuard(redisStore.sandboxExecutionGuard())
 *     .build();
 *
 * HarnessAgent.builder()
 *     .distributedStore(store)
 *     .filesystem(new RemoteFilesystemSpec()
 *             .isolationScope(IsolationScope.USER))
 *     .build();
 * }</pre>
 *
 * <p>{@code distributedStore} 自动装配 {@link AgentStateStore} 和沙箱组件
 * （快照、执行守卫）。工作区文件系统模式（{@code RemoteFilesystemSpec}、
 * {@code DockerFilesystemSpec} 等）始终由用户通过 {@code .filesystem(...)} 显式配置。
 *
 * <p>实现由扩展模块提供：
 * <ul>
 *   <li>{@code agentscope-extensions-cos}：{@code CosDistributedStore}</li>
 *   <li>{@code agentscope-extensions-jdbc}：{@code JdbcDistributedStore}</li>
 *   <li>{@code agentscope-extensions-mongodb}：{@code MongoDistributedStore}</li>
 *   <li>{@code agentscope-extensions-oss}：{@code OssDistributedStore}</li>
 *   <li>{@code agentscope-extensions-redis}：{@code RedisDistributedStore}</li>
 * </ul>
 *
 * <p><b>优先级：</b>显式构建器方法（{@code .stateStore()}、{@code .filesystem()}）
 * 在各自配置的组件上，优先于 {@code distributedStore}。
 */
public interface DistributedStore {

    /**
     * 创建用于智能体会话状态持久化的 {@link AgentStateStore}。
     *
     * @return 分布式智能体状态存储；不可为 {@code null}
     */
    AgentStateStore agentStateStore();

    /**
     * 创建用于工作区文件系统键值存储的 {@link BaseStore}。
     *
     * @return 分布式基础存储；不可为 {@code null}
     */
    BaseStore baseStore();

    /**
     * 创建用于沙箱快照持久化的 {@link SandboxSnapshotSpec}。
     *
     * <p>当存储支持适合 Docker 沙箱快照的二进制对象存储时，应重写此方法。
     * 默认返回 {@link NoopSnapshotSpec}（不保存快照）。
     *
     * @return 沙箱快照规范；不可为 {@code null}
     */
    default SandboxSnapshotSpec sandboxSnapshotSpec() {
        return new NoopSnapshotSpec();
    }

    /**
     * 创建用于分布式沙箱并发控制的 {@link SandboxExecutionGuard}。
     *
     * <p>存储支持分布式锁时应重写此方法。默认返回空操作守卫，不提供跨节点协调。
     *
     * @return 沙箱执行守卫；不可为 {@code null}
     */
    default SandboxExecutionGuard sandboxExecutionGuard() {
        return SandboxExecutionGuard.noop();
    }

    /**
     * 创建用于收件箱消息投递及会话事件流的 {@link MessageBus}。
     *
     * <p>存储支持实时传输（例如 Redis Pub/Sub）时应重写此方法。默认返回 {@code null}，
     * 通知 HarnessAgent 回退为基于已解析 {@code AbstractFilesystem} 创建的工作区实现。
     *
     * @return 消息总线；返回 {@code null} 时使用工作区默认实现
     */
    default MessageBus messageBus() {
        return null;
    }

    /**
     * 创建用于跟踪异步工具执行的 {@link AsyncToolRegistry}。
     *
     * <p>存储支持持久化键值存储时应重写此方法。默认返回 {@code null}，
     * 通知 HarnessAgent 回退到工作区实现。
     *
     * @return 异步工具注册表；返回 {@code null} 时使用工作区默认实现
     */
    default AsyncToolRegistry asyncToolRegistry() {
        return null;
    }

    /**
     * 创建用于分布式子智能体后台任务的 {@link TaskRepository}。
     *
     * <p>存储支持托管任务持久化（例如控制面 {@code /api/v1/dp/tasks/*}）时应重写。
     * 默认返回 {@code null}，通知 HarnessAgent 回退为基于工作区的 {@code WorkspaceTaskRepository}。
     *
     * @return 任务仓库；返回 {@code null} 时使用工作区默认实现
     */
    default TaskRepository taskRepository() {
        return null;
    }

    /**
     * 创建用于分布式会话级轮次串行化的 {@link SessionTurnGate}。
     *
     * <p>存储支持托管锁（例如控制面 {@code /api/v1/dp/locks/*}）时应重写。
     * 默认返回 {@code null}，通知 HarnessAgent 使用网关内置的
     * {@link io.agentscope.harness.agent.gateway.LocalSessionTurnGate}。
     *
     * <p>使用分布式轮次门控时，应把 {@link io.agentscope.core.ReActAgent} 的
     * {@code conflictPolicy} 配置为 {@code FAIL}，使并发状态写入显式报错，而非静默覆盖。
     *
     * @return 会话轮次门控；返回 {@code null} 时使用进程本地默认实现
     */
    default SessionTurnGate sessionTurnGate() {
        return null;
    }

    /**
     * 创建用于 AgentTeams 协调的 {@link TeamClient}。
     *
     * <p>存储提供团队任务／消息 API（控制面），或可支撑本地 CAS 客户端时应重写。
     * 默认返回 {@code null}。
     *
     * @return 团队客户端；团队模式不可用时为 {@code null}
     */
    default TeamClient teamClient() {
        return null;
    }

    /**
     * 创建构建器，将各个独立组件组合成 {@link DistributedStore}，组件可以来自不同存储实现。
     *
     * <p>示例：MySQL 用于状态和文件，Redis 用于沙箱：
     * <pre>{@code
     * DistributedStore mysql = MysqlDistributedStore.create(dataSource);
     * DistributedStore redis = RedisDistributedStore.fromJedis(jedis);
     *
     * DistributedStore mixed = DistributedStore.builder()
     *     .agentStateStore(mysql.agentStateStore())
     *     .baseStore(mysql.baseStore())
     *     .sandboxSnapshotSpec(redis.sandboxSnapshotSpec())
     *     .sandboxExecutionGuard(redis.sandboxExecutionGuard())
     *     .build();
     * }</pre>
     *
     * @return 新构建器
     */
    static Builder builder() {
        return new Builder();
    }

    /**
     * 把各独立组件组合成 {@link DistributedStore} 的构建器。
     */
    final class Builder {

        private AgentStateStore agentStateStore;
        private BaseStore baseStore;
        private SandboxSnapshotSpec sandboxSnapshotSpec;
        private SandboxExecutionGuard sandboxExecutionGuard;
        private MessageBus messageBus;
        private AsyncToolRegistry asyncToolRegistry;
        private TaskRepository taskRepository;
        private SessionTurnGate sessionTurnGate;
        private TeamClient teamClient;

        private Builder() {}

        /**
         * 设置智能体状态存储组件。
         *
         * @param agentStateStore 要使用的状态存储
         * @return 此构建器
         */
        public Builder agentStateStore(AgentStateStore agentStateStore) {
            this.agentStateStore = agentStateStore;
            return this;
        }

        /**
         * 设置工作区文件系统键值存储所用的基础存储组件。
         *
         * @param baseStore 要使用的基础存储
         * @return 此构建器
         */
        public Builder baseStore(BaseStore baseStore) {
            this.baseStore = baseStore;
            return this;
        }

        /**
         * 设置沙箱快照规范。
         *
         * @param sandboxSnapshotSpec 要使用的快照规范
         * @return 此构建器
         */
        public Builder sandboxSnapshotSpec(SandboxSnapshotSpec sandboxSnapshotSpec) {
            this.sandboxSnapshotSpec = sandboxSnapshotSpec;
            return this;
        }

        /**
         * 设置用于分布式并发控制的沙箱执行守卫。
         *
         * @param sandboxExecutionGuard 要使用的执行守卫
         * @return 此构建器
         */
        public Builder sandboxExecutionGuard(SandboxExecutionGuard sandboxExecutionGuard) {
            this.sandboxExecutionGuard = sandboxExecutionGuard;
            return this;
        }

        public Builder messageBus(MessageBus messageBus) {
            this.messageBus = messageBus;
            return this;
        }

        public Builder asyncToolRegistry(AsyncToolRegistry asyncToolRegistry) {
            this.asyncToolRegistry = asyncToolRegistry;
            return this;
        }

        public Builder taskRepository(TaskRepository taskRepository) {
            this.taskRepository = taskRepository;
            return this;
        }

        /**
         * 设置用于分布式会话级轮次串行化的门控。
         *
         * @param sessionTurnGate 要使用的轮次门控
         * @return 此构建器
         */
        public Builder sessionTurnGate(SessionTurnGate sessionTurnGate) {
            this.sessionTurnGate = sessionTurnGate;
            return this;
        }

        /**
         * 设置 AgentTeams 客户端。
         */
        public Builder teamClient(TeamClient teamClient) {
            this.teamClient = teamClient;
            return this;
        }

        /**
         * 构建组合式 {@link DistributedStore}。
         *
         * @return 由已配置组件组合而成的新分布式存储
         * @throws NullPointerException 未设置 agentStateStore 或 baseStore 时抛出
         */
        public DistributedStore build() {
            Objects.requireNonNull(agentStateStore, "agentStateStore is required");
            Objects.requireNonNull(baseStore, "baseStore is required");
            SandboxSnapshotSpec snap =
                    sandboxSnapshotSpec != null ? sandboxSnapshotSpec : new NoopSnapshotSpec();
            SandboxExecutionGuard guard =
                    sandboxExecutionGuard != null
                            ? sandboxExecutionGuard
                            : SandboxExecutionGuard.noop();
            return new CompositeDistributedStore(
                    agentStateStore,
                    baseStore,
                    snap,
                    guard,
                    messageBus,
                    asyncToolRegistry,
                    taskRepository,
                    sessionTurnGate,
                    teamClient);
        }
    }

    /**
     * 由分别指定的组件组合而成的分布式存储。
     */
    record CompositeDistributedStore(
            AgentStateStore stateStore,
            BaseStore store,
            SandboxSnapshotSpec snapshotSpec,
            SandboxExecutionGuard executionGuard,
            MessageBus bus,
            AsyncToolRegistry toolRegistry,
            TaskRepository tasks,
            SessionTurnGate turnGate,
            TeamClient teams)
            implements DistributedStore {

        @Override
        public AgentStateStore agentStateStore() {
            return stateStore;
        }

        @Override
        public BaseStore baseStore() {
            return store;
        }

        @Override
        public SandboxSnapshotSpec sandboxSnapshotSpec() {
            return snapshotSpec;
        }

        @Override
        public SandboxExecutionGuard sandboxExecutionGuard() {
            return executionGuard;
        }

        @Override
        public MessageBus messageBus() {
            return bus;
        }

        @Override
        public AsyncToolRegistry asyncToolRegistry() {
            return toolRegistry;
        }

        @Override
        public TaskRepository taskRepository() {
            return tasks;
        }

        @Override
        public SessionTurnGate sessionTurnGate() {
            return turnGate;
        }

        @Override
        public TeamClient teamClient() {
            return teams;
        }
    }
}
