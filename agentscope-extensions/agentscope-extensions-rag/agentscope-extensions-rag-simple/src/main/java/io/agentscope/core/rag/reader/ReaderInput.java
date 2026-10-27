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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文档读取器的输入包装器。
 *
 * <p>为文本字符串、文件路径、URL 等不同输入来源提供统一接口。
 */
public class ReaderInput {

    private final String content;
    private final InputType type;

    private ReaderInput(String content, InputType type) {
        this.content = content;
        this.type = type;
    }

    /**
     * 从文本字符串创建 ReaderInput。
     *
     * @param text 文本内容
     * @return ReaderInput 实例
     */
    public static ReaderInput fromString(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Text content cannot be null");
        }
        return new ReaderInput(text, InputType.STRING);
    }

    /**
     * 从文件路径创建 ReaderInput。
     *
     * @param filePath 文件路径
     * @return ReaderInput 实例
     * @throws IOException 当文件无法读取时
     */
    public static ReaderInput fromFile(String filePath) throws IOException {
        if (filePath == null) {
            throw new IllegalArgumentException("File path cannot be null");
        }
        return fromFile(Path.of(filePath));
    }

    /**
     * 从文件路径创建 ReaderInput。
     *
     * @param filePath 文件路径
     * @return ReaderInput 实例
     * @throws IOException 当文件无法读取时
     */
    public static ReaderInput fromFile(Path filePath) throws IOException {
        if (filePath == null) {
            throw new IllegalArgumentException("File path cannot be null");
        }
        if (!Files.exists(filePath)) {
            throw new IOException("File does not exist: " + filePath);
        }
        String content = Files.readString(filePath);
        return new ReaderInput(content, InputType.FILE);
    }

    /**
     * 从 File 对象创建 ReaderInput。
     *
     * @param file 文件对象
     * @return ReaderInput 实例
     * @throws IOException 当文件无法读取时
     */
    public static ReaderInput fromFile(File file) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("File cannot be null");
        }
        return fromFile(file.toPath());
    }

    /**
     * 从文件路径字符串创建 ReaderInput。
     *
     * @param path 文件路径字符串
     * @return ReaderInput 实例
     * @throws IllegalArgumentException 当路径无效时
     */
    public static ReaderInput fromPath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("File path cannot be null");
        }
        return fromPath(Path.of(path));
    }

    /**
     * 从文件路径创建 ReaderInput。
     *
     * @param path 文件路径
     * @return ReaderInput 实例
     * @throws IllegalArgumentException 当路径无效时
     */
    public static ReaderInput fromPath(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("File path cannot be null");
        }

        if (!Files.exists(path)) {
            throw new IllegalArgumentException("File does not exist: " + path);
        }

        return new ReaderInput(path.toAbsolutePath().toString(), InputType.FILE);
    }

    /**
     * 获取字符串形式的输入内容。
     *
     * @return 内容字符串
     */
    public String asString() {
        return content;
    }

    /**
     * 获取输入类型。
     *
     * @return 输入类型
     */
    public InputType getType() {
        return type;
    }

    /**
     * 输入类型枚举。
     */
    public enum InputType {
        STRING,
        FILE,
        URL
    }
}
