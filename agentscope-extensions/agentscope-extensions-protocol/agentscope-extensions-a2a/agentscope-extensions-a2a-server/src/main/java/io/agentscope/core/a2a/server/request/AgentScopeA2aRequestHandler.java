/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.a2a.server.request;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResult;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.auth.TaskOperation;
import org.a2aproject.sdk.server.events.InMemoryQueueManager;
import org.a2aproject.sdk.server.events.MainEventBus;
import org.a2aproject.sdk.server.events.MainEventBusProcessor;
import org.a2aproject.sdk.server.events.QueueManager;
import org.a2aproject.sdk.server.requesthandlers.DefaultRequestHandler;
import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.server.tasks.BasePushNotificationSender;
import org.a2aproject.sdk.server.tasks.DefaultPushNotificationUrlValidator;
import org.a2aproject.sdk.server.tasks.InMemoryPushNotificationConfigStore;
import org.a2aproject.sdk.server.tasks.InMemoryTaskStore;
import org.a2aproject.sdk.server.tasks.PushNotificationConfigStore;
import org.a2aproject.sdk.server.tasks.PushNotificationSender;
import org.a2aproject.sdk.server.tasks.TaskStateProvider;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.CancelTaskParams;
import org.a2aproject.sdk.spec.DeleteTaskPushNotificationConfigParams;
import org.a2aproject.sdk.spec.EventKind;
import org.a2aproject.sdk.spec.GetTaskPushNotificationConfigParams;
import org.a2aproject.sdk.spec.ListTaskPushNotificationConfigsParams;
import org.a2aproject.sdk.spec.ListTaskPushNotificationConfigsResult;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskIdParams;
import org.a2aproject.sdk.spec.TaskPushNotificationConfig;
import org.a2aproject.sdk.spec.TaskQueryParams;
import org.a2aproject.sdk.spec.UnsupportedOperationError;

/**
 * AgentScope A2A 服务器的 {@link RequestHandler}，由 SDK 的
 * {@link DefaultRequestHandler} 及其所需组件支撑。
 */
public class AgentScopeA2aRequestHandler implements RequestHandler, AutoCloseable {

    private final DefaultRequestHandler delegate;

    private final ExecutorService executor;

    private final ExecutorService eventConsumerExecutor;

    private final MainEventBusProcessor mainEventBusProcessor;

    private AgentScopeA2aRequestHandler(
            DefaultRequestHandler delegate,
            ExecutorService executor,
            ExecutorService eventConsumerExecutor,
            MainEventBusProcessor mainEventBusProcessor) {
        this.delegate = delegate;
        this.executor = executor;
        this.eventConsumerExecutor = eventConsumerExecutor;
        this.mainEventBusProcessor = mainEventBusProcessor;
    }

    @Override
    public Task onGetTask(TaskQueryParams params, ServerCallContext context) throws A2AError {
        return delegate.onGetTask(params, context);
    }

    /**
     * 不支持：AgentScope 尚未认证调用方，开放此操作会让任意调用方列出所有会话任务。
     *
     * @throws UnsupportedOperationError 始终抛出
     */
    @Override
    public ListTasksResult onListTasks(ListTasksParams params, ServerCallContext context)
            throws A2AError {
        throw new UnsupportedOperationError(
                null, "Listing tasks is not supported: callers are not authorized", null);
    }

    @Override
    public Task onCancelTask(CancelTaskParams params, ServerCallContext context) throws A2AError {
        return delegate.onCancelTask(params, context);
    }

    @Override
    public EventKind onMessageSend(MessageSendParams params, ServerCallContext context)
            throws A2AError {
        return delegate.onMessageSend(params, context);
    }

    @Override
    public Flow.Publisher<StreamingEventKind> onMessageSendStream(
            MessageSendParams params, ServerCallContext context) throws A2AError {
        return delegate.onMessageSendStream(params, context);
    }

    @Override
    public TaskPushNotificationConfig onCreateTaskPushNotificationConfig(
            TaskPushNotificationConfig params, ServerCallContext context) throws A2AError {
        return delegate.onCreateTaskPushNotificationConfig(params, context);
    }

    @Override
    public TaskPushNotificationConfig onGetTaskPushNotificationConfig(
            GetTaskPushNotificationConfigParams params, ServerCallContext context) throws A2AError {
        return delegate.onGetTaskPushNotificationConfig(params, context);
    }

    @Override
    public Flow.Publisher<StreamingEventKind> onSubscribeToTask(
            TaskIdParams params, ServerCallContext context) throws A2AError {
        return delegate.onSubscribeToTask(params, context);
    }

    @Override
    public ListTaskPushNotificationConfigsResult onListTaskPushNotificationConfigs(
            ListTaskPushNotificationConfigsParams params, ServerCallContext context)
            throws A2AError {
        return delegate.onListTaskPushNotificationConfigs(params, context);
    }

    @Override
    public void onDeleteTaskPushNotificationConfig(
            DeleteTaskPushNotificationConfigParams params, ServerCallContext context)
            throws A2AError {
        delegate.onDeleteTaskPushNotificationConfig(params, context);
    }

    @Override
    public void authorizeTaskAccess(
            String requestedTaskId, ServerCallContext context, TaskOperation operation)
            throws A2AError {
        delegate.authorizeTaskAccess(requestedTaskId, context, operation);
    }

    /**
     * 停止 SDK 在构建处理器时启动的事件持久化与分派线程，
     * 并通过 {@link ExecutorService#shutdownNow()} 关闭执行智能体和消费事件的线程池。
     * 要求仍在运行的执行停止，但此方法不等待它们结束。重复调用无效果。
     */
    @Override
    public void close() {
        executor.shutdownNow();
        eventConsumerExecutor.shutdownNow();
        SdkInternals.stop(mainEventBusProcessor);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private AgentExecutor agentExecutor;

        private TaskStore taskStore;

        private MainEventBus mainEventBus;

        private QueueManager queueManager;

        private PushNotificationConfigStore pushConfigStore;

        private PushNotificationSender pushSender;

        public Builder agentExecutor(AgentExecutor agentExecutor) {
            this.agentExecutor = agentExecutor;
            return this;
        }

        public Builder taskStore(TaskStore taskStore) {
            this.taskStore = taskStore;
            return this;
        }

        /**
         * 设置将队列事件传给持久化及分派处理器的总线。自定义
         * {@link #queueManager(QueueManager) queue manager} 必须用此总线构建。
         * 默认创建新总线。
         *
         * @param mainEventBus 事件总线
         * @return 此构建器
         */
        public Builder mainEventBus(MainEventBus mainEventBus) {
            this.mainEventBus = mainEventBus;
            return this;
        }

        /**
         * 设置队列管理器。必须使用此处理器的
         * {@link #mainEventBus(MainEventBus) event bus} 构建，并同时设置该总线。
         *
         * @param queueManager 队列管理器
         * @return 此构建器
         */
        public Builder queueManager(QueueManager queueManager) {
            this.queueManager = queueManager;
            return this;
        }

        /**
         * 设置推送配置存储。尚不支持推送通知：不接受新推送配置，
         * 但已有配置仍会通过 {@link #pushSender(PushNotificationSender) push sender} 发送。
         *
         * @param pushConfigStore 推送配置存储
         * @return 此构建器
         */
        public Builder pushConfigStore(PushNotificationConfigStore pushConfigStore) {
            this.pushConfigStore = pushConfigStore;
            return this;
        }

        public Builder pushSender(PushNotificationSender pushSender) {
            this.pushSender = pushSender;
            return this;
        }

        public AgentScopeA2aRequestHandler build() {
            if (null == agentExecutor) {
                throw new IllegalArgumentException("AgentExecutor is required.");
            }
            if (null != queueManager && null == mainEventBus) {
                // 其队列事件会进入无人消费的总线。
                throw new IllegalArgumentException(
                        "A custom QueueManager needs the MainEventBus it was created with, set by"
                                + " mainEventBus(...).");
            }
            if (null == taskStore) {
                taskStore = new InMemoryTaskStore();
            }
            if (null == mainEventBus) {
                mainEventBus = new MainEventBus();
            }
            if (null == queueManager) {
                TaskStateProvider stateProvider =
                        taskStore instanceof TaskStateProvider provider
                                ? provider
                                : new AgentScopeTaskStateProvider(taskStore);
                queueManager = new InMemoryQueueManager(stateProvider, mainEventBus);
            }
            if (null == pushConfigStore) {
                pushConfigStore = new InMemoryPushNotificationConfigStore();
            }
            if (null == pushSender) {
                pushSender =
                        new BasePushNotificationSender(
                                pushConfigStore, new DefaultPushNotificationUrlValidator());
            }
            MainEventBusProcessor mainEventBusProcessor =
                    new MainEventBusProcessor(mainEventBus, taskStore, pushSender, queueManager);
            ExecutorService executor = Executors.newCachedThreadPool();
            ExecutorService eventConsumerExecutor = Executors.newCachedThreadPool();
            DefaultRequestHandler delegate =
                    DefaultRequestHandler.builder()
                            .agentExecutor(agentExecutor)
                            .taskStore(taskStore)
                            .queueManager(queueManager)
                            .pushConfigStore(pushConfigStore)
                            .mainEventBusProcessor(mainEventBusProcessor)
                            // TODO：支持自定义执行器。
                            .executor(executor)
                            .eventConsumerExecutor(eventConsumerExecutor)
                            // AgentScope 尚未认证调用方；若要求授权
                            // 却没有提供者，所有任务操作都会被拒绝。
                            // 【学习提示】当前这里关闭 SDK 任务授权要求；AgentCard 声明安全方案
                            // 不会自动建立认证。对外暴露时需由宿主接入层落实身份与访问边界。
                            .authorizationRequired(false)
                            // 智能体卡片声明不支持推送通知；若不设置此项，SDK
                            // 仍会保存随消息传来的推送配置。
                            .pushNotificationsEnabled(false)
                            .build();
            // 构建委托处理器已启动事件处理线程。
            AgentScopeA2aRequestHandler result =
                    new AgentScopeA2aRequestHandler(
                            delegate, executor, eventConsumerExecutor, mainEventBusProcessor);
            try {
                // 沿用之前的 60/10 秒：SDK 构建器设置的几秒钟
                // 对模型调用太短。
                SdkInternals.setTimeouts(delegate, 60, 10);
            } catch (RuntimeException e) {
                result.close();
                throw e;
            }
            return result;
        }
    }

    private record AgentScopeTaskStateProvider(TaskStore taskStore) implements TaskStateProvider {

        @Override
        public boolean isTaskActive(String taskId) {
            Task task = taskStore.get(taskId);
            if (task == null) {
                return false;
            }
            // 非最终状态的任务视为活动任务
            return task.status() == null
                    || task.status().state() == null
                    || !task.status().state().isFinal();
        }

        @Override
        public boolean isTaskFinalized(String taskId) {
            Task task = taskStore.get(taskId);
            if (task == null) {
                return false;
            }
            // 处于最终状态的任务视为已结束（忽略宽限期）
            return task.status() != null
                    && task.status().state() != null
                    && task.status().state().isFinal();
        }
    }
}
