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
package io.agentscope.builder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Builder <b>调度平面</b>入口。
 * <p>承载其他平面请求路径之外的执行工作：
 * <ul>
 * <li><b>即时消息通道运行时</b>（钉钉、飞书、企业微信、GitHub、GitLab）：从控制平面内部 API 拉取通道配置；入站消息桥接到托管会话（控制平面查找或创建，数据平面接收轮次事件）。
 * <li><b>出站投递</b>：{@code /api/outbound/send} 将智能体主动消息推送到已注册通道。
 * <li><b>Cron 部署</b>：检测到期并获取触发租约，再调用控制平面触发 API。
 * <li><b>Hands Worker</b>：{@code io.agentscope.builder.worker.HandsWorkerMain} 是同一 jar 中提供的独立自行托管环境 Worker 入口。
 * </ul>
 * <p>调度器从不构建 {@code HarnessAgent} 实例（由数据平面负责），也不管理目录定义或会话生命周期（由控制平面负责）。
 */
@SpringBootApplication(
        scanBasePackages = {
            "io.agentscope.builder.runtime",
            "io.agentscope.builder.web.auth",
            "io.agentscope.builder.web.catalog",
            "io.agentscope.builder.web.config",
            "io.agentscope.builder.web.coord",
            "io.agentscope.builder.web.managed",
            "io.agentscope.builder.web.persistence",
            "io.agentscope.builder.web.share",
            "io.agentscope.builder.worker",
            // 通道回调控制器（飞书、企业微信、GitHub、GitLab）位于
            // extensions 包中；缺少这些包时，回调路径 /api/channels/...
            // 会直接返回 404，即使通道本身启动正常也不会留下回调处理记录。
            "io.agentscope.extensions.channel.feishu",
            "io.agentscope.extensions.channel.wecom",
            "io.agentscope.extensions.channel.github",
            "io.agentscope.extensions.channel.gitlab"
        })
public class SchedulerApp {
    public static void main(String[] args) {
        SpringApplication.run(SchedulerApp.class, args);
    }
}
