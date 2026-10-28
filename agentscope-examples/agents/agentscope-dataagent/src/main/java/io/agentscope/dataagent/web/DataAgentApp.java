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
package io.agentscope.dataagent.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** agentscope-dataagent Spring Boot 应用入口。<p>DataAgent 基于 {@code HarnessAgent}，面向多租户及可分布式部署场景。提供所有租户可用的内置 {@code data-agent}（SQL / 图表 / 探索 / 报告编写），以及位于隔离工作区的逐用户自定义数据智能体。具体数据工具在此版本中仍有占位实现，部署约束见配置类。<p>主要界面为 {@code classpath:/static/} 提供的 React SPA。外部 IM 和工单系统可通过钉钉、通用 Webhook 等旁路适配器调用租户智能体，并进入相同的每用户会话存储。<p>此类刻意保持精简，装配逻辑位于 {@code runtime/} 和 {@code web/} 包，通过组件扫描发现。 */
@SpringBootApplication(scanBasePackages = "io.agentscope.dataagent")
public class DataAgentApp {

    public static void main(String[] args) {
        SpringApplication.run(DataAgentApp.class, args);
    }
}
