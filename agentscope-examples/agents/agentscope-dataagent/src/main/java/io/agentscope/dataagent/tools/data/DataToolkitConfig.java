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
package io.agentscope.dataagent.tools.data;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** DataAgent 工具包默认配置的 Spring 装配。暴露 {@link DataSourceRegistry}（空 {@link InMemoryDataSourceRegistry}）和 {@link ChartRenderer}（{@link StubChartRenderer}），便于运维分别覆盖，例如用 Spring 配置环境装配 JDBC 注册表或服务端 PNG 渲染器。<p>向主智能体工具包的实际注册在 {@link DataToolkitRegistrar} 中完成，避免 {@code @PostConstruct} 与此处 {@code @Bean} 方法的自注入纠缠。 */
@Configuration
public class DataToolkitConfig {

    private static final Logger log = LoggerFactory.getLogger(DataToolkitConfig.class);

    @Bean
    @ConditionalOnMissingBean(DataSourceRegistry.class)
    public DataSourceRegistry inMemoryDataSourceRegistry() {
        log.info(
                "DataToolkitConfig: no DataSourceRegistry bean found, using empty"
                        + " InMemoryDataSourceRegistry");
        return new InMemoryDataSourceRegistry(List.of());
    }

    @Bean
    @ConditionalOnMissingBean(ChartRenderer.class)
    public ChartRenderer stubChartRenderer() {
        log.info("DataToolkitConfig: no ChartRenderer bean found, using StubChartRenderer");
        return new StubChartRenderer();
    }
}
