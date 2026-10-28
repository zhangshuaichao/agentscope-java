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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("StudioManager Tests")
class StudioManagerTest {

    @AfterEach
    void tearDown() {
        // 每个测试后始终清理
        StudioManager.shutdown();
    }

    @Test
    @DisplayName("init() should return a new builder")
    void testInit() {
        StudioManager.Builder builder = StudioManager.init();
        assertNotNull(builder);
    }

    @Test
    @DisplayName("getClient() should return null before initialization")
    void testGetClientBeforeInit() {
        assertNull(StudioManager.getClient());
    }

    @Test
    @DisplayName("getWebSocketClient() should return null before initialization")
    void testGetWebSocketClientBeforeInit() {
        assertNull(StudioManager.getWebSocketClient());
    }

    @Test
    @DisplayName("getConfig() should return null before initialization")
    void testGetConfigBeforeInit() {
        assertNull(StudioManager.getConfig());
    }

    @Test
    @DisplayName("isInitialized() should return false before initialization")
    void testIsInitializedBeforeInit() {
        assertFalse(StudioManager.isInitialized());
    }

    @Test
    @DisplayName("shutdown() should be safe to call before initialization")
    void testShutdownBeforeInit() {
        // 不应抛异常
        StudioManager.shutdown();

        assertNull(StudioManager.getClient());
        assertNull(StudioManager.getWebSocketClient());
        assertNull(StudioManager.getConfig());
        assertFalse(StudioManager.isInitialized());
    }

    @Test
    @DisplayName("shutdown() should clear all state")
    void testShutdownClearsState() {
        // 为避免网络调用，此测试不实际初始化，
        // 仅检查 shutdown 重置状态
        StudioManager.shutdown();

        assertNull(StudioManager.getClient());
        assertNull(StudioManager.getWebSocketClient());
        assertNull(StudioManager.getConfig());
        assertFalse(StudioManager.isInitialized());
    }

    @Test
    @DisplayName("Builder should support method chaining")
    void testBuilderChaining() {
        StudioManager.Builder builder =
                StudioManager.init()
                        .studioUrl("http://localhost:3000")
                        .project("TestProject")
                        .runName("test_run")
                        .maxRetries(5)
                        .reconnectAttempts(3);

        assertNotNull(builder);
    }

    @Test
    @DisplayName("Multiple init() calls should return independent builders")
    void testMultipleInitCalls() {
        StudioManager.Builder builder1 = StudioManager.init();
        StudioManager.Builder builder2 = StudioManager.init();

        assertNotNull(builder1);
        assertNotNull(builder2);
        // 应为不同实例
        assertFalse(builder1 == builder2);
    }

    @Test
    @DisplayName("shutdown() should be idempotent")
    void testShutdownIdempotent() {
        // 多次 shutdown 应安全
        StudioManager.shutdown();
        StudioManager.shutdown();
        StudioManager.shutdown();

        assertNull(StudioManager.getClient());
        assertFalse(StudioManager.isInitialized());
    }
}
