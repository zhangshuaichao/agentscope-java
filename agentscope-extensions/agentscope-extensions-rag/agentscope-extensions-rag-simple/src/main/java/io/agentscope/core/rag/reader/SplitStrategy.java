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
 * 文本分块策略。
 *
 * <p>不同内容适用不同策略：
 * <ul>
 * <li>CHARACTER：按字符数拆分，简单快速，但可能截断词语。</li>
 * <li>PARAGRAPH：按段落拆分，保留语义单元。</li>
 * <li>TOKEN：按近似 token 数拆分，近似适配 LLM 长度限制。</li>
 * <li>SEMANTIC：按语义边界拆分，需要 NLP，尚未实现。</li>
 * </ul>
 */
public enum SplitStrategy {
    /**
     * 按字符数拆分文本。
     *
     * <p>最简单的策略，按字符数形成固定大小块，可能在词语中间截断。
     */
    CHARACTER,

    /**
     * 按段落拆分文本。
     *
     * <p>在双换行段落边界拆分，保留语义单元，块大小可能不同。
     */
    PARAGRAPH,

    /**
     * 按近似 token 数拆分文本。
     *
     * <p>用于近似适配 LLM 上下文限制，采用每 token 约 4 字符的简单启发式。
     */
    TOKEN,

    /**
     * 按语义边界拆分文本。
     *
     * <p>设计上在句子、分句等语义边界拆分以保留意义；目前尚未实现，回退到 PARAGRAPH。
     */
    SEMANTIC
}
