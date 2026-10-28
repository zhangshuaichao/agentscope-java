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
package io.agentscope.builder.web.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 通过 {@code
 * X-Builder-Internal-Token} 携带的共享静态令牌验证跨平面内部调用。用于控制平面的
 * {@code /api/internal/**} 路由，以及由控制平面或调度器（而非浏览器）调用的数据平面端点。
 * 代理操作的所有者通过 {@code X-Builder-Internal-User} 传递，并作为认证主体暴露，
 * 使接收平面无需用户 JWT 即可确定操作归属。
 *
 * <p>配置令牌为空白或请求令牌不匹配时，过滤器不执行认证，请求继续以未认证状态流转。
 * 内部端点仍保持关闭：安全链会以 401 拒绝未认证请求。
 * <p>【学习提示】此处的内部用户头只有在共享令牌验证成功后才可信；用户身份归属不等同于资源级授权。
 */
public class InternalTokenAuthFilter implements WebFilter {

    public static final String INTERNAL_TOKEN_HEADER = "X-Builder-Internal-Token";
    public static final String INTERNAL_USER_HEADER = "X-Builder-Internal-User";
    public static final String ROLE_INTERNAL = "ROLE_INTERNAL";

    private final String internalToken;

    public InternalTokenAuthFilter(String internalToken) {
        this.internalToken = internalToken;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String presented = exchange.getRequest().getHeaders().getFirst(INTERNAL_TOKEN_HEADER);
        if (internalToken == null
                || internalToken.isBlank()
                || presented == null
                || !constantTimeEquals(internalToken, presented)) {
            return chain.filter(exchange);
        }
        String user = exchange.getRequest().getHeaders().getFirst(INTERNAL_USER_HEADER);
        String principal = user != null && !user.isBlank() ? user : "internal";
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(
                        principal, null, List.of(new SimpleGrantedAuthority(ROLE_INTERNAL)));
        return chain.filter(exchange)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth));
    }

    private static boolean constantTimeEquals(String expected, String presented) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}
