/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.state;

import java.util.List;

/**
 * 计算状态列表哈希值的工具类。
 *
 * <p>为 AgentStateStore 实现提供变更检测所需的哈希计算，
 * 用于判断自上次保存以来列表是否被修改，而不仅仅是在末尾追加。
 *
 * <p>哈希计算采用采样策略，避免遍历大型列表：
 * <ul>
 *   <li>小型列表（不超过 5 个元素）：包含所有元素
 *   <li>大型列表：采样位置 0、1/4、1/2、3/4 和最后一个元素
 * </ul>
 *
 * <p>【学习提示】采样哈希是低成本变更信号，不是逐元素相等证明，更不是密码学完整性校验。
 * 未采样位置的改动可能不改变采样结果；理解这一取舍后再评估是否适合自己的存储场景。
 *
 * <p>在 AgentStateStore 实现中的用法：
 *
 * <pre>{@code
 * String currentHash = ListHashUtil.computeHash(values);
 * String storedHash = readStoredHash();
 *
 * if (storedHash != null && !storedHash.equals(currentHash)) {
 *     // 列表已有内容被修改，需要完整重写
 *     rewriteEntireList(values);
 * } else if (values.size() > existingCount) {
 *     // 列表增长，可以增量追加
 *     appendNewItems(values);
 * }
 * }</pre>
 */
public final class ListHashUtil {

    /** 空列表的哈希常量。 */
    private static final String EMPTY_HASH = "empty:0";

    /** 启用采样策略的阈值。 */
    private static final int SAMPLING_THRESHOLD = 5;

    private ListHashUtil() {
        // 工具类，禁止实例化
    }

    /**
     * 计算状态对象列表的哈希值。
     *
     * <p>哈希包含：
     * <ul>
     *   <li>列表大小
     *   <li>采样元素的哈希码
     * </ul>
     *
     * <p>此方法旨在轻量且快速，对大型列表采用采样，避免 O(n) 遍历。
     *
     * @param values 要计算哈希的状态对象列表
     * @return 表示列表内容的十六进制哈希字符串
     */
    public static String computeHash(List<? extends State> values) {
        if (values == null || values.isEmpty()) {
            return EMPTY_HASH;
        }

        int size = values.size();
        StringBuilder sb = new StringBuilder();
        sb.append("size:").append(size).append(";");

        // 根据列表大小获取采样下标
        int[] sampleIndices = getSampleIndices(size);

        for (int idx : sampleIndices) {
            State item = values.get(idx);
            int itemHash = item != null ? item.hashCode() : 0;
            sb.append(idx).append(":").append(itemHash).append(",");
        }

        return Integer.toHexString(sb.toString().hashCode());
    }

    /**
     * 获取指定大小列表的采样下标。
     *
     * <p>采样策略：
     * <ul>
     *   <li>大小 ≤ 5：返回所有下标 [0, 1, 2, ..., size-1]
     *   <li>大小 > 5：返回 [0, size/4, size/2, size*3/4, size-1]
     * </ul>
     *
     * @param size 列表大小
     * @return 要采样的下标数组
     */
    private static int[] getSampleIndices(int size) {
        if (size <= SAMPLING_THRESHOLD) {
            // 小型列表：采样所有元素
            int[] indices = new int[size];
            for (int i = 0; i < size; i++) {
                indices[i] = i;
            }
            return indices;
        }

        // 大型列表：在关键位置采样
        return new int[] {0, size / 4, size / 2, size * 3 / 4, size - 1};
    }

    /**
     * 根据哈希比较检查列表是否发生变化。
     *
     * @param currentHash 当前列表的哈希
     * @param storedHash 之前保存的哈希（可为 null）
     * @return 列表变化时为 true，否则为 false
     */
    public static boolean hasChanged(String currentHash, String storedHash) {
        if (storedHash == null) {
            // 没有之前的哈希，视为新列表
            return false;
        }
        return !storedHash.equals(currentHash);
    }

    /**
     * 根据列表内容和现有条目数量判断是否需要完整重写。
     *
     * @param currentValues 当前完整的状态对象列表
     * @param storedHash 之前保存的哈希（可为 null）
     * @param existingCount 已存储的条目数量
     * @return 需要完整重写时为 true；增量追加即可满足要求时为 false
     */
    public static boolean needsFullRewrite(
            List<? extends State> currentValues, String storedHash, int existingCount) {
        if (currentValues == null) {
            return existingCount > 0;
        }

        int currentSize = currentValues.size();

        // 情况 1：列表缩短（元素已被删除）
        if (currentSize < existingCount) {
            return true;
        }

        // 情况 2：缺少哈希，但存在已有数据（例如版本升级或哈希损坏）
        // 无法验证状态未被修改，因此必须重写。
        if (storedHash == null && existingCount > 0) {
            return true;
        }

        // 情况 3：检查之前已有的元素是否被修改。
        List<? extends State> prefix = currentValues.subList(0, existingCount);
        String prefixHash = computeHash(prefix);
        return hasChanged(prefixHash, storedHash);
    }
}
