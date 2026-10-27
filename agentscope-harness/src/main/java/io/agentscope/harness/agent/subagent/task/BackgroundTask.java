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
package io.agentscope.harness.agent.subagent.task;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 包装 {@link CompletableFuture}，跟踪后台子智能体任务的状态、结果、错误及
 * 生命周期元数据。通过委托给底层 future 保证线程安全。
 *
 * <p>生命周期元数据：
 * <ul>
 *   <li>{@code agentId}：正在执行的子智能体类型
 *   <li>{@code createdAt}：任务创建时间
 *   <li>{@code lastCheckedAt}：最近一次轮询任务状态的时间
 * </ul>
 */
public class BackgroundTask {

    private final String taskId;
    private final String agentId;
    private final CompletableFuture<String> future;
    private final Instant createdAt;
    private volatile Instant lastCheckedAt;
    private volatile boolean cancelled;

    public BackgroundTask(String taskId, String agentId, CompletableFuture<String> future) {
        this.taskId = taskId;
        this.agentId = agentId;
        this.future = future;
        this.createdAt = Instant.now();
        this.lastCheckedAt = this.createdAt;
    }

    /**
     * 观察底层 future；工作区本地任务会先持久化结果，再完成该 future。
     */
    public BackgroundTask whenComplete(java.util.function.BiConsumer<String, Throwable> action) {
        future.whenComplete(action);
        return this;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getAgentId() {
        return agentId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastCheckedAt() {
        return lastCheckedAt;
    }

    public void updateLastCheckedAt() {
        this.lastCheckedAt = Instant.now();
    }

    public boolean isCompleted() {
        return future.isDone();
    }

    /**
     * 返回此任务的结构化 {@link TaskStatus}，反映底层 future 状态及显式取消。
     */
    public TaskStatus getTaskStatus() {
        if (cancelled || future.isCancelled()) {
            return TaskStatus.CANCELLED;
        }
        if (future.isCompletedExceptionally()) {
            return TaskStatus.FAILED;
        }
        if (future.isDone()) {
            return TaskStatus.COMPLETED;
        }
        return TaskStatus.RUNNING;
    }

    /**
     * 返回供人阅读的状态字符串。
     */
    public String getStatus() {
        TaskStatus ts = getTaskStatus();
        if (ts == TaskStatus.FAILED) {
            Exception error = getError();
            return "Failed: " + (error != null ? error.getMessage() : "Unknown error");
        }
        return ts.name().charAt(0) + ts.name().substring(1).toLowerCase();
    }

    /**
     * 非阻塞地查看结果；尚未完成或发生错误时返回 null。
     */
    public String getResult() {
        try {
            return future.getNow(null);
        } catch (Exception e) {
            return null;
        }
    }

    public Exception getError() {
        if (future.isCompletedExceptionally()) {
            try {
                future.getNow(null);
            } catch (Exception e) {
                return (e.getCause() instanceof Exception cause) ? cause : e;
            }
        }
        return null;
    }

    /**
     * 阻塞直到任务完成或超时。
     *
     * @return 在超时前完成返回 true，超时返回 false
     */
    public boolean waitForCompletion(long timeoutMs) throws InterruptedException {
        if (future.isDone()) {
            return true;
        }
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            throw e;
        } catch (TimeoutException e) {
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 取消任务。设置内部取消标志，即使 future 无法中断，
     * {@link #getTaskStatus()} 也会返回 {@link TaskStatus#CANCELLED}。
     */
    public boolean cancel(boolean mayInterruptIfRunning) {
        this.cancelled = true;
        return future.cancel(mayInterruptIfRunning);
    }
}
