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
package io.agentscope.harness.agent.tool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 用于 {@code SkillManageTool#patch} 的模糊匹配阶梯。LLM 很少能完全复现空白：
 * 缩进偏移、编辑器规范化行尾空格、Windows 文件的 CRLF 行尾，以及解析器吞掉前置元数据空白
 * （参见 {@code MarkdownSkillParser.FRONTMATTER_PATTERN}），都会造成严格的
 * {@code String.indexOf} 拒绝的匹配差异。
 *
 * <p>匹配器逐级尝试更宽松的比较，并报告命中的级别，供调用方向 LLM 展示。
 * 每个宽松级别都维护逐字符映射，指回原始 {@code existing} 字符串，
 * 使补丁应用到未经修改的原始内容；绝不改动 LLM 未要求修改的空白。
 *
 * <ul>
 *   <li>{@link Level#EXACT}：严格的 {@code String.indexOf}，保留逐字节保真性
 *   <li>{@link Level#TRAILING_WS_STRIPPED}：在两侧逐行去掉末尾的 {@code ' '/'\t'}，
 *       并将 CRLF 终止符中的 {@code '\r'} 视为换行的一部分而非行内容后比较；
 *       否则 Windows 文件在任何级别都无法匹配仅含 LF 的查找文本。
 *       此级别处理编辑器对行尾空白的规范化。
 *   <li>{@link Level#WHITESPACE_COLLAPSED}：进一步去掉每行前导空白，
 *       将内部连续空白折叠为单个 {@code ' '} 后比较。
 *       此级别处理缩进偏移（制表符与空格、2 空格与 4 空格）及重新换行的文本。
 * </ul>
 *
 * <p>CRLF 终止符折叠为 {@code '\n'}，使 Windows 文件能够匹配仅含 LF 的查找文本。
 * 输出的 {@code '\n'} 映射回 CRLF 起始的 {@code '\r'}；首尾边界均通过同一映射项解析，
 * 因此在规范化级别，匹配区间边缘总是落在终止符边界，不会拆开终止符。
 * {@link Level#EXACT} 不提供此保证：它使用原始 {@code indexOf}，因此以换行开头的
 * 查找文本可能从 CRLF 的 {@code '\n'} 开始并留下 {@code '\r'}，与本次变更之前相同。
 * 无论哪种方式，都只替换匹配区间本身，不重写区间之外的内容。
 *
 * <p>替换文本按原样拼接，因此若查找文本从换行开始，该终止符会交由调用方文本决定：
 * 替换文本提供 {@code '\n'} 就会留下 {@code '\n'}，所以一次补丁可能让 CRLF 文档
 * 同时包含两种行尾。这是调用方文本生效的结果，而不是本类重写了空白。
 *
 * <p>单独的 {@code '\r'}（经典 Mac 行尾，或只有部分行如此结束的文件）
 * 仍被视为普通内容，无法借助换行容差匹配；这超出本类范围，
 * 但由于本类其余说明强调换行容差，因此在此明确边界。
 */
final class FuzzyTextMatcher {

    /**
     * 匹配严格程度，按最严格优先排序，调用方可用 {@code <} 比较。
     */
    enum Level {
        EXACT,
        TRAILING_WS_STRIPPED,
        WHITESPACE_COLLAPSED
    }

    /**
     * {@code existing} 中的一个匹配：原始字符串的字节偏移，左闭右开。
     */
    // 【学习提示】尽管上游说明使用“字节偏移”措辞，实际这里是 Java String 的索引：
    // 以 UTF-16 代码单元计数，不是编码文件的字节偏移；后续 StringBuilder.replace 使用同一坐标系。
    record MatchRange(int start, int end, Level level) {
        int length() {
            return end - start;
        }
    }

    /**
     * 供调用方使用的结果组合。
     */
    record SearchResult(List<MatchRange> matches, Level level) {
        boolean isEmpty() {
            return matches.isEmpty();
        }
    }

    private FuzzyTextMatcher() {}

    /**
     * 从最严格级别开始，在 {@code existing} 中搜索 {@code needle}，
     * 逐步尝试更宽松级别，直到找到至少一个匹配。
     *
     * @return {@link SearchResult} 包含首个非空级别的全部匹配
     *     （使调用方能对同级匹配执行唯一性检查）；所有级别均未命中时 {@code matches} 为空
     */
    static SearchResult search(String existing, String needle) {
        if (existing == null || needle == null || needle.isEmpty()) {
            return new SearchResult(Collections.emptyList(), Level.EXACT);
        }

        // 级别 1：精确匹配。开销小，覆盖常见情况。
        List<MatchRange> exact = findAll(existing, needle, Level.EXACT);
        if (!exact.isEmpty()) {
            return new SearchResult(exact, Level.EXACT);
        }

        // 级别 2：对两侧逐行去掉行尾空白。
        Normalized existingTws = stripTrailingWhitespace(existing);
        Normalized needleTws = stripTrailingWhitespace(needle);
        List<MatchRange> tws =
                findAllNormalized(existingTws, needleTws, Level.TRAILING_WS_STRIPPED);
        if (!tws.isEmpty()) {
            return new SearchResult(tws, Level.TRAILING_WS_STRIPPED);
        }

        // 级别 3：完整折叠空白（逐行去掉首尾空白，将内部连续空白
        // 折叠为单个空格）。最宽松的级别，会向 LLM 显示警告。
        Normalized existingWsc = collapseWhitespace(existing);
        Normalized needleWsc = collapseWhitespace(needle);
        List<MatchRange> wsc =
                findAllNormalized(existingWsc, needleWsc, Level.WHITESPACE_COLLAPSED);
        if (!wsc.isEmpty()) {
            return new SearchResult(wsc, Level.WHITESPACE_COLLAPSED);
        }

        return new SearchResult(Collections.emptyList(), Level.EXACT);
    }

    // ---------------------------------------------------------------------
    // 精确模式辅助方法
    // ---------------------------------------------------------------------

    private static List<MatchRange> findAll(String haystack, String needle, Level level) {
        List<MatchRange> out = new ArrayList<>();
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            out.add(new MatchRange(idx, idx + needle.length(), level));
            idx += needle.length();
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // 规范化搜索
    // ---------------------------------------------------------------------

    /**
     * 在 {@code haystack.text} 中查找 {@code needle.text} 的所有匹配，
     * 再通过 {@link Normalized#originalIndex} 将规范化位置映射回原始字符串偏移。
     */
    private static List<MatchRange> findAllNormalized(
            Normalized haystack, Normalized needle, Level level) {
        List<MatchRange> out = new ArrayList<>();
        if (needle.text.isEmpty()) {
            return out;
        }
        int idx = 0;
        while ((idx = haystack.text.indexOf(needle.text, idx)) >= 0) {
            int origStart = haystack.originalIndex[idx];
            int needleEndExcl = idx + needle.text.length();
            int origEnd;
            if (needleEndExcl < haystack.originalIndex.length) {
                origEnd = haystack.originalIndex[needleEndExcl];
            } else {
                origEnd = haystack.originalLength();
            }
            out.add(new MatchRange(origStart, origEnd, level));
            idx = needleEndExcl;
        }
        return out;
    }

    /**
     * 字符串的规范化视图，以及逐字符映射回原始字符串的索引。
     * 对于 {@code [0, text.length())} 内每个 {@code i}，{@code originalIndex[i]}
     * 表示生成 {@code text.charAt(i)} 的源字符在原字符串中的偏移。
     *
     * <p>折叠后的终止符映射到其<em>起始</em> {@code '\r'}，绝不映射到结束的
     * {@code '\n'}。匹配区间两侧边界读取同一映射，因此在换行处开始或结束的匹配
     * 从任一侧都会落到终止符边界，不会留下游离的 {@code '\r'}。
     * 如果将前导换行向前解析到其 {@code '\n'}，就会拆开终止符：替换文本按原样拼接，
     * 任何本身不以换行开头的补丁都会把 {@code '\r'} 留在文档中，
     * 并将该行终止符降为 LF。
     */
    private record Normalized(String source, String text, int[] originalIndex) {
        int originalLength() {
            return source.length();
        }
    }

    /**
     * {@code [lineStart, lineEnd)} 内行正文的结束位置（不含）。
     * 紧邻行末 {@code '\n'} 之前的 {@code '\r'} 属于 CRLF 终止符，而非行正文。
     * 若不这样处理，Windows 文件在阶梯任何级别都无法匹配仅含 LF 的查找文本；
     * 同一偏移也作为输出换行的映射目标，防止以行边界结束的匹配吞掉回车。
     */
    private static int lineBodyEnd(String s, int lineStart, int lineEnd) {
        if (lineEnd < s.length() && lineEnd > lineStart && s.charAt(lineEnd - 1) == '\r') {
            return lineEnd - 1;
        }
        return lineEnd;
    }

    /**
     * 去掉每行末尾的 {@code ' '} 和 {@code '\t'}。原样保留换行与行内内容。
     * 映射始终指向产生输出字符的源字符（包括换行）。
     */
    private static Normalized stripTrailingWhitespace(String s) {
        int len = s.length();
        StringBuilder out = new StringBuilder(len);
        int[] map = new int[len + 1];
        int mapLen = 0;
        int i = 0;
        while (i < len) {
            int lineStart = i;
            int lineEnd = s.indexOf('\n', i);
            if (lineEnd < 0) {
                lineEnd = len;
            }
            // CRLF：回车属于终止符，而不是行正文。
            int bodyEnd = lineBodyEnd(s, lineStart, lineEnd);
            // 去掉此行末尾的 ' ' / '\t'。
            int trimEnd = bodyEnd;
            while (trimEnd > lineStart
                    && (s.charAt(trimEnd - 1) == ' ' || s.charAt(trimEnd - 1) == '\t')) {
                trimEnd--;
            }
            for (int j = lineStart; j < trimEnd; j++) {
                out.append(s.charAt(j));
                map[mapLen++] = j;
            }
            if (lineEnd < len) {
                out.append('\n');
                map[mapLen++] = bodyEnd;
            }
            i = lineEnd + 1;
        }
        return new Normalized(s, out.toString(), Arrays.copyOf(map, mapLen));
    }

    /**
     * 积极规范化：逐行去掉首尾空白，并将内部每段连续的
     * {@code ' '}/{@code '\t'} 折叠为单个 {@code ' '}。
     * 保留换行，使行结构仍能约束匹配。
     */
    private static Normalized collapseWhitespace(String s) {
        int len = s.length();
        StringBuilder out = new StringBuilder(len);
        int[] map = new int[len + 1];
        int mapLen = 0;
        int i = 0;
        while (i < len) {
            int lineStart = i;
            int lineEnd = s.indexOf('\n', i);
            if (lineEnd < 0) {
                lineEnd = len;
            }
            // CRLF：回车属于终止符，而不是行内容。
            int bodyEnd = lineBodyEnd(s, lineStart, lineEnd);
            // 跳过前导空白。
            int j = lineStart;
            while (j < bodyEnd && (s.charAt(j) == ' ' || s.charAt(j) == '\t')) {
                j++;
            }
            boolean inRun = false;
            int runOrigStart = -1;
            int contentEmittedAt = mapLen;
            for (; j < bodyEnd; j++) {
                char c = s.charAt(j);
                if (c == ' ' || c == '\t') {
                    if (!inRun) {
                        inRun = true;
                        runOrigStart = j;
                    }
                } else {
                    if (inRun) {
                        // 输出单个折叠空格，并将其映射回连续空白的起始位置。
                        out.append(' ');
                        map[mapLen++] = runOrigStart;
                        inRun = false;
                    }
                    out.append(c);
                    map[mapLen++] = j;
                }
            }
            // 此行尾部空白随 `inRun` 一起丢弃。
            // 仅在实际存在行终止符且已经写出一些内容时输出换行
            // （省略全空白行的换行可使折叠视图更紧凑，
            // 也更不易受 LLM 插入空行的影响）。
            if (lineEnd < len && mapLen > contentEmittedAt) {
                out.append('\n');
                map[mapLen++] = bodyEnd;
            }
            i = lineEnd + 1;
        }
        return new Normalized(s, out.toString(), Arrays.copyOf(map, mapLen));
    }
}
