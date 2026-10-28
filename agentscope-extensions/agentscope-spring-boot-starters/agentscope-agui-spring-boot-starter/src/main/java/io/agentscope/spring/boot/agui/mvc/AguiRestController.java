/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.spring.boot.agui.mvc;

import io.agentscope.core.agui.encoder.AguiEventEncoder;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.runtime.AguiRequestBodyParser;
import io.agentscope.core.util.JsonException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AG-UI 协议端点的 REST 控制器。
 *
 * <p>向 Spring MVC 应用暴露 AG-UI 运行端点，并将实际处理委托给 {@link AguiMvcController}。
 */
@RestController
public class AguiRestController {

    private final AguiMvcController aguiMvcController;
    private final AguiEventEncoder encoder = new AguiEventEncoder();
    private final AguiRequestBodyParser requestBodyParser;
    private final String pathPrefix;
    private final boolean enablePathRouting;

    /**
     * 创建 AguiRestController。
     *
     * @param aguiMvcController AG-UI MVC 控制器
     * @param pathPrefix 端点路径前缀
     * @param enablePathRouting 是否启用路径变量路由
     * @param requestBodyParser 请求体解析器
     */
    public AguiRestController(
            AguiMvcController aguiMvcController,
            String pathPrefix,
            boolean enablePathRouting,
            AguiRequestBodyParser requestBodyParser) {
        this.aguiMvcController = aguiMvcController;
        this.pathPrefix = pathPrefix;
        this.enablePathRouting = enablePathRouting;
        this.requestBodyParser = requestBodyParser;
    }

    /**
     * 处理 AG-UI 运行请求。
     *
     * <p>按以下优先级解析智能体 ID：
     * <ol>
     *   <li>HTTP 请求头（可配置，默认 X-Agent-Id）</li>
     *   <li>请求体中的 forwardedProps.agentId</li>
     *   <li>config.defaultAgentId</li>
     *   <li>"default"</li>
     * </ol>
     *
     * @param body 原始智能体运行输入 JSON
     * @param agentIdHeader HTTP 请求头中的智能体 ID（可选）
     * @return 用于流式输出 AG-UI 事件的 SseEmitter
     */
    @PostMapping(
            value = "${agentscope.agui.path-prefix:/agui}/run",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter run(
            @RequestBody String body,
            @RequestHeader(
                            value = "${agentscope.agui.agent-id-header:X-Agent-Id}",
                            required = false)
                    String agentIdHeader,
            HttpServletRequest request) {
        RunAgentInput input = requestBodyParser.parse(body);
        return aguiMvcController.handle(input, agentIdHeader, request);
    }

    /**
     * 处理 URL 路径中包含智能体 ID 的 AG-UI 运行请求。
     *
     * <p>解析智能体时，路径变量具有最高优先级。
     *
     * @param agentId 路径变量中的智能体 ID
     * @param body 原始智能体运行输入 JSON
     * @param agentIdHeader HTTP 请求头中的智能体 ID（可选）
     * @return 用于流式输出 AG-UI 事件的 SseEmitter
     */
    @PostMapping(
            value = "${agentscope.agui.path-prefix:/agui}/run/{agentId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter runWithAgentId(
            @PathVariable String agentId,
            @RequestBody String body,
            @RequestHeader(
                            value = "${agentscope.agui.agent-id-header:X-Agent-Id}",
                            required = false)
                    String agentIdHeader,
            HttpServletRequest request) {
        RunAgentInput input = requestBodyParser.parse(body);
        return aguiMvcController.handleWithAgentId(input, agentIdHeader, agentId, request);
    }

    /**
     * AG-UI 请求体解析失败时返回 HTTP 400。
     *
     * @param error JSON 解析错误
     * @return 兼容 SSE 的错误请求响应
     */
    @ExceptionHandler(JsonException.class)
    public ResponseEntity<String> handleParseError(JsonException error) {
        String errorEvent =
                encoder.encodeToJson(
                                new AguiEvent.Raw(
                                        "unknown",
                                        "unknown",
                                        Map.of(
                                                "error",
                                                "Failed to parse request: " + error.getMessage())))
                        .trim();
        String finishEvent =
                encoder.encodeToJson(new AguiEvent.RunFinished("unknown", "unknown")).trim();
        return ResponseEntity.badRequest()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body("data: " + errorEvent + "\n\n" + "data: " + finishEvent + "\n\n");
    }
}
