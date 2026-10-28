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
package io.agentscope.spring.boot.admin.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 线程安全的 {@link AdminCommand} 注册表。
 *
 * <p>内置命令由 {@code BuiltinCommandRegistrar} 在启动时填充。第三方 starter 或业务代码可通过 {@link #register(AdminCommand)} 添加命令；注册表是 {@code /actuator/agentscope-commands} 目录的唯一事实来源。
 */
public final class AdminCommandRegistry {

    private final ConcurrentMap<String, AdminCommand> commands = new ConcurrentHashMap<>();

    /**
     * 注册命令。完全相同条目重复注册幂等；同一 {@code id} 已存在不同命令时抛出异常。
     */
    public void register(AdminCommand command) {
        AdminCommand prev = commands.putIfAbsent(command.id(), command);
        if (prev != null && !prev.equals(command)) {
            throw new IllegalStateException(
                    "AdminCommand id collision: "
                            + command.id()
                            + " already registered as "
                            + prev);
        }
    }

    public Optional<AdminCommand> find(String id) {
        return Optional.ofNullable(commands.get(id));
    }

    /**
 * 返回所有已注册命令，先按 {@code category} 再按 {@code id} 排序，确保输出稳定。
 */
    public List<AdminCommand> list() {
        List<AdminCommand> out = new ArrayList<>(commands.values());
        out.sort(Comparator.comparing(AdminCommand::category).thenComparing(AdminCommand::id));
        return out;
    }

    public int size() {
        return commands.size();
    }
}
