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
package io.agentscope.extensions.redis.state;

import java.util.List;
import java.util.Set;

/**
 * Redis 客户端操作的适配接口。
 *
 * <p>统一 Jedis、Lettuce、Redisson 的调用差异，使 RedisAgentStateStore 无需修改即可使用不同客户端。
 *
 * <p>方法采用一致且自解释的命名：
 * <ul><li>字符串：{@code set}、{@code get}。</li>
 * <li>列表：{@code rightPushList}、{@code rangeList}、{@code getListLength}。</li>
 * <li>集合：{@code addToSet}、{@code getSetMembers}、{@code getSetSize}。</li>
 * <li>键：{@code keyExists}、{@code deleteKeys}、{@code findKeysByPattern}。</li></ul>
 */
public interface RedisClientAdapter {

    /**
     * 设置字符串值。
     * @param key Redis 键
     * @param value 字符串值
     */
    void set(String key, String value);

    /**
     * 获取字符串值。
     * @param key Redis 键
     * @return 字符串值；不存在时为 null
     */
    String get(String key);

    /**
     * 向列表右端追加值。
     * @param key Redis 列表键
     * @param value 待追加的值
     */
    void rightPushList(String key, String value);

    /**
     * 获取列表指定范围的元素。
     * @param key Redis 列表键
     * @param start 起始索引，包含端点
     * @param end 结束索引，包含端点；-1 表示直到末尾
     * @return 值列表
     */
    List<String> rangeList(String key, long start, long end);

    /**
     * 获取列表长度。
     * @param key Redis 列表键
     * @return 列表长度
     */
    long getListLength(String key);

    /**
     * 删除一个或多个键。
     * @param keys 待删除的键
     */
    void deleteKeys(String... keys);

    /**
     * 向集合添加成员。
     * @param key Redis 集合键
     * @param member 待添加成员
     */
    void addToSet(String key, String member);

    /**
     * 获取集合全部成员。
     * @param key Redis 集合键
     * @return 成员集合
     */
    Set<String> getSetMembers(String key);

    /**
     * 获取集合成员数量。
     * @param key Redis 集合键
     * @return 成员数量
     */
    long getSetSize(String key);

    /**
     * 检查键是否存在。
     * @param key Redis 键
     * @return 存在时为 true
     */
    boolean keyExists(String key);

    /**
     * 查找匹配模式的所有键。
     * @param pattern 键模式，例如 "prefix:*"
     * @return 匹配键集合
     */
    Set<String> findKeysByPattern(String pattern);

    /**
     * 原子执行 Lua 脚本。
     * @param script Lua 脚本源码
     * @param keys 作为 {@code KEYS} 传入的 Redis 键
     * @param args 作为 {@code ARGV} 传入的 Redis 参数
     * @return 脚本返回的 long 值
     */
    long evalScript(String script, List<String> keys, List<String> args);

    /**
     * 关闭适配器并释放资源。
     */
    void close();
}
