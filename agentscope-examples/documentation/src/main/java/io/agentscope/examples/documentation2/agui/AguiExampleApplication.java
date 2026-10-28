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
package io.agentscope.examples.documentation2.agui;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * AG-UI 示例应用，演示通过 Spring WebFlux 以 AG-UI 协议公开 AgentScope 智能体。
 * <p>使用：设置 DASHSCOPE_API_KEY 环境变量，运行应用，在浏览器打开 http://localhost:8080；或使用：
 * <p>curl -X POST http://localhost:8080/agui/run -H "Content-Type: application/json" -d '{"threadId":"test","runId":"1","messages":[{"id":"m1","role":"user","content":"Hello!"}]}'
 */
@SpringBootApplication
public class AguiExampleApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(AguiExampleApplication.class);
        app.setDefaultProperties(
                java.util.Map.of(
                        "spring.config.location", "classpath:/agui/application.yml",
                        "logging.config", "classpath:agui/logback.xml"));
        app.run(args);
        printStartupInfo();
    }

    private static void printStartupInfo() {
        System.out.println("\n=== AG-UI Example Application Started ===");
        System.out.println("Open: http://localhost:8080");
        System.out.println("API:  POST http://localhost:8080/agui/run");
        System.out.println("\nExample curl command:");
        System.out.println("  curl -N -X POST http://localhost:8080/agui/run \\");
        System.out.println("    -H \"Content-Type: application/json\" \\");
        System.out.println(
                "    -d"
                    + " '{\"threadId\":\"test\",\"runId\":\"1\",\"messages\":[{\"id\":\"m1\",\"role\":\"user\",\"content\":\"Hello!\"}]}'");
        System.out.println("\nPress Ctrl+C to stop.");
    }
}
