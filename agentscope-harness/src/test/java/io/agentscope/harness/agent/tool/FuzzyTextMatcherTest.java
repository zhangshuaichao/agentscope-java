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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.tool.FuzzyTextMatcher.Level;
import io.agentscope.harness.agent.tool.FuzzyTextMatcher.MatchRange;
import io.agentscope.harness.agent.tool.FuzzyTextMatcher.SearchResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 覆盖 {@code SkillManageTool#patch} 使用的模糊匹配逐级策略。各测试覆盖一级匹配、跨级短路（更严格级别找到匹配就优先采用），以及原字符串偏移恢复；补丁必须作用于原始字节，而非规范化视图。 */
class FuzzyTextMatcherTest {

    @Test
    @DisplayName("Empty/null needles return an empty result without crashing")
    void emptyNeedle() {
        assertTrue(FuzzyTextMatcher.search("anything", "").isEmpty());
        assertTrue(FuzzyTextMatcher.search("anything", null).isEmpty());
        assertTrue(FuzzyTextMatcher.search(null, "x").isEmpty());
    }

    @Test
    @DisplayName("Exact match returns EXACT level with byte-precise offsets")
    void exactMatch() {
        String existing = "alpha beta gamma";
        SearchResult r = FuzzyTextMatcher.search(existing, "beta");
        assertEquals(Level.EXACT, r.level());
        assertEquals(1, r.matches().size());
        MatchRange m = r.matches().get(0);
        assertEquals(6, m.start());
        assertEquals(10, m.end());
        assertEquals("beta", existing.substring(m.start(), m.end()));
    }

    @Test
    @DisplayName("Exact match finds all occurrences in order")
    void exactMultiple() {
        String existing = "foo bar foo baz foo";
        SearchResult r = FuzzyTextMatcher.search(existing, "foo");
        assertEquals(Level.EXACT, r.level());
        assertEquals(3, r.matches().size());
        assertEquals(0, r.matches().get(0).start());
        assertEquals(8, r.matches().get(1).start());
        assertEquals(16, r.matches().get(2).start());
    }

    @Test
    @DisplayName("Trailing-whitespace ladder matches when needle missed trailing spaces")
    void trailingWhitespaceStripped() {
        // existing 第二行带尾部空格，needle 不带。
        String existing = "line one   \nline two\nline three\n";
        String needle = "line one\nline two\nline three";
        SearchResult r = FuzzyTextMatcher.search(existing, needle);
        assertEquals(Level.TRAILING_WS_STRIPPED, r.level());
        assertEquals(1, r.matches().size());
        // 原始偏移应覆盖从 "line one" 起点到 "line three" 末尾。
        MatchRange m = r.matches().get(0);
        assertEquals(0, m.start());
        // "line three" 末尾，即最后换行前的字符位于索引 31。
        // 只有 needle 包含尾换行时才纳入范围；本例不包含。
        assertTrue(existing.substring(m.start(), m.end()).contains("line three"));
    }

    @Test
    @DisplayName("Whitespace-collapsed ladder catches indentation drift")
    void whitespaceCollapsed() {
        // existing 使用 4 个空格缩进，needle 使用制表符；只删除行尾空白
        // 无法匹配，因为行首空白仍不同，折叠空白级别才可处理。
        String existing = "if cond:\n    return 1\n    return 2\n";
        String needle = "if cond:\n\treturn 1\n\treturn 2";
        SearchResult r = FuzzyTextMatcher.search(existing, needle);
        assertEquals(Level.WHITESPACE_COLLAPSED, r.level());
        assertEquals(1, r.matches().size());
        // 匹配必须从原文 if 的起点开始，不能包括匹配范围外的
        // 行首空白字节。
        MatchRange m = r.matches().get(0);
        assertEquals(0, m.start());
        // 从原文取出的子串仍应有意义，包含
        // return 2。
        assertTrue(existing.substring(m.start(), m.end()).contains("return 2"));
    }

    @Test
    @DisplayName("Strict level wins even if looser levels would also match")
    void strictWins() {
        // existing 中 needle 有两种形态：
        // - 一处精确匹配（"hello world"）
        // - 一处空白变形（"hello  world"，双空格）
        // 精确级别应只找到第一处并短路返回。
        String existing = "hello world\n---\nhello  world";
        String needle = "hello world";
        SearchResult r = FuzzyTextMatcher.search(existing, needle);
        assertEquals(Level.EXACT, r.level());
        assertEquals(1, r.matches().size());
        assertEquals(0, r.matches().get(0).start());
    }

    @Test
    @DisplayName("Returns empty when no level matches")
    void noMatch() {
        SearchResult r = FuzzyTextMatcher.search("foo bar baz", "absent");
        assertTrue(r.isEmpty());
    }

    @Test
    @DisplayName(
            "Range mapping correctness — replacing in original substring yields expected patch")
    void rangeMapsToOriginal() {
        String existing = "  prefix\n    body  \n    tail\n";
        String needle = "body";
        SearchResult r = FuzzyTextMatcher.search(existing, needle);
        // 此处为精确匹配。
        MatchRange m = r.matches().get(0);
        String patched = existing.substring(0, m.start()) + "BODY" + existing.substring(m.end());
        assertEquals("  prefix\n    BODY  \n    tail\n", patched);
    }

    @Test
    @DisplayName("Trailing-ws match preserves the original trailing whitespace in surrounding text")
    void trailingWsPreservesSurroundings() {
        // needle 不含尾部空格，但原文包含；补丁后这些
        // 空格必须保留，只替换 LLM 要求替换的范围。
        String existing = "header  \nold value  \nfooter\n";
        String needle = "old value";
        SearchResult r = FuzzyTextMatcher.search(existing, needle);
        assertEquals(Level.EXACT, r.level());
        MatchRange m = r.matches().get(0);
        String patched =
                existing.substring(0, m.start()) + "new value" + existing.substring(m.end());
        // 带尾空格的 header 及 old value → new value 的尾空格都应保留。
        assertEquals("header  \nnew value  \nfooter\n", patched);
    }

    @Test
    @DisplayName("Whitespace-collapsed match in middle of buffer preserves prefix/suffix bytes")
    void wsCollapsedMidBufferPreservesBytes() {
        // 在逐字节精确的前后缀之间插入模糊匹配块；前后缀有自身空白，
        // 仅修补模糊块后，前后缀必须
        // 保持字节完全一致。
        String existing = "PREFIX_KEEP\n\n  if x:\n    foo\nSUFFIX_KEEP";
        String needle = "if x:\n\tfoo";
        SearchResult r = FuzzyTextMatcher.search(existing, needle);
        assertNotEquals(Level.EXACT, r.level());
        assertEquals(1, r.matches().size());
        MatchRange m = r.matches().get(0);
        String patched =
                existing.substring(0, m.start()) + "REPLACED" + existing.substring(m.end());
        assertTrue(patched.startsWith("PREFIX_KEEP\n\n"), "prefix bytes must survive verbatim");
        assertTrue(patched.endsWith("SUFFIX_KEEP"), "suffix bytes must survive verbatim");
        assertTrue(patched.contains("REPLACED"));
    }

    // ---------------------------------------------------------------------
    // CRLF 覆盖：Windows 编写的技能文件每行以 CRLF 结束，
    // 而 LLM 几乎总返回 LF。修复前，两个规范化器都把 '\r'
    // 作为普通内容，导致 CRLF 原文无法在任何匹配级别匹配 LF needle，
    // 甚至最宽松级别也失败，而该级别本应处理
    // 这种空白差异。
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("CRLF file matches an LF needle at the trailing-whitespace level")
    void crlfExistingMatchesLfNeedle() {
        String existing = "alpha   \r\nbeta\r\ngamma\r\n";
        String needle = "alpha\nbeta\ngamma";
        SearchResult r = FuzzyTextMatcher.search(existing, needle);
        assertEquals(Level.TRAILING_WS_STRIPPED, r.level());
        assertEquals(1, r.matches().size());
    }

    @Test
    @DisplayName("CRLF match range maps back without eating the line terminator")
    void crlfMatchRangePreservesTerminators() {
        String existing = "PREFIX_KEEP\r\nalpha   \r\nbeta\r\nSUFFIX_KEEP\r\n";
        String needle = "alpha\nbeta";
        SearchResult r = FuzzyTextMatcher.search(existing, needle);
        assertEquals(Level.TRAILING_WS_STRIPPED, r.level());
        assertEquals(1, r.matches().size());
        MatchRange m = r.matches().get(0);
        // 匹配范围精确覆盖原文中 needle 对应的字节，包括内部 CRLF，
        // 但停在最后匹配行结尾的 '\r' 之前；若将输出换行映射到
        // '\n'，就会吞掉该 '\r'，静默改写
        // 补丁边界的行结束符。
        assertEquals("alpha   \r\nbeta", existing.substring(m.start(), m.end()));
        String patched = existing.substring(0, m.start()) + "ALPHA" + existing.substring(m.end());
        assertEquals("PREFIX_KEEP\r\nALPHA\r\nSUFFIX_KEEP\r\n", patched);
    }

    @Test
    @DisplayName("CRLF file still reaches the collapsed level when indentation drifts")
    void crlfCollapseLevelHandlesIndentDrift() {
        String existing = "if cond:\r\n    return 1\r\n    return 2\r\n";
        String needle = "if cond:\n\treturn 1\n\treturn 2";
        SearchResult r = FuzzyTextMatcher.search(existing, needle);
        assertEquals(Level.WHITESPACE_COLLAPSED, r.level());
        assertEquals(1, r.matches().size());
    }

    @Test
    @DisplayName("A CRLF document behaves exactly like its LF twin")
    void crlfBehavesLikeLfTwin() {
        String lf = "header\n\nalpha   \n   beta\nfooter\n";
        String crlf = lf.replace("\n", "\r\n");
        for (String needle :
                new String[] {
                    "alpha\nbeta", "header\nalpha\nbeta\nfooter", "\nalpha\nbeta", "\n\nalpha\nbeta"
                }) {
            SearchResult onLf = FuzzyTextMatcher.search(lf, needle);
            SearchResult onCrlf = FuzzyTextMatcher.search(crlf, needle);
            assertEquals(onLf.isEmpty(), onCrlf.isEmpty(), "emptiness differs for: " + needle);
            assertEquals(onLf.level(), onCrlf.level(), "level differs for: " + needle);
            assertEquals(
                    onLf.matches().size(),
                    onCrlf.matches().size(),
                    "match count differs for: " + needle);
            for (int i = 0; i < onLf.matches().size(); i++) {
                MatchRange lfRange = onLf.matches().get(i);
                MatchRange crlfRange = onCrlf.matches().get(i);
                assertEquals(
                        lf.substring(lfRange.start(), lfRange.end()),
                        crlf.substring(crlfRange.start(), crlfRange.end()).replace("\r", ""),
                        "mapped range differs for: " + needle);
            }
        }
    }

    @Test
    @DisplayName("A newline-leading needle patches a CRLF document exactly as its LF twin")
    void crlfLeadingNewlinePatchMatchesLfTwin() {
        // 每组数据为 CRLF 文档、needle、替换文本；LF 对照文档只折叠行结束符，
        // 这样补丁残留的字节会表现为差异，
        // 而不会被比较逻辑忽略。
        String[][] cases = {
            // needle 首行为空白：匹配范围从行结束符开始，而非正文。
            {"alpha\r\nbeta   \r\ngamma\r\n", "\nbeta\ngamma", "\nBETA"},
            // 两个前导换行：范围从第二个结束符开始；若边界规则只跳过
            // 一个 '\r'，会把第一个留成输出中的孤立 CR。
            {"one\r\n\r\nalpha beta\r\n", "\n\nalpha beta", "REPL"},
            // 相同形态，但替换文本自身重新提供两个换行。
            {"alpha\r\nbeta   \r\ngamma\r\n", "\nbeta\ngamma", "\nBETA\nGAMMA"},
            // 无前导换行：范围从普通内容开始。
            {"alpha\r\nbeta\r\ngamma\r\n", "beta\ngamma", "BETA"},
        };
        for (String[] c : cases) {
            String onCrlf = patchOnce(c[0], c[1], c[2]);
            String onLf = patchOnce(c[0].replace("\r\n", "\n"), c[1], c[2]);
            // 只折叠完整 CRLF 对，保留孤立 '\r'；这样孤立回车
            // 不会被规范化隐藏，而会使比较失败。
            assertEquals(onLf, onCrlf.replace("\r\n", "\n"), "patched output differs for: " + c[1]);
        }
        // 固定字节结果，而非只比较等价性。前导换行属于匹配范围，因此
        // 替换文本的 '\n' 落在该结束符上，后面未改的结束符仍
        // 为 CRLF；混合结果来自替换生效，而不是匹配器重写
        // 空白。若起点锚定在 '\r' 上，本例会全为 CRLF，
        // 但上方双换行例会残留一个 '\r'，因此两种形态一起固定。
        assertEquals("alpha\nBETA\r\n", patchOnce(cases[0][0], cases[0][1], cases[0][2]));
        // 空行情况由尾空白处理级别匹配，而非折叠级别：
        // needle 自身换行直接匹配 CRLF 文档折叠后的结束符，
        // 不会忽略超出必要范围的空白。
        assertEquals(
                Level.TRAILING_WS_STRIPPED,
                FuzzyTextMatcher.search(cases[1][0], cases[1][1]).level());
    }

    /** 按 {@code SkillManageTool#patch} 的方式应用首个匹配，原样拼接。 */
    private static String patchOnce(String document, String needle, String replacement) {
        SearchResult r = FuzzyTextMatcher.search(document, needle);
        assertEquals(1, r.matches().size(), "expected exactly one match for: " + needle);
        MatchRange m = r.matches().get(0);
        return document.substring(0, m.start()) + replacement + document.substring(m.end());
    }
}
