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
package io.agentscope.examples.documentation2.tool;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.DefaultToolResultConverter;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonSchemaUtils;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import io.agentscope.harness.agent.middleware.AgentTraceMiddleware;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ToolCallingWithConverterExample：自定义 {@link DefaultToolResultConverter}。
 * <p>展示敏感字段脱敏转换器（密码、API 密钥、信用卡）及模式增强转换器（将返回类型 JSON Schema 附加到结果）。迁移移除 {@code .memory(new InMemoryMemory())}，2.0 不再需要。
 */
public class ToolCallingWithConverterExample {

    /**
     * 运行转换器示例。
     * @param args 命令行参数，此处忽略
     * @throws Exception I/O 错误时
     */
    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Tool Calling Custom ToolResultConverter Example");
        System.out.println("=".repeat(60));
        System.out.println(
                "Demonstrates custom ToolResultConverters:\n"
                    + "  - get_user_info: Masks sensitive fields (password, apiKey, creditCard)\n"
                    + "  - list_orders:   Appends the JSON Schema of the return type");
        System.out.println("=".repeat(60) + "\n");

        String apiKey = System.getenv("DASHSCOPE_API_KEY");

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new SimpleTools());

        ReActAgent agent =
                ReActAgent.builder()
                        .name("ToolAgent")
                        .sysPrompt(
                                "You are a helpful assistant with access to tools. "
                                        + "Use tools when needed to answer questions accurately.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-max")
                                        .stream(true)
                                        .enableThinking(false)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .toolkit(toolkit)
                        .middleware(new AgentTraceMiddleware())
                        .build();

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("Chat started. Type 'exit' to quit.\n");

        while (true) {
            System.out.print("You: ");
            String input = reader.readLine();
            if (input == null || input.trim().equalsIgnoreCase("exit")) {
                System.out.println("\nGoodbye!");
                break;
            }
            if (input.isBlank()) {
                continue;
            }
            Msg userMsg = new UserMessage(input.trim());
            System.out.print("\nAgent: ");
            agent.streamEvents(userMsg)
                    .doOnNext(
                            event -> {
                                if (event instanceof TextBlockDeltaEvent e) {
                                    System.out.print(e.getDelta());
                                }
                            })
                    .blockLast();
            System.out.println("\n");
        }
    }

    /**
     * 本示例使用的简单工具。
     */
    public static class SimpleTools {

        /**
         * 返回指定用户信息，由转换器掩码敏感字段。
         * @param userId 用户 ID
         * @return 用户信息
         */
        @Tool(
                name = "get_user_info",
                description = "Retrieve user information by user ID",
                converter = SensitiveDataMaskingConverter.class)
        public UserInfo getUserInfo(
                @ToolParam(name = "userId", description = "User ID") String userId) {
            return new UserInfo(
                    userId,
                    "John Doe",
                    "john@example.com",
                    "MySecretPassword123",
                    "sk-1234567890abcdef",
                    "4567-1234-8888-6666");
        }

        /**
         * 返回用户订单列表，转换后附加 JSON Schema。
         * @param userId 用户 ID
         * @return 订单列表
         */
        @Tool(
                name = "list_orders",
                description = "Retrieve a list of orders by user ID",
                converter = SchemaEnhancementConverter.class)
        public List<Order> listOrders(
                @ToolParam(name = "userId", description = "User ID") String userId) {
            return List.of(
                    new Order(
                            "ORD001",
                            userId,
                            "Luxurious Laptop",
                            3,
                            5999.99,
                            "Hangzhou City, Zhejiang Province",
                            1,
                            "Handle with care, waterproof and shockproof packaging required",
                            "2025-01-15 10:30:00"),
                    new Order(
                            "ORD002",
                            userId,
                            "Splendid Monitor",
                            3,
                            4999.99,
                            "Hangzhou City, Zhejiang Province",
                            1,
                            "Handle with care, waterproof and shockproof packaging required",
                            "2025-01-15 10:30:00"));
        }
    }

    // ==================== 自定义转换器 ====================

    /**
     * 掩码工具结果中的 password、apiKey、creditCard 等敏感字段。
     */
    public static class SensitiveDataMaskingConverter extends DefaultToolResultConverter {

        private static final Set<String> SENSITIVE_FIELDS =
                new HashSet<>(
                        Arrays.asList(
                                "password",
                                "apikey",
                                "api_key",
                                "token",
                                "secret",
                                "creditcard",
                                "credit_card",
                                "ssn"));

        private static final Pattern CREDIT_CARD_PATTERN =
                Pattern.compile("\\d{4}[-\\s]?\\d{4}[-\\s]?\\d{4}[-\\s]?\\d{4}");

        @Override
        protected ToolResultBlock serialize(Object result, Type returnType) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                mapper.registerModule(new JavaTimeModule());
                JsonNode node = mapper.valueToTree(result);
                JsonNode masked = maskSensitiveData(node);
                String json = JsonUtils.getJsonCodec().toJson(masked);
                Map<String, Object> schema = JsonSchemaUtils.generateSchemaFromType(returnType);
                String schemaJson = JsonUtils.getJsonCodec().toJson(schema);
                return ToolResultBlock.of(
                        List.of(
                                TextBlock.builder()
                                        .text("Sensitive data has been masked\n\n" + json)
                                        .build(),
                                TextBlock.builder()
                                        .text("\nResult JSON Schema:\n" + schemaJson)
                                        .build()));
            } catch (Exception e) {
                return super.serialize(result, returnType);
            }
        }

        private JsonNode maskSensitiveData(JsonNode node) {
            if (node.isObject()) {
                ObjectNode result = ((ObjectNode) node).deepCopy();
                Iterator<Map.Entry<String, JsonNode>> fields = result.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    String fieldName = entry.getKey().toLowerCase();
                    if (isSensitiveField(fieldName)) {
                        result.put(entry.getKey(), "***MASKED***");
                    } else if (entry.getValue().isTextual()) {
                        String value = entry.getValue().asText();
                        if (CREDIT_CARD_PATTERN.matcher(value).matches()) {
                            result.put(entry.getKey(), maskCreditCard(value));
                        }
                    }
                }
                return result;
            }
            return node;
        }

        private boolean isSensitiveField(String fieldName) {
            for (String sensitive : SENSITIVE_FIELDS) {
                if (fieldName.contains(sensitive)) {
                    return true;
                }
            }
            return false;
        }

        private String maskCreditCard(String card) {
            String digits = card.replaceAll("[^0-9]", "");
            if (digits.length() >= 4) {
                return "****-****-****-" + digits.substring(digits.length() - 4);
            }
            return "***MASKED***";
        }
    }

    /**
     * 将返回类型的 JSON Schema 附加到工具结果。
     */
    public static class SchemaEnhancementConverter extends DefaultToolResultConverter {

        @Override
        protected ToolResultBlock serialize(Object result, Type returnType) {
            try {
                String json = JsonUtils.getJsonCodec().toJson(result);
                Map<String, Object> schema = JsonSchemaUtils.generateSchemaFromType(returnType);
                String schemaJson = JsonUtils.getJsonCodec().toJson(schema);
                return ToolResultBlock.of(
                        List.of(
                                TextBlock.builder().text("Result Data:\n" + json).build(),
                                TextBlock.builder()
                                        .text("\nResult JSON Schema:\n" + schemaJson)
                                        .build()));
            } catch (Exception e) {
                return super.serialize(result, returnType);
            }
        }
    }

    // ==================== 数据类 ====================

    /**
     * {@code get_user_info} 返回的用户信息。
     */
    public static class UserInfo {

        @JsonPropertyDescription("User ID")
        private String userId;

        @JsonPropertyDescription("Username")
        private String username;

        @JsonPropertyDescription("Email address")
        private String email;

        @JsonPropertyDescription("Password (sensitive)")
        private String password;

        @JsonPropertyDescription("API key (sensitive)")
        private String apiKey;

        @JsonPropertyDescription("Credit card number (sensitive)")
        private String creditCard;

        @JsonPropertyDescription("Registration time, format: yyyy-MM-dd HH:mm:ss")
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime createTime = LocalDateTime.now();

        /**
         * Jackson 所需默认构造函数。
         */
        public UserInfo() {}

        /**
         * 创建 UserInfo 实例。
         * @param userId 用户 ID
         * @param username 用户名
         * @param email 邮箱地址
         * @param password 密码，将由转换器掩码
         * @param apiKey API 密钥，将被掩码
         * @param creditCard 信用卡号，将被掩码
         */
        public UserInfo(
                String userId,
                String username,
                String email,
                String password,
                String apiKey,
                String creditCard) {
            this.userId = userId;
            this.username = username;
            this.email = email;
            this.password = password;
            this.apiKey = apiKey;
            this.creditCard = creditCard;
        }

        /**
         * 返回用户 ID。
         */
        public String getUserId() {
            return userId;
        }

        /**
         * 设置用户 ID。
         */
        public void setUserId(String userId) {
            this.userId = userId;
        }

        /**
         * 返回用户名。
         */
        public String getUsername() {
            return username;
        }

        /**
         * 设置用户名。
         */
        public void setUsername(String username) {
            this.username = username;
        }

        /**
         * 返回邮箱地址。
         */
        public String getEmail() {
            return email;
        }

        /**
         * 设置邮箱地址。
         */
        public void setEmail(String email) {
            this.email = email;
        }

        /**
         * 返回密码。
         */
        public String getPassword() {
            return password;
        }

        /**
         * 设置密码。
         */
        public void setPassword(String password) {
            this.password = password;
        }

        /**
         * 返回API 密钥。
         */
        public String getApiKey() {
            return apiKey;
        }

        /**
         * 设置API 密钥。
         */
        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        /**
         * 返回信用卡号。
         */
        public String getCreditCard() {
            return creditCard;
        }

        /**
         * 设置信用卡号。
         */
        public void setCreditCard(String creditCard) {
            this.creditCard = creditCard;
        }

        /**
         * 返回注册时间。
         */
        public LocalDateTime getCreateTime() {
            return createTime;
        }

        /**
         * 设置注册时间。
         */
        public void setCreateTime(LocalDateTime createTime) {
            this.createTime = createTime;
        }
    }

    /**
     * {@code list_orders} 返回的订单数据。
     */
    public static class Order {

        @JsonPropertyDescription("Order ID, format: ORD + 3 digits")
        private String id;

        @JsonPropertyDescription("User ID")
        private String userId;

        @JsonPropertyDescription("Product name")
        private String product;

        @JsonPropertyDescription(
                "Status: 0=Pending, 1=Paid, 2=Processing, 3=Shipped, 4=In Transit,"
                        + " 5=Delivered, 6=Completed, 7=Cancelled")
        private Integer status;

        @JsonPropertyDescription("Total price in CNY")
        private Double price;

        @JsonPropertyDescription("Product origin")
        private String address;

        @JsonPropertyDescription("Quantity")
        private Integer quantity;

        @JsonPropertyDescription("Order remarks for special delivery requirements")
        private String description;

        @JsonPropertyDescription("Order creation time, format: yyyy-MM-dd HH:mm:ss")
        private String createTime;

        /**
         * Jackson 所需默认构造函数。
         */
        public Order() {}

        /**
         * 创建 Order 实例。
         * @param id 订单 ID
         * @param userId 用户 ID
         * @param product 商品名称
         * @param status 订单状态码
         * @param price 总价
         * @param address 商品产地
         * @param quantity 数量
         * @param description 备注
         * @param createTime 创建时间字符串
         */
        public Order(
                String id,
                String userId,
                String product,
                Integer status,
                Double price,
                String address,
                Integer quantity,
                String description,
                String createTime) {
            this.id = id;
            this.userId = userId;
            this.product = product;
            this.status = status;
            this.price = price;
            this.address = address;
            this.quantity = quantity;
            this.description = description;
            this.createTime = createTime;
        }

        /**
         * 返回订单 ID。
         */
        public String getId() {
            return id;
        }

        /**
         * 设置订单 ID。
         */
        public void setId(String id) {
            this.id = id;
        }

        /**
         * 返回用户 ID。
         */
        public String getUserId() {
            return userId;
        }

        /**
         * 设置用户 ID。
         */
        public void setUserId(String userId) {
            this.userId = userId;
        }

        /**
         * 返回商品名称。
         */
        public String getProduct() {
            return product;
        }

        /**
         * 设置商品名称。
         */
        public void setProduct(String product) {
            this.product = product;
        }

        /**
         * 返回状态码。
         */
        public Integer getStatus() {
            return status;
        }

        /**
         * 设置状态码。
         */
        public void setStatus(Integer status) {
            this.status = status;
        }

        /**
         * 返回总价。
         */
        public Double getPrice() {
            return price;
        }

        /**
         * 设置总价。
         */
        public void setPrice(Double price) {
            this.price = price;
        }

        /**
         * 返回商品产地。
         */
        public String getAddress() {
            return address;
        }

        /**
         * 设置商品产地。
         */
        public void setAddress(String address) {
            this.address = address;
        }

        /**
         * 返回数量。
         */
        public Integer getQuantity() {
            return quantity;
        }

        /**
         * 设置数量。
         */
        public void setQuantity(Integer quantity) {
            this.quantity = quantity;
        }

        /**
         * 返回订单备注。
         */
        public String getDescription() {
            return description;
        }

        /**
         * 设置订单备注。
         */
        public void setDescription(String description) {
            this.description = description;
        }

        /**
         * 返回创建时间字符串。
         */
        public String getCreateTime() {
            return createTime;
        }

        /**
         * 设置创建时间字符串。
         */
        public void setCreateTime(String createTime) {
            this.createTime = createTime;
        }
    }
}
