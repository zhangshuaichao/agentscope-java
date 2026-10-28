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
package io.agentscope.dataagent.web.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/** React SPA 后备路由的 WebFlux 配置。<p>不以 {@code /api} 开头且没有文件扩展名的请求（SPA 深链接）被转发到 {@code /static/index.html}，由 React 路由器在客户端处理导航。<p>带扩展名的静态资产（JS、CSS、图片）由 Spring 默认静态资源处理器直接从 {@code classpath:/static/} 提供。 */
@Configuration
public class WebConfig {

    @Bean
    public RouterFunction<ServerResponse> spaFallback() {
        ClassPathResource indexHtml = new ClassPathResource("/static/index.html");
        return RouterFunctions.route()
                .GET(
                        request -> {
                            String path = request.path();
                            return !path.startsWith("/api")
                                    && !path.contains(".")
                                    && !path.startsWith("/actuator");
                        },
                        request ->
                                ServerResponse.ok()
                                        .contentType(MediaType.TEXT_HTML)
                                        .bodyValue(indexHtml))
                .build();
    }
}
