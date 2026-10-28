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
package io.agentscope.builder.web.managed;

import java.util.Set;

/** 各平面共享的环境模板类型常量。控制平面负责环境生命周期；数据平面在构建智能体和获取 Hands 租约时读取这些类型。 */
public final class EnvironmentTypes {

    /** 本地宿主文件系统 Hands。 */
    public static final String TYPE_LOCAL = "local";

    /** 等效于云环境的 Docker Hands（E2B 沙箱）。 */
    public static final String TYPE_SANDBOX = "sandbox";

    /** 分布式 KV 文件系统，无 shell。 */
    public static final String TYPE_REMOTE = "remote";

    /** 通过 {@code externalSandbox} 使用工作进程拥有的 Hands（自托管环境工作进程）。 */
    public static final String TYPE_SELF_HOSTED = "self_hosted";

    /** 环境共享检查使用的 ACL 资源类型。 */
    public static final String RESOURCE_TYPE = "environment";

    public static final Set<String> VALID_TYPES =
            Set.of(TYPE_LOCAL, TYPE_SANDBOX, TYPE_REMOTE, TYPE_SELF_HOSTED);

    private EnvironmentTypes() {}
}
