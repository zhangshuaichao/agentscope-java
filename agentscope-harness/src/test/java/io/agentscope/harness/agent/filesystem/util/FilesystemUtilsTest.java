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
package io.agentscope.harness.agent.filesystem.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.filesystem.model.EditResult;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FilesystemUtilsTest {

    // ================================================================
    // stringReplacement
    // ================================================================

    @Test
    void stringReplacement_singleOccurrence() {
        FilesystemUtils.ReplacementResult r =
                FilesystemUtils.stringReplacement("Hello World", "World", "Java", false);

        assertTrue(r.isSuccess());
        assertEquals("Hello Java", r.content());
        assertEquals(1, r.occurrences());
    }

    @Test
    void stringReplacement_replaceAll() {
        FilesystemUtils.ReplacementResult r =
                FilesystemUtils.stringReplacement("a b a b a", "a", "x", true);

        assertTrue(r.isSuccess());
        assertEquals("x b x b x", r.content());
        assertEquals(3, r.occurrences());
    }

    @Test
    void stringReplacement_notFound() {
        FilesystemUtils.ReplacementResult r =
                FilesystemUtils.stringReplacement("hello", "nope", "x", false);

        assertFalse(r.isSuccess());
        assertTrue(r.error().contains("String not found"));
    }

    @Test
    void stringReplacement_multipleWithoutReplaceAll() {
        FilesystemUtils.ReplacementResult r =
                FilesystemUtils.stringReplacement("foo bar foo", "foo", "x", false);

        assertFalse(r.isSuccess());
        assertTrue(r.error().contains("2 times"));
    }

    @Test
    void stringReplacement_emptyNewString_deletes() {
        FilesystemUtils.ReplacementResult r =
                FilesystemUtils.stringReplacement("Hello World", "World", "", false);

        assertTrue(r.isSuccess());
        assertEquals("Hello ", r.content());
        assertEquals(1, r.occurrences());
    }

    // ================================================================
    // 行尾规范化，所有文件系统实现共享
    // ================================================================

    @Test
    void stringReplacement_matchesAcrossCrLf() {
        FilesystemUtils.ReplacementResult r =
                FilesystemUtils.stringReplacement("a\r\nb\r\nc", "a\nb", "x", false);

        assertTrue(r.isSuccess(), "a CRLF file must match an LF pattern: " + r.error());
        assertEquals(1, r.occurrences());
    }

    @Test
    void stringReplacement_matchesAcrossLoneCr() {
        FilesystemUtils.ReplacementResult r =
                FilesystemUtils.stringReplacement("a\rb\rc", "a\nb", "x", false);

        assertTrue(r.isSuccess());
        assertEquals(1, r.occurrences());
    }

    @Test
    void stringReplacement_normalizesCrlfPatternGivenByCaller() {
        FilesystemUtils.ReplacementResult r =
                FilesystemUtils.stringReplacement("a\nb", "a\r\nb", "x", false);

        assertTrue(r.isSuccess(), "a CRLF pattern must match an LF file: " + r.error());
        assertEquals(1, r.occurrences());
    }

    @Test
    void stringReplacement_writesBackLfOnly() {
        FilesystemUtils.ReplacementResult r =
                FilesystemUtils.stringReplacement("keep\r\nthis\r\nx\r\n", "x", "y", false);

        assertTrue(r.isSuccess());
        // Unix 风格文件系统会规范化 CRLF，与 LocalFilesystem 长期行为
        // 一致，使三种实现保持相同语义。
        assertEquals("keep\nthis\ny\n", r.content());
        assertFalse(r.content().contains("\r"));
    }

    @Test
    void stringReplacement_replaceAll_countsAcrossMixedLineEndings() {
        // 同一内容中混合 CRLF、LF 和单独 CR，三者必须视为相同分隔符，
        // 让调用方在每个文件系统获得一致出现次数。
        FilesystemUtils.ReplacementResult r =
                FilesystemUtils.stringReplacement("a\r\na\ra\na", "a\na", "-", true);

        assertTrue(r.isSuccess());
        // 由 4 个分隔符连接的 a 对，但出现次数按不重叠匹配计数。
        assertEquals(2, r.occurrences());
        assertEquals("-\n-", r.content());
    }

    @Test
    void stringReplacement_multipleAcrossCrLf_preservesOccurrenceGuard() {
        // CRLF 内容必须与 LF 内容一样触发 >1 保护，否则相同编辑
        // 在一个后端被接受，在另一个后端却被拒绝。
        FilesystemUtils.ReplacementResult r =
                FilesystemUtils.stringReplacement("a\r\na", "a", "x", false);

        assertFalse(r.isSuccess());
        assertTrue(r.error().contains("2 times"));
    }

    @Test
    void stringReplacement_lfOnlyFileIsUnchangedSemantics() {
        FilesystemUtils.ReplacementResult r =
                FilesystemUtils.stringReplacement("Hello World", "World", "Java", false);

        assertTrue(r.isSuccess());
        assertEquals("Hello Java", r.content());
    }

    @Test
    void stringReplacement_rejectsEmptyOldStringDirectly() {
        FilesystemUtils.ReplacementResult r =
                FilesystemUtils.stringReplacement("content", "", "replacement", false);

        assertFalse(r.isSuccess());
        assertTrue(r.error().contains("oldString must not be null or empty"));
    }

    @Test
    void countOccurrences_emptySubstringReturnsZero() {
        assertEquals(0, FilesystemUtils.countOccurrences("content", ""));
    }

    @Test
    void decodeUtf8Strict_acceptsValidTextAndRejectsMalformedBytes()
            throws CharacterCodingException {
        assertEquals(
                "héllo",
                FilesystemUtils.decodeUtf8Strict("héllo".getBytes(StandardCharsets.UTF_8)));
        assertThrows(
                CharacterCodingException.class,
                () -> FilesystemUtils.decodeUtf8Strict(new byte[] {'a', (byte) 0xFF, 'b'}));
    }

    // ================================================================
    // 已弃用桥接层：v2.0.1—v2.0.3 的 Object[] 契约
    // ================================================================

    @SuppressWarnings("deprecation")
    @Test
    void performStringReplacement_bridge_returnsTwoElementArrayOnSuccess() {
        Object[] result =
                FilesystemUtils.performStringReplacement("Hello World", "World", "Java", false);

        assertEquals(2, result.length);
        assertEquals("Hello Java", result[0]);
        assertEquals(1, result[1]);
    }

    @SuppressWarnings("deprecation")
    @Test
    void performStringReplacement_bridge_returnsSingleElementArrayOnError() {
        Object[] notFound = FilesystemUtils.performStringReplacement("hello", "nope", "x", false);
        assertEquals(1, notFound.length);
        assertTrue(((String) notFound[0]).contains("String not found"));

        Object[] multiple =
                FilesystemUtils.performStringReplacement("foo bar foo", "foo", "x", false);
        assertEquals(1, multiple.length);
        assertTrue(((String) multiple[0]).contains("2 times"));
    }

    @SuppressWarnings("deprecation")
    @Test
    void performStringReplacement_bridgePreservesExactLineEndingSemantics() {
        String content = "first\r\nsecond\r\n";
        Object[] exact =
                FilesystemUtils.performStringReplacement(content, "first\nsecond", "x", false);
        FilesystemUtils.ReplacementResult normalized =
                FilesystemUtils.stringReplacement(content, "first\nsecond", "x", false);

        // 桥接层保留 v2.0.x 已发布行为；新类型化 API 有意
        // 规范化行尾。不得静默改变已发布方法的语义。
        assertEquals(1, exact.length);
        assertTrue(((String) exact[0]).contains("String not found"));
        assertTrue(normalized.isSuccess());
        assertEquals("x\n", normalized.content());
    }

    @SuppressWarnings("deprecation")
    @Test
    void performStringReplacement_bridge_agreesWithTypedResult() {
        for (String[] pair :
                new String[][] {
                    {"Hello World", "World"},
                    {"a b a", "a"},
                    {"hello", "nope"},
                    {"foo foo", "foo"}
                }) {
            String content = pair[0];
            String needle = pair[1];
            Object[] legacy = FilesystemUtils.performStringReplacement(content, needle, "x", false);
            FilesystemUtils.ReplacementResult typed =
                    FilesystemUtils.stringReplacement(content, needle, "x", false);

            if (typed.isSuccess()) {
                assertEquals(2, legacy.length, content);
                assertEquals(typed.content(), legacy[0]);
                assertEquals(typed.occurrences(), legacy[1]);
            } else {
                assertEquals(1, legacy.length, content);
                assertEquals(typed.error(), legacy[0]);
            }
        }
    }

    @SuppressWarnings("deprecation")
    @Test
    void performStringReplacement_bridgeKeepsPublishedExactLineEndingBehavior() {
        String content = "first\r\nsecond\r\n";
        Object[] legacy =
                FilesystemUtils.performStringReplacement(content, "first\nsecond", "x", false);

        assertEquals(1, legacy.length);
        assertTrue(((String) legacy[0]).contains("String not found"));

        FilesystemUtils.ReplacementResult current =
                FilesystemUtils.stringReplacement(content, "first\nsecond", "x", false);
        assertTrue(current.isSuccess());
        assertEquals("x\n", current.content());
    }

    // ================================================================
    // validateEditArguments
    // ================================================================

    @Test
    void validateEditArguments_acceptsDeletionAsEmptyString() {
        assertNull(FilesystemUtils.validateEditArguments("World", ""));
    }

    @Test
    void validateEditArguments_rejectsNullNewString() {
        EditResult r = FilesystemUtils.validateEditArguments("World", null);

        assertNotNull(r);
        assertFalse(r.isSuccess());
        assertTrue(r.error().contains("newString must not be null"));
        assertTrue(r.error().contains("empty string to delete"));
    }

    @Test
    void validateEditArguments_rejectsEmptyOrNullOldString() {
        EditResult empty = FilesystemUtils.validateEditArguments("", "x");
        assertNotNull(empty);
        assertTrue(empty.error().contains("oldString must not be null or empty"));

        EditResult nulled = FilesystemUtils.validateEditArguments(null, "x");
        assertNotNull(nulled);
        assertTrue(nulled.error().contains("oldString must not be null or empty"));
    }
}
