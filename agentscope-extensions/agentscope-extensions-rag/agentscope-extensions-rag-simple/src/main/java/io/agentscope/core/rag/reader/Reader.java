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
package io.agentscope.core.rag.reader;

import io.agentscope.core.rag.model.Document;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * 文档读取器接口。
 *
 * <p>提供读取各种文档并分块为 Document 对象的统一 API，实现应支持不同格式与输入来源。
 */
public interface Reader {

    /**
     * 读取文档并返回分块 Document 列表。
     *
     * <p>读取错误通过 {@code Mono.error()} 沿 Mono 传播。
     *
     * @param input 待读取输入，如文本、路径、URL
     * @return 发出 Document 列表的 Mono；失败时发出错误信号
     */
    Mono<List<Document>> read(ReaderInput input);

    /**
     * 获取此读取器支持的文件格式列表。
     *
     * @return 支持的扩展名列表，如 txt、md、pdf
     */
    List<String> getSupportedFormats();
}
