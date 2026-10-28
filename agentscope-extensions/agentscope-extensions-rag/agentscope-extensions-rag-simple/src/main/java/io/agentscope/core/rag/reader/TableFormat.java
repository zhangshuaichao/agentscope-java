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

/**
 * Word 文档读取器的表格格式选项枚举。
 *
 * <p>定义提取 Word 内容时如何把表格转换为文本。
 */
public enum TableFormat {
    /**
     * 将表格转换为 Markdown。
     *
     * <p>示例：
     * <pre>
     * | Header1 | Header2 |
     * |---------|---------|
     * | Cell1   | Cell2   |
     * </pre>
     *
     * <p>表格示例中文说明：Header1、Header2 是两列标题，下一行分隔标题和数据，Cell1、Cell2 是一行单元格内容。
     *
     * <p>单元格内换行渲染为 {@code <br>}，使每个表格行仍占一行 Markdown。
     */
    MARKDOWN,

    /**
     * 将表格转换为 JSON。
     *
     * <p>提取为行列表的 JSON 字符串，每行是单元格值列表，可正确处理包含换行的单元格。
     *
     * <p>示例：
     * <pre>
     * &lt;system-info&gt;A table loaded as a JSON array:&lt;/system-info&gt;
     * ["Header1", "Header2"]
     * ["Cell1", "Cell2"]
     * </pre>
     *
     * <p>文本格式示例中文说明：system-info 提示“按 JSON 数组载入的表格”；第一组数组保存列名，后续数组保存每行单元格。示例字符串保持原样以对应实际输出。
     */
    JSON
}
