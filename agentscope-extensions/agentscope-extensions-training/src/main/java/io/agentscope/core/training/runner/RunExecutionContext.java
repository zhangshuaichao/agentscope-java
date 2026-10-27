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

package io.agentscope.core.training.runner;

import io.agentscope.core.message.Msg;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 封装单次 Run 的执行信息。
 * <p>Task 表示用户原始请求，是业务级关联标识；Run 是同一 Task 的某次实验或训练执行。
 * <h2>关系示例</h2>
 * <pre>
 * Task "task-001" (User asks: "What's the weather today?")
 *   ├─ Run "0" → RunExecutionContext (msgIds=[msg-1, msg-2], msgs=[...], reward=0.8)
 *   ├─ Run "1" → RunExecutionContext (msgIds=[msg-3, msg-4], msgs=[...], reward=0.9)
 *   └─ Run "2" → RunExecutionContext (msgIds=[msg-5, msg-6], msgs=[...], reward=0.85)
 * </pre>
 * <p>数据包括任务 ID、从 0 递增的运行 ID、Trinity 模型交互返回的 msg_ids、
 * 供奖励计算的智能体消息，以及开始时间和耗时。
 * <h2>生命周期（原示意图）</h2>
 * <pre>
 * 1. TrainingRouter creates RunExecutionContext (corresponds to one Run)
 * 2. Passed to TrinityModelAdapter (automatically collects msg_ids)
 * 3. Shadow Agent execution completes, all data collected
 * 4. TrainingRouter calculates reward
 * 5. TrainingRouter submits Feedback using (taskId, runId, msg_ids, reward)
 * 6. Registered to TaskExecutionRegistry (optional, for later queries)
 * </pre>
 * <p>msg_ids 使用 {@link CopyOnWriteArrayList} 支持并发追加。
 * <p>【学习提示】集合单个操作线程安全不等于多步操作原子：setMessages 先 clear 再 addAll。
 * 读取返回的是可修改列表副本，不是不可修改视图，也不是 Msg 元素深拷贝。
 * @see TaskIdGenerator 自动生成任务 ID
 * @see RunRegistry 管理同任务的多次运行
 * @see TaskExecutionRegistry 保存运行上下文
 */
public class RunExecutionContext {

    /**
     * 用户请求的任务 ID，可手工指定或自动生成。
     */
    private final String taskId;

    /**
     * 同任务的第 N 次运行 ID，从 0 递增。
     */
    private final String runId;

    /**
     * 本次模型交互的全部消息 ID。
     */
    private final List<String> msgIds;

    /**
     * 用于奖励计算的本次智能体记忆消息。
     */
    private final List<Msg> msgs;

    /**
     * 本次运行的开始时间，毫秒时间戳。
     */
    private final long startTime;

    /**
     * 私有构造器。
     * @param taskId 任务 ID
     * @param runId 运行 ID
     */
    private RunExecutionContext(String taskId, String runId) {
        if (taskId == null || taskId.isEmpty()) {
            throw new IllegalArgumentException("Task ID cannot be null or empty");
        }
        if (runId == null || runId.isEmpty()) {
            throw new IllegalArgumentException("Run ID cannot be null or empty");
        }

        this.taskId = taskId;
        this.runId = runId;
        this.msgIds = new CopyOnWriteArrayList<>();
        this.msgs = new CopyOnWriteArrayList<>();
        this.startTime = System.currentTimeMillis();
    }

    /**
     * 创建运行上下文，通常由 TrainingRouter 拦截调用后创建。
     * @param taskId 任务 ID
     * @param runId 运行 ID
     * @return 运行上下文实例
     * @throws IllegalArgumentException taskId 或 runId 为 null／空值时抛出
     */
    public static RunExecutionContext create(String taskId, String runId) {
        return new RunExecutionContext(taskId, runId);
    }

    /**
     * 添加从 Trinity Chat API 响应 {@code response.getId()} 提取的消息 ID，可并发调用。
     * @param msgId 消息 ID，null 或空值会被忽略
     */
    public void addMsgId(String msgId) {
        if (msgId != null && !msgId.isEmpty()) {
            msgIds.add(msgId);
        }
    }

    /**
     * 获取全部消息 ID 的新列表副本，修改列表不影响内部集合。
     * @return 可修改的消息 ID 列表副本
     */
    public List<String> getMsgIds() {
        return new ArrayList<>(msgIds);
    }

    /**
     * 获取消息 ID 数量。
     * @return msg_ids 数量
     */
    public int getMsgIdCount() {
        return msgIds.size();
    }

    /**
     * 获取任务 ID。
     * @return 任务 ID
     */
    public String getTaskId() {
        return taskId;
    }

    /**
     * 获取运行 ID。
     * @return 运行 ID
     */
    public String getRunId() {
        return runId;
    }

    /**
     * 获取运行开始时间。
     * @return 毫秒时间戳
     */
    public long getStartTime() {
        return startTime;
    }

    /**
     * 获取运行至当前时刻的耗时。
     * @return 耗时毫秒数
     */
    public long getDuration() {
        return System.currentTimeMillis() - startTime;
    }

    /**
     * 检查是否收集到消息 ID。
     * @return 至少有一个 msg_id 时为 true
     */
    public boolean hasMsgIds() {
        return !msgIds.isEmpty();
    }

    /**
     * 添加消息，可并发调用。
     * @param msg 待添加消息，null 会被忽略
     */
    public void addMsg(Msg msg) {
        if (msg != null) {
            msgs.add(msg);
        }
    }

    /**
     * 批量设置消息，通常来自智能体记忆。
     * <p>先清空，再加入新列表。各集合操作线程安全，但这两步不是一次原子替换。
     * @param messages 消息列表，null 或空列表仅清除原消息
     */
    public void setMessages(List<Msg> messages) {
        msgs.clear();
        if (messages != null && !messages.isEmpty()) {
            msgs.addAll(messages);
        }
    }

    /**
     * 获取全部消息的新列表副本；副本中仍引用原 Msg 对象。
     * @return 可修改的消息列表副本
     */
    public List<Msg> getMessages() {
        return new ArrayList<>(msgs);
    }

    /**
     * 获取消息数量。
     * @return msgs 数量
     */
    public int getMessageCount() {
        return msgs.size();
    }

    /**
     * 检查是否收集到消息。
     * @return 至少有一条消息时为 true
     */
    public boolean hasMessages() {
        return !msgs.isEmpty();
    }

    @Override
    public String toString() {
        return String.format(
                "RunExecution{task=%s, run=%s, msgIds=%d, msgs=%d, duration=%dms}",
                taskId, runId, msgIds.size(), msgs.size(), getDuration());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RunExecutionContext that = (RunExecutionContext) o;
        return taskId.equals(that.taskId) && runId.equals(that.runId);
    }

    @Override
    public int hashCode() {
        int result = taskId.hashCode();
        result = 31 * result + runId.hashCode();
        return result;
    }
}
