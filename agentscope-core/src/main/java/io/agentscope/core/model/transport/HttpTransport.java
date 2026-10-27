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
package io.agentscope.core.model.transport;

import reactor.core.publisher.Flux;

/**
 * 发起 HTTP 请求的 HTTP 传输层接口。
 *
 * <p>抽象实际 HTTP 客户端实现，使 OkHttp、JDK HttpClient 等实现可以互换。
 *
 * <p>传输层负责：
 * <ul>
 *   <li>执行同步 HTTP 请求</li>
 *   <li>处理流式（SSE）响应</li>
 *   <li>管理连接生命周期</li>
 * </ul>
 *
 * <p>【学习提示】模型适配器负责业务协议，传输层负责 HTTP 与流式数据传递。
 * 替换客户端不应迫使智能体推理循环理解具体网络库。
 */
public interface HttpTransport {

    /**
     * 执行同步 HTTP 请求。
     *
     * @param request 要执行的 HTTP 请求
     * @return HTTP 响应
     * @throws HttpTransportException 请求失败时抛出
     */
    HttpResponse execute(HttpRequest request) throws HttpTransportException;

    /**
     * 执行流式 HTTP 请求（服务器发送事件，SSE）。
     *
     * <p>返回以 String 发出每条 SSE 数据行的 Flux。
     * 实现应负责解析 SSE 格式并提取每个事件的数据内容。
     *
     * @param request 要执行的 HTTP 请求
     * @return 以字符串形式发出 SSE 数据行的 Flux
     */
    Flux<String> stream(HttpRequest request);

    /**
     * 关闭传输并释放资源。
     *
     * <p>调用此方法后，不应继续使用该传输。
     */
    void close();
}
