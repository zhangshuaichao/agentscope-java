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
package io.agentscope.dataagent.web.usage;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

/** 内存用量事件存储，逐轮记录事件（每条用户消息一项），并按小时/天汇总供趋势图使用。<p>数据<em>不会</em>跨重启持久化，这是当前阶段的有意设计；以后可替换为持久化实现。 */
@Component
public class UsageStore {

    /** 内存保留原始事件数上限（滚动窗口）。 */
    private static final int MAX_EVENTS = 50_000;

    private final CopyOnWriteArrayList<UsageEvent> events = new CopyOnWriteArrayList<>();

    /** 记录单个轮次完成。 */
    public void record(String userId, String agentId, long durationMs) {
        events.add(new UsageEvent(System.currentTimeMillis(), userId, agentId, durationMs));
        if (events.size() > MAX_EVENTS) {
            events.remove(0);
        }
    }

    /** 返回最多 {@code limit} 条原始事件，最新在前。 */
    public List<UsageEvent> recentEvents(int limit) {
        List<UsageEvent> copy = new ArrayList<>(events);
        Collections.reverse(copy);
        return copy.stream().limit(limit).toList();
    }

    /**
     * 返回过去 {@code hours} 小时的逐小时轮次数。
     * @param hours 包含的历史小时数（最多 168，即 7 天）
     */
    public List<BucketCount> hourlyTurns(int hours) {
        int h = Math.max(1, Math.min(hours, 168));
        long nowMs = System.currentTimeMillis();
        long startMs = nowMs - (long) h * 3_600_000L;

        Map<Long, Integer> buckets = new TreeMap<>();
        // 预先将各小时填充为 0
        for (int i = 0; i < h; i++) {
            long bucketMs = startMs + (long) i * 3_600_000L;
            buckets.put(truncateHour(bucketMs), 0);
        }
        for (UsageEvent e : events) {
            if (e.timestampMs() < startMs) continue;
            long bucket = truncateHour(e.timestampMs());
            buckets.merge(bucket, 1, Integer::sum);
        }

        return buckets.entrySet().stream()
                .map(en -> new BucketCount(en.getKey(), labelHour(en.getKey()), en.getValue()))
                .toList();
    }

    /**
     * 返回过去 {@code days} 天的逐日轮次数。
     * @param days 包含的历史天数（最多 90）
     */
    public List<BucketCount> dailyTurns(int days) {
        int d = Math.max(1, Math.min(days, 90));
        long nowMs = System.currentTimeMillis();
        long startMs = nowMs - (long) d * 86_400_000L;

        Map<Long, Integer> buckets = new TreeMap<>();
        for (int i = 0; i < d; i++) {
            long bucketMs = startMs + (long) i * 86_400_000L;
            buckets.put(truncateDay(bucketMs), 0);
        }
        for (UsageEvent e : events) {
            if (e.timestampMs() < startMs) continue;
            long bucket = truncateDay(e.timestampMs());
            buckets.merge(bucket, 1, Integer::sum);
        }

        return buckets.entrySet().stream()
                .map(en -> new BucketCount(en.getKey(), labelDay(en.getKey()), en.getValue()))
                .toList();
    }

    /** 仅返回指定用户的汇总总量。 */
    public UsageSummary summaryForUser(String userId) {
        List<UsageEvent> mine = events.stream().filter(e -> userId.equals(e.userId())).toList();
        long totalTurns = mine.size();
        long today = truncateDay(System.currentTimeMillis());
        long todayTurns = mine.stream().filter(e -> truncateDay(e.timestampMs()) == today).count();
        long avgDurationMs =
                mine.isEmpty()
                        ? 0L
                        : (long)
                                mine.stream().mapToLong(UsageEvent::durationMs).average().orElse(0);
        return new UsageSummary(totalTurns, todayTurns, avgDurationMs, 1L);
    }

    /** 返回单个用户过去 {@code hours} 小时的逐小时轮次数。 */
    public List<BucketCount> hourlyTurnsForUser(String userId, int hours) {
        int h = Math.max(1, Math.min(hours, 168));
        long nowMs = System.currentTimeMillis();
        long startMs = nowMs - (long) h * 3_600_000L;
        Map<Long, Integer> buckets = new TreeMap<>();
        for (int i = 0; i < h; i++) {
            buckets.put(truncateHour(startMs + (long) i * 3_600_000L), 0);
        }
        for (UsageEvent e : events) {
            if (!userId.equals(e.userId()) || e.timestampMs() < startMs) continue;
            buckets.merge(truncateHour(e.timestampMs()), 1, Integer::sum);
        }
        return buckets.entrySet().stream()
                .map(en -> new BucketCount(en.getKey(), labelHour(en.getKey()), en.getValue()))
                .toList();
    }

    /** 返回单个用户过去 {@code days} 天的逐日轮次数。 */
    public List<BucketCount> dailyTurnsForUser(String userId, int days) {
        int d = Math.max(1, Math.min(days, 90));
        long nowMs = System.currentTimeMillis();
        long startMs = nowMs - (long) d * 86_400_000L;
        Map<Long, Integer> buckets = new TreeMap<>();
        for (int i = 0; i < d; i++) {
            buckets.put(truncateDay(startMs + (long) i * 86_400_000L), 0);
        }
        for (UsageEvent e : events) {
            if (!userId.equals(e.userId()) || e.timestampMs() < startMs) continue;
            buckets.merge(truncateDay(e.timestampMs()), 1, Integer::sum);
        }
        return buckets.entrySet().stream()
                .map(en -> new BucketCount(en.getKey(), labelDay(en.getKey()), en.getValue()))
                .toList();
    }

    /** 返回过去 {@code days} 天按轮次数排名前 N 的用户，供管理员用量面板使用。 */
    public List<GroupCount> topUsersByTurns(int days, int topN) {
        long startMs = System.currentTimeMillis() - (long) days * 86_400_000L;
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (UsageEvent e : events) {
            if (e.timestampMs() < startMs || e.userId() == null) continue;
            counts.merge(e.userId(), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(topN)
                .map(en -> new GroupCount(en.getKey(), en.getValue()))
                .toList();
    }

    /** 返回过去 {@code days} 天按轮次数排名前 N 的智能体，供管理员用量面板使用。 */
    public List<GroupCount> topAgentsByTurns(int days, int topN) {
        long startMs = System.currentTimeMillis() - (long) days * 86_400_000L;
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (UsageEvent e : events) {
            if (e.timestampMs() < startMs || e.agentId() == null) continue;
            counts.merge(e.agentId(), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(topN)
                .map(en -> new GroupCount(en.getKey(), en.getValue()))
                .toList();
    }

    /** 返回汇总总量。 */
    public UsageSummary summary() {
        long totalTurns = events.size();
        long today = truncateDay(System.currentTimeMillis());
        long todayTurns =
                events.stream().filter(e -> truncateDay(e.timestampMs()) == today).count();
        long avgDurationMs =
                events.isEmpty()
                        ? 0L
                        : (long)
                                events.stream()
                                        .mapToLong(UsageEvent::durationMs)
                                        .average()
                                        .orElse(0);
        long uniqueUsers =
                events.stream()
                        .map(UsageEvent::userId)
                        .filter(u -> u != null && !u.isBlank())
                        .distinct()
                        .count();
        return new UsageSummary(totalTurns, todayTurns, avgDurationMs, uniqueUsers);
    }

    // -----------------------------------------------------------------
    // 内部辅助方法
    // -----------------------------------------------------------------

    private static long truncateHour(long epochMs) {
        return Instant.ofEpochMilli(epochMs)
                .atZone(ZoneId.systemDefault())
                .truncatedTo(ChronoUnit.HOURS)
                .toInstant()
                .toEpochMilli();
    }

    private static long truncateDay(long epochMs) {
        return Instant.ofEpochMilli(epochMs)
                .atZone(ZoneId.systemDefault())
                .truncatedTo(ChronoUnit.DAYS)
                .toInstant()
                .toEpochMilli();
    }

    private static String labelHour(long epochMs) {
        ZonedDateTime zdt = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault());
        return String.format("%02d:%02d", zdt.getHour(), 0);
    }

    private static String labelDay(long epochMs) {
        ZonedDateTime zdt = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault());
        return String.format("%02d-%02d", zdt.getMonthValue(), zdt.getDayOfMonth());
    }

    // -----------------------------------------------------------------
    // 数据传输对象类型
    // -----------------------------------------------------------------

    public record UsageEvent(long timestampMs, String userId, String agentId, long durationMs) {}

    public record BucketCount(long epochMs, String label, int count) {}

    public record GroupCount(String key, int count) {}

    public record UsageSummary(
            long totalTurns, long todayTurns, long avgDurationMs, long uniqueUsers) {}
}
