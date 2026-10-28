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
package io.agentscope.extensions.jdbc;

import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;

/**
 * 为集成测试创建 H2 内存 DataSource 的支持类。
 *
 * <p>每次调用创建名称唯一的新数据库，使同一类中的测试也不会相互泄漏数据。
 *
 * @author shanhongyu
 */
public final class H2TestSupport {

    private H2TestSupport() {}

    /**
     * 创建具有唯一数据库名称的全新 H2 内存数据源。
     *
     * @param dbName 数据库基础名称，将追加 UUID 后缀
     * @return 可用的 DataSource
     */
    public static DataSource createDataSource(String dbName) {
        String uniqueName =
                dbName + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        JdbcDataSource ds = new JdbcDataSource();
        ds.setUrl("jdbc:h2:mem:" + uniqueName + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }
}
