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
package io.agentscope.core.studio;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.util.JsonUtils;
import io.socket.client.IO;
import io.socket.client.Socket;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * 与 AgentScope Studio 实时通信的 WebSocket 客户端。
 * <p>使用 Socket.IO 连接 Studio 的 /python 命名空间并接收用户输入，维持支持自动重连的持久连接。
 * <p>监听 Studio 的 "forwardUserInput" 事件；用户通过网页输入后，数据通过 Mono 交给等待中的智能体。
 */
public class StudioWebSocketClient {
    private static final Logger logger = LoggerFactory.getLogger(StudioWebSocketClient.class);

    private final StudioConfig config;
    private Socket socket;
    private final Map<String, Sinks.One<UserInputData>> pendingRequests = new ConcurrentHashMap<>();

    /**
     * 创建 Studio WebSocket 客户端。
     * @param config Studio 连接配置
     */
    public StudioWebSocketClient(StudioConfig config) {
        this.config = config;
    }

    /**
     * 包级构造函数，使用预配置 socket 进行测试。
     * @param config Studio 连接配置
     * @param socket 预配置 Socket.IO socket
     */
    StudioWebSocketClient(StudioConfig config, Socket socket) {
        this.config = config;
        this.socket = socket;
    }

    /**
     * 连接 Studio WebSocket 端点（/python 命名空间）。使用 run_id 认证建立 Socket.IO 连接，包含指数退避自动重连。
     * @return 建立连接时完成的 Mono
     */
    public Mono<Void> connect() {
        return Mono.<Void>create(
                        sink -> {
                            try {
                                // 配置 Socket.IO 选项
                                IO.Options options = new IO.Options();
                                options.forceNew = true;
                                options.reconnection = true;
                                options.reconnectionAttempts = config.getReconnectAttempts();
                                options.reconnectionDelay = config.getReconnectDelay().toMillis();
                                options.reconnectionDelayMax =
                                        config.getReconnectMaxDelay().toMillis();

                                // 设置认证凭据
                                Map<String, String> auth = new HashMap<>();
                                auth.put("run_id", config.getRunId());
                                options.auth = auth;

                                // 连接 /python 命名空间
                                String socketUrl = config.getStudioUrl() + "/python";
                                socket = IO.socket(socketUrl, options);

                                // 使用一次性监听器接收连接结果
                                socket.once(
                                        Socket.EVENT_CONNECT,
                                        args -> {
                                            logger.info(
                                                    "Connected to Studio WebSocket (/python"
                                                            + " namespace) with run_id: {}",
                                                    config.getRunId());
                                            sink.success();
                                        });

                                socket.once(
                                        Socket.EVENT_CONNECT_ERROR,
                                        args -> {
                                            logger.error(
                                                    "Failed to connect to Studio WebSocket: {}",
                                                    Arrays.toString(args));
                                            sink.error(
                                                    new RuntimeException(
                                                            "WebSocket connection failed"));
                                        });

                                // 注册持续事件处理器
                                setupEventHandlers();

                                // 连接
                                socket.connect();

                            } catch (URISyntaxException e) {
                                sink.error(
                                        new RuntimeException(
                                                "Failed to initialize Studio WebSocket", e));
                            }
                        })
                .timeout(Duration.ofSeconds(10));
    }

    /**
     * 设置 Socket.IO 事件处理器。
     */
    private void setupEventHandlers() {
        // 连接成功
        socket.on(
                Socket.EVENT_CONNECT,
                args -> logger.info("Socket.IO connected to /python namespace"));

        // 断开连接
        socket.on(
                Socket.EVENT_DISCONNECT,
                args -> logger.info("Socket.IO disconnected from /python namespace"));

        // 重新连接
        socket.on(
                "reconnect",
                args -> logger.info("Socket.IO reconnected (attempt: {})", Arrays.toString(args)));

        // 重连失败
        socket.on("reconnect_failed", args -> logger.error("Socket.IO reconnection failed"));

        // 连接错误
        socket.on(
                Socket.EVENT_CONNECT_ERROR,
                args -> logger.error("Socket.IO connection error: {}", Arrays.toString(args)));

        // 核心事件：forwardUserInput
        // 这是用户提交输入时 Studio 发出的主要事件
        socket.on(
                "forwardUserInput",
                args -> {
                    try {
                        handleUserInput(args);
                    } catch (Exception e) {
                        logger.error("Error handling forwardUserInput", e);
                    }
                });
    }

    /**
     * 处理通过 WebSocket 从 Studio 接收的用户输入。
     * <p>服务端发送 forwardUserInput 的三个参数：requestId、blocksInput 数组、structuredInput 对象。此方法解析数据并完成等待的请求。
     * @param args WebSocket 事件参数：[requestId, blocksInput, structuredInput]
     */
    void handleUserInput(Object... args) {
        // 参数：[requestId, blocksInput, structuredInput]
        String requestId = (String) args[0];
        JSONArray blocksInputJson = (JSONArray) args[1];
        Object structuredInputRaw = args[2];

        // 解析 blocksInput（ContentBlock 对象数组）
        List<ContentBlock> blocksInput = parseContentBlocks(blocksInputJson);

        // 解析 structuredInput
        Map<String, Object> structuredInput = null;
        if (structuredInputRaw instanceof JSONObject) {
            structuredInput = jsonObjectToMap((JSONObject) structuredInputRaw);
        }

        // 创建 UserInputData
        UserInputData inputData = new UserInputData(blocksInput, structuredInput);

        // 完成等待的 Mono
        Sinks.One<UserInputData> sink = pendingRequests.remove(requestId);
        if (sink != null) {
            sink.tryEmitValue(inputData);
            logger.debug("Received user input for request: {}", requestId);
        } else {
            logger.warn("No pending request found for: {}", requestId);
        }
    }

    /**
     * 从 JSON 解析 ContentBlock 数组。将 Studio 内容块 JSON 数组转换为强类型对象，各类型（文本、图像、工具结果等）自动反序列化为对应 Java 类；工具结果块特殊处理以统一输出格式。
     * @param jsonArray Studio 内容块 JSON 数组
     * @return ContentBlock 对象列表
     */
    @SuppressWarnings("unchecked")
    List<ContentBlock> parseContentBlocks(JSONArray jsonArray) {
        List<ContentBlock> blocks = new ArrayList<>();

        for (int i = 0; i < jsonArray.length(); i++) {
            try {
                JSONObject blockJson = jsonArray.getJSONObject(i);

                // 预处理 ToolResultBlock，兼容 TypeScript 的字符串输出
                if ("tool_result".equals(blockJson.optString("type"))) {
                    blockJson = preprocessToolResultBlock(blockJson);
                }

                // 将 JSONObject 转为 Map
                Map<String, Object> blockMap = jsonObjectToMap(blockJson);

                // 使用 Jackson 反序列化为 ContentBlock
                // Jackson 根据 @JsonSubTypes 注解
                // 自动确定正确的 ContentBlock 子类型
                ContentBlock block =
                        JsonUtils.getJsonCodec().convertValue(blockMap, ContentBlock.class);
                blocks.add(block);

            } catch (Exception e) {
                logger.error("Failed to parse ContentBlock at index {}: {}", i, e.getMessage(), e);
            }
        }

        return blocks;
    }

    /**
     * 规范化 ToolResultBlock 输出格式。确保 output 总是 ContentBlock 对象列表；简单字符串包装成列表中的 TextBlock。
     * @param json 原始 ToolResultBlock JSON
     * @return output 为 ContentBlock 数组的规范化 JSON
     */
    private JSONObject preprocessToolResultBlock(JSONObject json) {
        try {
            Object output = json.opt("output");

            // output 为字符串时转换为 ContentBlock 数组
            if (output instanceof String) {
                JSONArray outputArray = new JSONArray();
                JSONObject textBlock = new JSONObject();
                textBlock.put("type", "text");
                textBlock.put("text", output);
                outputArray.put(textBlock);

                // 创建带规范化输出的新 JSONObject
                // 通过 JSONObject.toString 再解析创建副本
                JSONObject normalized = new JSONObject(json.toString());
                normalized.put("output", outputArray);
                return normalized;
            }

            return json;
        } catch (Exception e) {
            logger.error("Failed to preprocess ToolResultBlock: {}", e.getMessage(), e);
            return json;
        }
    }

    /**
     * 使用 Jackson 将 JSONObject 转为 Map。利用 ObjectMapper 转换 org.json.JSONObject，避免手工递归及类型转换。
     * @param jsonObject 待转换 JSON 对象
     * @return Map 表示
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> jsonObjectToMap(JSONObject jsonObject) {
        try {
            // 先将 JSONObject 转为 JSON 字符串，再通过 Jackson 解析
            String jsonString = jsonObject.toString();
            return JsonUtils.getJsonCodec().fromJson(jsonString, Map.class);
        } catch (Exception e) {
            logger.error("Failed to convert JSONObject to Map", e);
            return new HashMap<>();
        }
    }

    /**
     * 等待匹配请求 ID 的用户输入。发送 requestUserInput HTTP 请求后调用；对应 forwardUserInput 事件通过 WebSocket 到达时，返回的 Mono 发出数据。
     * @param requestId 等待的请求 ID，来自 StudioClient.requestUserInput
     * @return 输入可用时发出用户输入数据的 Mono
     */
    public Mono<UserInputData> waitForInput(String requestId) {
        Sinks.One<UserInputData> sink = Sinks.one();
        // 【学习提示】requestId 将 HTTP 请求与 WebSocket 响应关联；当前实现仅在响应或错误时移除等待项，取消订阅并不等于已执行清理。
        pendingRequests.put(requestId, sink);

        return sink.asMono()
                .timeout(Duration.ofMinutes(30))
                .doOnError(
                        e -> {
                            pendingRequests.remove(requestId);
                            logger.warn("Timeout or error waiting for user input: {}", requestId);
                        });
    }

    /**
     * 关闭 WebSocket 连接并释放资源。
     */
    public void close() {
        if (socket != null && socket.connected()) {
            socket.disconnect();
            socket.close();
        }
    }

    /**
     * 检查 WebSocket 当前是否连接。
     * @return 已连接为 true，否则为 false
     */
    public boolean isConnected() {
        return socket != null && socket.connected();
    }

    /**
     * 表示 Studio 用户输入的数据结构。包含内容块（文本、图像等）以及可选的结构化数据；结构化数据的校验由输入表单／服务契约负责，此类型本身仅保存数据。
     */
    public static class UserInputData {
        private final List<ContentBlock> blocksInput;
        private final Map<String, Object> structuredInput;

        /**
         * 创建用户输入数据。
         * @param blocksInput 用户提供的内容块列表
         * @param structuredInput 可选结构化数据，如表单字段
         */
        public UserInputData(List<ContentBlock> blocksInput, Map<String, Object> structuredInput) {
            this.blocksInput = blocksInput;
            this.structuredInput = structuredInput;
        }

        /**
         * 获取用户提供的内容块。
         * @return 内容块列表
         */
        public List<ContentBlock> getBlocksInput() {
            return blocksInput;
        }

        /**
         * 获取结构化输入数据。
         * @return 结构化数据映射，未提供时为 null
         */
        public Map<String, Object> getStructuredInput() {
            return structuredInput;
        }
    }
}
