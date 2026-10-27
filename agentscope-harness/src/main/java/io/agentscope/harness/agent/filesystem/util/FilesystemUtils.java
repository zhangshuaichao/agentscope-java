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

import io.agentscope.harness.agent.filesystem.model.EditResult;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 文件系统实现共用的工具函数。
 */
public final class FilesystemUtils {

    private FilesystemUtils() {}

    /**
     * 严格解码 UTF-8，拒绝格式错误或无法映射的字节序列。
     */
    public static String decodeUtf8Strict(byte[] bytes) throws CharacterCodingException {
        CharsetDecoder decoder =
                StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static final Set<String> BINARY_EXTENSIONS =
            Set.of(
                    ".png", ".jpg", ".jpeg", ".gif", ".webp", ".heic", ".heif", ".bmp", ".ico",
                    ".svg", ".mp4", ".mpeg", ".mov", ".avi", ".flv", ".mpg", ".webm", ".wmv",
                    ".3gpp", ".wav", ".mp3", ".aiff", ".aac", ".ogg", ".flac", ".pdf", ".ppt",
                    ".pptx", ".doc", ".docx", ".xls", ".xlsx", ".zip", ".tar", ".gz", ".bz2", ".7z",
                    ".rar", ".class", ".jar", ".war", ".ear", ".so", ".dll", ".dylib", ".exe");

    /**
     * 根据扩展名将文件归类为文本或二进制文件。
     */
    public static String getFileType(String path) {
        if (path == null) {
            return "text";
        }
        int dot = path.lastIndexOf('.');
        if (dot < 0) {
            return "text";
        }
        String ext = path.substring(dot).toLowerCase();
        return BINARY_EXTENSIONS.contains(ext) ? "binary" : "text";
    }

    /**
     * 字符串替换操作的结果。
     */
    public record ReplacementResult(String content, int occurrences, String error) {

        public static ReplacementResult success(String content, int occurrences) {
            return new ReplacementResult(content, occurrences, null);
        }

        public static ReplacementResult error(String message) {
            return new ReplacementResult(null, 0, message);
        }

        public boolean isSuccess() {
            return error == null;
        }
    }

    /**
     * 验证所有文件系统实现共用的 {@code edit()} 参数。
     *
     * <p>统一拒绝两种情况，以确保各实现行为一致：{@code null} 的 {@code newString}
     * 不表示删除（删除请传 {@code ""}，保留正常替换语义和计数），空 {@code oldString} 也无效。
     * {@link #countOccurrences} 防御性地将空子串视为零次匹配。
     *
     * @return 参数无效时返回 {@link EditResult#fail} 结果，否则返回 {@code null} 表示可继续编辑
     */
    public static EditResult validateEditArguments(String oldStr, String newStr) {
        if (oldStr == null || oldStr.isEmpty()) {
            return EditResult.fail("Error: oldString must not be null or empty");
        }
        if (newStr == null) {
            return EditResult.fail(
                    "Error: newString must not be null; pass an empty string to delete the"
                            + " matched text");
        }
        return null;
    }

    /**
     * 执行字符串替换并验证匹配次数。
     *
     * @return 成功时返回包含 {@code [newContent, occurrenceCount]} 的 {@code Object[]}，
     *     失败时返回单元素数组 {@code [errorMessage]}
     * @deprecated 已由 {@link #stringReplacement(String, String, String, boolean)} 替代。
     *     此兼容桥保留已发布 v2.0.1-v2.0.3 实现的精确字符串匹配行为，包括换行符；
     *     类型化方法会将 CRLF／独立 CR 规范化为 LF。
     */
    @Deprecated
    public static Object[] performStringReplacement(
            String content, String oldString, String newString, boolean replaceAll) {
        ReplacementResult result = replaceExact(content, oldString, newString, replaceAll);
        if (!result.isSuccess()) {
            return new Object[] {result.error()};
        }
        return new Object[] {result.content(), result.occurrences()};
    }

    /**
     * 执行字符串替换并验证匹配次数。
     *
     * <p>先将内容和两个模式的换行符规范化为 {@code \n}，调用方无需关心文件使用 CRLF 还是独立 CR。
     * 因此返回内容的换行统一为 LF：在 Unix 风格文件系统中应去除 CRLF；
     * 所有实现必须保持一致，否则同一次编辑可能在一个后端匹配，而在另一个后端不匹配。
     *
     * @return 成功时为 {@link ReplacementResult#success(String, int)}，
     *     失败时为 {@link ReplacementResult#error(String)}
     */
    public static ReplacementResult stringReplacement(
            String content, String oldString, String newString, boolean replaceAll) {
        if (oldString == null || oldString.isEmpty()) {
            return ReplacementResult.error("Error: oldString must not be null or empty");
        }
        if (newString == null) {
            return ReplacementResult.error(
                    "Error: newString must not be null; pass an empty string to delete the"
                            + " matched text");
        }
        String normalizedContent = normalizeLineEndings(content);
        String normalizedOld = normalizeLineEndings(oldString);
        String normalizedNew = normalizeLineEndings(newString);

        return replaceExact(normalizedContent, normalizedOld, normalizedNew, replaceAll);
    }

    /**
     * 已弃用兼容桥使用的精确字符串替换。
     */
    private static ReplacementResult replaceExact(
            String content, String oldString, String newString, boolean replaceAll) {
        if (oldString == null || oldString.isEmpty()) {
            return ReplacementResult.error("Error: oldString must not be null or empty");
        }
        if (newString == null) {
            return ReplacementResult.error(
                    "Error: newString must not be null; pass an empty string to delete the"
                            + " matched text");
        }

        int occurrences = countOccurrences(content, oldString);

        if (occurrences == 0) {
            return ReplacementResult.error("Error: String not found in file: '" + oldString + "'");
        }

        if (occurrences > 1 && !replaceAll) {
            return ReplacementResult.error(
                    "Error: String '"
                            + oldString
                            + "' appears "
                            + occurrences
                            + " times in file. "
                            + "Use replaceAll=true to replace all instances, or provide a more"
                            + " specific string with surrounding context.");
        }

        String newContent;
        if (replaceAll) {
            newContent = content.replace(oldString, newString);
        } else {
            int idx = content.indexOf(oldString);
            newContent =
                    content.substring(0, idx)
                            + newString
                            + content.substring(idx + oldString.length());
        }
        return ReplacementResult.success(newContent, occurrences);
    }

    /**
     * 将 CRLF 和独立 CR 规范化为 LF。
     */
    private static String normalizeLineEndings(String text) {
        if (text.indexOf('\r') < 0) {
            return text;
        }
        return text.replace("\r\n", "\n").replace("\r", "\n");
    }

    /**
     * 统计子串不重叠的出现次数；空子串的匹配次数为零。
     */
    public static int countOccurrences(String text, String sub) {
        if (sub == null || sub.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) >= 0) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    /**
     * 对字符串进行 Shell 转义，以便用于 Shell 命令。
     */
    public static String shellQuote(String s) {
        if (s == null || s.isEmpty()) {
            return "''";
        }
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
