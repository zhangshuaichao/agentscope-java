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
package io.agentscope.extensions.channel.weixin;

/**
 * 本模块定义的操作失败，其消息可安全报告给宿主。消息必须仅使用常量和协议数字码，绝不包含服务方文本、载荷或请求 URI。
 */
final class WeixinOperationException extends IllegalStateException {

    WeixinOperationException(String message) {
        super(message);
    }
}
