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
package io.agentscope.examples.documentation2.copilotkit.workbench;

import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.state.Task;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * CopilotKit 工作台的线程级共享状态。
 * <p>每次修改都将 {@link CustomEvent} 入队，由 {@code WorkbenchEventMiddleware} 在流式执行中取出，再由 {@code WorkbenchAguiEventConverter} 转为 {@code STATE_SNAPSHOT} / {@code STATE_DELTA} / {@code STEP_STARTED} / {@code STEP_FINISHED} / {@code ACTIVITY_SNAPSHOT} / {@code CUSTOM}，使进度及权限决定在运行中即可显示。
 * <p>所有修改方法同步，因为工具可能并行执行。
 */
public final class WorkbenchState {

    /**
     * 携带完整工作台快照，转为 STATE_SNAPSHOT 或 STATE_DELTA。
     */
    public static final String EVENT_STATE_UPDATED = "state_updated";

    /**
     * 计划任务进入 {@code in_progress}，转为 STEP_STARTED。
     */
    public static final String EVENT_STEP_STARTED = "plan_step_started";

    /**
     * 计划任务达到 {@code completed}，转为 STEP_FINISHED。
     */
    public static final String EVENT_STEP_FINISHED = "plan_step_finished";

    /**
     * 携带 A2UI 操作，转为 {@code a2ui-surface} ACTIVITY_SNAPSHOT。
     */
    public static final String EVENT_A2UI_SURFACE = "a2ui_surface";

    /**
     * 携带权限决定，转为审计面板的 CUSTOM 事件。
     */
    public static final String EVENT_PERMISSION_AUDIT = "permission_audit";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_AUDIT_ENTRIES = 30;

    private final String threadId;
    private final Deque<CustomEvent> pending = new ConcurrentLinkedDeque<>();

    private String topic = "AgentScope 2.0 × CopilotKit 能力演示";
    private String priority = "中";
    private String status = "空闲";
    private boolean approved;
    private String updatedAt = LocalTime.now().format(TIME);

    private String planGoal;
    private String planPhase = "idle";
    private final List<Map<String, Object>> tasks = new ArrayList<>();

    /**
     * 已发出 {@code STEP_STARTED} 但尚未发出 {@code STEP_FINISHED} 的步骤。
     * <p>任务 ID 映射到实际线路上的 {@code stepName}；存在活动步骤时 AG-UI 拒绝 {@code RUN_FINISHED}，且 {@code STEP_FINISHED} 必须复用对应开始名称。
     */
    private final Map<String, String> openSteps = new LinkedHashMap<>();

    private final List<Map<String, Object>> permissionAudit = new ArrayList<>();

    private Map<String, Object> metrics = Map.of();

    private volatile Set<String> clientComponents = Set.of();

    private String a2uiSurfaceId;
    private String a2uiCatalogId;
    private String a2uiGeneratedBy;
    private String a2uiIntent;
    private int a2uiComponentCount;

    WorkbenchState(String threadId) {
        this.threadId = threadId;
    }

    public String getThreadId() {
        return threadId;
    }

    /**
     * 浏览器声明可渲染的自定义 A2UI 组件。通过 {@code useAgentContext} 报告，由 {@code WorkbenchEventMiddleware} 获取，只向模型公布客户端已有渲染器的组件。空集合表示浏览器未声明，使用服务端完整目录。
     */
    public Set<String> getClientComponents() {
        return clientComponents;
    }

    public void setClientComponents(Set<String> names) {
        if (names != null && !names.isEmpty()) {
            this.clientComponents = Set.copyOf(names);
        }
    }

    /**
     * 状态的不可变视图，形状与浏览器共享状态对象一致。
     */
    public synchronized Map<String, Object> snapshot() {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("goal", planGoal);
        plan.put("phase", planPhase);
        plan.put("tasks", List.copyOf(tasks));
        plan.put("total", tasks.size());
        plan.put("completed", completedCount());
        plan.put("progress", progress());

        Map<String, Object> a2ui = new LinkedHashMap<>();
        a2ui.put("surfaceId", a2uiSurfaceId);
        a2ui.put("catalogId", a2uiCatalogId);
        a2ui.put("generatedBy", a2uiGeneratedBy);
        a2ui.put("intent", a2uiIntent);
        a2ui.put("componentCount", a2uiComponentCount);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("topic", topic);
        snapshot.put("priority", priority);
        snapshot.put("status", status);
        snapshot.put("approved", approved);
        snapshot.put("updatedAt", updatedAt);
        snapshot.put("plan", plan);
        snapshot.put("permissionAudit", List.copyOf(permissionAudit));
        snapshot.put("metrics", metrics);
        snapshot.put("a2ui", a2ui);
        return snapshot;
    }

    private long completedCount() {
        return tasks.stream().filter(task -> "completed".equals(task.get("state"))).count();
    }

    private int progress() {
        if (tasks.isEmpty()) {
            return 0;
        }
        return (int) Math.round(completedCount() * 100.0 / tasks.size());
    }

    /**
     * 用浏览器随运行发送的 {@code state} 对象初始化状态。
     */
    public synchronized void mergeFromClient(Map<String, Object> clientState) {
        if (clientState == null || clientState.isEmpty()) {
            return;
        }
        if (clientState.get("topic") instanceof String value && !value.isBlank()) {
            topic = value;
        }
        if (clientState.get("priority") instanceof String value && !value.isBlank()) {
            priority = value;
        }
        if (clientState.get("status") instanceof String value && !value.isBlank()) {
            status = value;
        }
        if (clientState.get("approved") instanceof Boolean value) {
            approved = value;
        }
    }

    public synchronized String updateBrief(String newTopic, String newPriority, String newStatus) {
        if (newTopic != null && !newTopic.isBlank()) {
            topic = newTopic.trim();
        }
        if (newPriority != null && !newPriority.isBlank()) {
            priority = newPriority.trim();
        }
        if (newStatus != null && !newStatus.isBlank()) {
            status = newStatus.trim();
        }
        touch();
        return "已更新：主题=%s，优先级=%s，状态=%s".formatted(topic, priority, status);
    }

    public synchronized void setPlanGoal(String goal) {
        if (goal != null && !goal.isBlank()) {
            planGoal = goal.trim();
            touch();
        }
    }

    /**
     * 将实时任务列表镜像到共享状态。与上次镜像比较，进入 {@code in_progress} 或 {@code completed} 时同时入队步骤事件。跟踪开放步骤，使 {@link #closeOpenSteps()} 保证 {@code RUN_FINISHED} 前每个 {@code STEP_STARTED} 都有 {@code STEP_FINISHED}。
     * @param liveTasks 当前 {@code AgentState.getTasksContext().getTasks()}
     * @return 有变更且状态帧已入队时为 true
     */
    public synchronized boolean syncTasks(List<Task> liveTasks) {
        List<Map<String, Object>> mirrored = new ArrayList<>();
        for (Task task : liveTasks) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", task.getId());
            entry.put("title", task.getSubject());
            entry.put("state", task.getState().getWire());
            Object taskPriority = task.getMetadata().get("priority");
            entry.put("priority", taskPriority == null ? "medium" : taskPriority);
            mirrored.add(entry);
        }
        if (mirrored.equals(tasks)) {
            return false;
        }

        Map<String, String> previousStates = new LinkedHashMap<>();
        for (Map<String, Object> task : tasks) {
            previousStates.put(String.valueOf(task.get("id")), String.valueOf(task.get("state")));
        }
        tasks.clear();
        tasks.addAll(mirrored);

        Set<String> liveIds = new LinkedHashSet<>();
        for (Map<String, Object> task : mirrored) {
            String id = String.valueOf(task.get("id"));
            liveIds.add(id);
            String state = String.valueOf(task.get("state"));
            if (state.equals(previousStates.get(id))) {
                continue;
            }
            String title = String.valueOf(task.get("title"));
            if ("in_progress".equals(state)) {
                openStep(id, title);
            } else if ("completed".equals(state)) {
                // AG-UI 禁止同名步骤未先 STEP_STARTED 就发出 STEP_FINISHED。
                if (!openSteps.containsKey(id)) {
                    openStep(id, title);
                }
                finishStep(id);
            } else if (openSteps.containsKey(id)) {
                // 离开 in_progress 到其他状态（如 pending）时仍须关闭步骤。
                finishStep(id);
            }
        }
        // 计划中删除仍开放的任务，否则会阻止 RUN_FINISHED。
        for (String id : List.copyOf(openSteps.keySet())) {
            if (!liveIds.contains(id)) {
                finishStep(id);
            }
        }

        long done = completedCount();
        if (tasks.isEmpty()) {
            planPhase = "idle";
        } else if (done == tasks.size()) {
            planPhase = "completed";
        } else if (done > 0 || tasks.stream().anyMatch(t -> "in_progress".equals(t.get("state")))) {
            planPhase = "executing";
        } else {
            planPhase = "planning";
        }
        status = "计划进度 %d/%d".formatted(done, tasks.size());
        touch();
        return true;
    }

    public synchronized void recordMetrics(Map<String, Object> newMetrics) {
        metrics = newMetrics == null ? Map.of() : Map.copyOf(newMetrics);
        touch();
    }

    /**
     * 向浏览器发布新生成的 A2UI 界面。
     */
    public synchronized void recordA2uiSurface(
            String surfaceId,
            String catalogId,
            String intent,
            String generatedBy,
            int componentCount,
            List<Map<String, Object>> operations) {
        this.a2uiSurfaceId = surfaceId;
        this.a2uiCatalogId = catalogId;
        this.a2uiIntent = intent;
        this.a2uiGeneratedBy = generatedBy;
        this.a2uiComponentCount = componentCount;
        status = "已生成 A2UI 界面（%s）".formatted(generatedBy);
        touch();

        Map<String, Object> payload = new LinkedHashMap<>();
        // CopilotKit A2UI 消息渲染器仅在找到此精确键时绘制
        // （@copilotkit/react-core 的 A2UI_OPERATIONS_KEY）；其他内容只是元数据。
        payload.put("a2ui_operations", operations);
        payload.put("surfaceId", surfaceId);
        payload.put("catalogId", catalogId);
        payload.put("intent", intent);
        payload.put("generatedBy", generatedBy);
        pending.add(new CustomEvent(EVENT_A2UI_SURFACE, payload));
    }

    /**
     * 即使未变化也入队状态帧，用于每轮开始。
     */
    public synchronized void queueSnapshot() {
        pending.add(new CustomEvent(EVENT_STATE_UPDATED, snapshot()));
    }

    /**
     * 清除上一运行遗留开放步骤记录，不发出事件。{@code AgentStartEvent} 时调用；此前以 {@code RUN_ERROR} 结束可在线路上留下未关闭步骤，但名称不能泄漏到下一运行配对。
     */
    public synchronized void clearOpenSteps() {
        openSteps.clear();
    }

    /**
     * 为所有仍开放步骤发出 {@code STEP_FINISHED}。必须在 {@code AgentEndEvent} 转为 {@code RUN_FINISHED} 前执行；存在活动步骤时 AG-UI 验证器拒绝完成运行。
     */
    public synchronized void closeOpenSteps() {
        for (String id : List.copyOf(openSteps.keySet())) {
            finishStep(id);
        }
    }

    private void openStep(String taskId, String title) {
        if (openSteps.containsKey(taskId)) {
            return;
        }
        String step = resolveStepName(taskId, title);
        openSteps.put(taskId, step);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", taskId);
        payload.put("step", step);
        pending.add(new CustomEvent(EVENT_STEP_STARTED, payload));
    }

    private void finishStep(String taskId) {
        String step = openSteps.remove(taskId);
        if (step == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", taskId);
        payload.put("step", step);
        pending.add(new CustomEvent(EVENT_STEP_FINISHED, payload));
    }

    /**
     * 选择在当前开放步骤中唯一的线路 {@code stepName}。优先使用可读标题；重名时附加任务 ID，避免第二个 {@code STEP_STARTED} 被判为已活动。
     */
    private String resolveStepName(String taskId, String title) {
        String base =
                title == null || title.isBlank() || "null".equals(title) ? taskId : title.trim();
        for (Map.Entry<String, String> entry : openSteps.entrySet()) {
            if (!entry.getKey().equals(taskId) && base.equals(entry.getValue())) {
                return base + " [" + taskId + "]";
            }
        }
        return base;
    }

    private void touch() {
        updatedAt = LocalTime.now().format(TIME);
        pending.add(new CustomEvent(EVENT_STATE_UPDATED, snapshot()));
    }

    /**
     * 移除并返回上次取出之后的所有排队事件。
     */
    public List<CustomEvent> drainPendingEvents() {
        List<CustomEvent> drained = new ArrayList<>();
        CustomEvent event;
        while ((event = pending.poll()) != null) {
            drained.add(event);
        }
        return drained;
    }
}
