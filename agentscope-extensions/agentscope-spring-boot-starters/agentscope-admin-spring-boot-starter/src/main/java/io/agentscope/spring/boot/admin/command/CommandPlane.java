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

/**
 * 命令所在的实际接口层。
 *
 * <ul>
 *   <li>{@link #DATA}：会话所有者或业务调用方使用的逐会话操作（压缩、中止、导出等），通过管理基础路径下的 REST 提供。
 *   <li>{@link #CONTROL}：SRE／运维使用的进程级操作（状态、排空、关闭、自检等），通过 {@code management.server.port} 上的 Spring Boot Actuator 提供。
 * </ul>
 */
public enum CommandPlane {
    DATA,
    CONTROL
}
