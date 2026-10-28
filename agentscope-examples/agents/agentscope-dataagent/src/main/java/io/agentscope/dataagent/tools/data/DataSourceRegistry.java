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
package io.agentscope.dataagent.tools.data;

import java.util.List;
import java.util.Optional;

/** DataAgent 部署暴露的、由管理员维护的 {@link DataSource} 描述符集合 SPI。实现置于 Spring {@code @Bean} 后，运维可替换为 JPA、Nacos 或服务发现后端，而不修改工具包代码。<p>对智能体而言注册表只读；管理写入路径（CRUD 界面、REST）不在 v1 范围内，运维通过 {@code agentscope.json} 或专用 Spring {@code @Bean} 初始化注册表。 */
public interface DataSourceRegistry {

    /** 列出所有已配置数据源。 */
    List<DataSource> list();

    /** 按 ID 查找数据源。 */
    Optional<DataSource> findById(String id);
}
