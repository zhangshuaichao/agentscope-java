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
package io.agentscope.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 基于 List<ContextStore> 架构的 ToolExecutionContext 测试。
 */
class ToolExecutionContextTest {

    /**
     * 用于测试按类型存储的 POJO。
     */
    static class UserContext {
        private String userId;
        private String sessionId;
        private Map<String, String> permissions;

        public UserContext() {}

        public UserContext(String userId, String sessionId) {
            this.userId = userId;
            this.sessionId = sessionId;
        }

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public String getSessionId() {
            return sessionId;
        }

        public void setSessionId(String sessionId) {
            this.sessionId = sessionId;
        }

        public Map<String, String> getPermissions() {
            return permissions;
        }

        public void setPermissions(Map<String, String> permissions) {
            this.permissions = permissions;
        }
    }

    static class DatabaseConfig {
        private String url;
        private int maxConnections;

        public DatabaseConfig(String url, int maxConnections) {
            this.url = url;
            this.maxConnections = maxConnections;
        }

        public String getUrl() {
            return url;
        }

        public int getMaxConnections() {
            return maxConnections;
        }
    }

    @Test
    void testSingletonPattern() {
        // 单例：每种类型一个实例
        UserContext userCtx = new UserContext("user123", "session456");
        DatabaseConfig dbConfig = new DatabaseConfig("jdbc:mysql://localhost", 10);

        ToolExecutionContext context =
                ToolExecutionContext.builder()
                        .register(userCtx) // 按实际类型注册
                        .register(dbConfig)
                        .build();

        // 按类型检索，取得同一实例
        UserContext retrieved = context.get(UserContext.class);
        DatabaseConfig retrievedDb = context.get(DatabaseConfig.class);

        assertSame(userCtx, retrieved);
        assertSame(dbConfig, retrievedDb);
        assertEquals("user123", retrieved.getUserId());
        assertEquals(10, retrievedDb.getMaxConnections());
    }

    @Test
    void testMultiInstancePattern() {
        // 同类型多个实例按键区分
        UserContext admin = new UserContext("admin", "session1");
        UserContext guest = new UserContext("guest", "session2");

        ToolExecutionContext context =
                ToolExecutionContext.builder()
                        .register("admin", admin)
                        .register("guest", guest)
                        .build();

        // 按键和类型检索
        UserContext adminCtx = context.get("admin", UserContext.class);
        UserContext guestCtx = context.get("guest", UserContext.class);

        assertSame(admin, adminCtx);
        assertSame(guest, guestCtx);
        assertEquals("admin", adminCtx.getUserId());
        assertEquals("guest", guestCtx.getUserId());
    }

    @Test
    void testExplicitTypeRegistration() {
        // 按接口/父类类型注册
        UserContext impl = new UserContext("user123", "session456");

        ToolExecutionContext context =
                ToolExecutionContext.builder()
                        .register(UserContext.class, impl) // 显式类型
                        .build();

        UserContext retrieved = context.get(UserContext.class);
        assertSame(impl, retrieved);
    }

    @Test
    void testKeyedExplicitTypeRegistration() {
        // 同时指定键和显式类型注册
        UserContext admin = new UserContext("admin", "session1");
        UserContext guest = new UserContext("guest", "session2");

        ToolExecutionContext context =
                ToolExecutionContext.builder()
                        .register("admin", UserContext.class, admin)
                        .register("guest", UserContext.class, guest)
                        .build();

        assertEquals("admin", context.get("admin", UserContext.class).getUserId());
        assertEquals("guest", context.get("guest", UserContext.class).getUserId());
    }

    @Test
    void testMixedSingletonAndMultiInstance() {
        // 同类型混合单例与多实例
        DatabaseConfig defaultDb = new DatabaseConfig("jdbc:mysql://default", 10);
        DatabaseConfig replicaDb = new DatabaseConfig("jdbc:mysql://replica", 5);

        ToolExecutionContext context =
                ToolExecutionContext.builder()
                        .register(defaultDb) // 单例，默认键
                        .register("replica", replicaDb) // 带键实例
                        .build();

        // 单例检索
        DatabaseConfig def = context.get(DatabaseConfig.class);
        assertSame(defaultDb, def);

        // 按键检索
        DatabaseConfig replica = context.get("replica", DatabaseConfig.class);
        assertSame(replicaDb, replica);
    }

    @Test
    void testContainsMethods() {
        UserContext userCtx = new UserContext("user123", "session456");

        ToolExecutionContext context =
                ToolExecutionContext.builder()
                        .register(userCtx)
                        .register("admin", new UserContext("admin", "session1"))
                        .build();

        // 按类型检查包含
        assertTrue(context.contains(UserContext.class));
        assertFalse(context.contains(DatabaseConfig.class));

        // 按键检查包含
        assertTrue(context.contains("admin", UserContext.class));
        assertFalse(context.contains("guest", UserContext.class));
    }

    @Test
    void testPriorityChainMerge() {
        // 工具集级：默认上下文
        DatabaseConfig toolkitDb = new DatabaseConfig("jdbc:mysql://toolkit", 10);
        ToolExecutionContext toolkitCtx =
                ToolExecutionContext.builder().register(toolkitDb).build();

        // 智能体级：用用户上下文覆盖
        UserContext agentUser = new UserContext("user123", "session456");
        ToolExecutionContext agentCtx = ToolExecutionContext.builder().register(agentUser).build();

        // 调用级：最具体
        UserContext callUser = new UserContext("user999", "session999");
        ToolExecutionContext callCtx = ToolExecutionContext.builder().register(callUser).build();

        // 合并优先级：callCtx > agentCtx > toolkitCtx
        ToolExecutionContext merged = ToolExecutionContext.merge(callCtx, agentCtx, toolkitCtx);

        // UserContext 优先采用调用上下文
        UserContext mergedUser = merged.get(UserContext.class);
        assertSame(callUser, mergedUser);
        assertEquals("user999", mergedUser.getUserId());

        // 数据库配置来自工具集
        DatabaseConfig mergedDb = merged.get(DatabaseConfig.class);
        assertSame(toolkitDb, mergedDb);
    }

    @Test
    void testEmptyContext() {
        ToolExecutionContext empty = ToolExecutionContext.empty();

        assertNull(empty.get(UserContext.class));
        assertNull(empty.get("admin", UserContext.class));
        assertFalse(empty.contains(UserContext.class));
        assertFalse(empty.contains("admin", UserContext.class));
    }

    @Test
    void testNullRetrievals() {
        UserContext userCtx = new UserContext("user123", "session456");

        ToolExecutionContext context = ToolExecutionContext.builder().register(userCtx).build();

        // 类型不存在
        assertNull(context.get(DatabaseConfig.class));

        // 键不存在
        assertNull(context.get("nonexistent", UserContext.class));
    }

    @Test
    void testStoreList() {
        DatabaseConfig dbConfig = new DatabaseConfig("jdbc:mysql://localhost", 10);

        ToolExecutionContext context = ToolExecutionContext.builder().register(dbConfig).build();

        // 应有一个存储，即 SimpleContextStore
        assertEquals(1, context.getStores().size());
        assertTrue(context.getStores().get(0) instanceof DefaultContextStore);
    }

    @Test
    void testMultipleStores() {
        // 创建含 UserContext 的第一个存储
        DefaultContextStore store1 =
                DefaultContextStore.builder()
                        .register(new UserContext("user1", "session1"))
                        .build();

        // 创建含 DatabaseConfig 的第二个存储
        DefaultContextStore store2 =
                DefaultContextStore.builder()
                        .register(new DatabaseConfig("jdbc:mysql://localhost", 10))
                        .build();

        // 将两者加入上下文
        ToolExecutionContext context =
                ToolExecutionContext.builder().addStore(store1).addStore(store2).build();

        // 可从两个存储检索
        assertNotNull(context.get(UserContext.class));
        assertNotNull(context.get(DatabaseConfig.class));
        assertEquals(2, context.getStores().size());
    }

    @Test
    void testStorePriority() {
        // 第一个存储的 UserContext 的 userId 为 "first"
        DefaultContextStore store1 =
                DefaultContextStore.builder()
                        .register(new UserContext("first", "session1"))
                        .build();

        // 第二个存储的 UserContext 的 userId 为 "second"
        DefaultContextStore store2 =
                DefaultContextStore.builder()
                        .register(new UserContext("second", "session2"))
                        .build();

        // 列表中第一个存储优先
        ToolExecutionContext context =
                ToolExecutionContext.builder().addStore(store1).addStore(store2).build();

        UserContext retrieved = context.get(UserContext.class);
        assertEquals("first", retrieved.getUserId());
    }
}
