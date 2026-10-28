/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ListHashUtil 工具类测试。
 */
class ListHashUtilTest {

    @Test
    void testComputeHashEmptyList() {
        String hash = ListHashUtil.computeHash(List.of());
        assertEquals("empty:0", hash);
    }

    @Test
    void testComputeHashNullList() {
        String hash = ListHashUtil.computeHash(null);
        assertEquals("empty:0", hash);
    }

    @Test
    void testComputeHashSameListSameHash() {
        List<Msg> list = createMsgList(5);

        // 同一列表实例应产生相同哈希
        String hash1 = ListHashUtil.computeHash(list);
        String hash2 = ListHashUtil.computeHash(list);

        assertEquals(hash1, hash2);
    }

    @Test
    void testComputeHashListModifiedDifferentHash() {
        List<Msg> list = createMsgList(5);

        String hashBefore = ListHashUtil.computeHash(list);

        // 修改列表
        list.set(
                2,
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("modified content").build())
                        .build());

        String hashAfter = ListHashUtil.computeHash(list);

        assertNotEquals(hashBefore, hashAfter);
    }

    @Test
    void testComputeHashListGrowsDifferentHash() {
        List<Msg> list = createMsgList(5);

        String hashBefore = ListHashUtil.computeHash(list);

        // 添加更多条目
        list.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("new message").build())
                        .build());

        String hashAfter = ListHashUtil.computeHash(list);

        assertNotEquals(hashBefore, hashAfter);
    }

    @Test
    void testHasChangedNullStoredHash() {
        String currentHash = "abc123";
        assertFalse(ListHashUtil.hasChanged(currentHash, null));
    }

    @Test
    void testHasChangedSameHash() {
        String hash = "abc123";
        assertFalse(ListHashUtil.hasChanged(hash, hash));
    }

    @Test
    void testHasChangedDifferentHash() {
        assertTrue(ListHashUtil.hasChanged("abc123", "def456"));
    }

    @Test
    void testNeedsFullRewriteHashChanged() {
        List<Msg> list = createMsgList(5);
        String storedHash = ListHashUtil.computeHash(list);

        // 修改现有前缀中的元素，例如索引 2
        list.set(
                2,
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("modified content").build())
                        .build());

        assertTrue(ListHashUtil.needsFullRewrite(list, storedHash, 5));
    }

    @Test
    void testNeedsFullRewriteListShrunk() {
        List<Msg> list = createMsgList(3);
        // 当前长度 3 小于已有数量 5，必须重写
        assertTrue(ListHashUtil.needsFullRewrite(list, "any_old_hash", 5));
    }

    @Test
    void testNeedsFullRewriteListGrew() {
        List<Msg> list = createMsgList(5);
        String storedHash = ListHashUtil.computeHash(list);

        // 追加新条目，使列表增长到 8
        list.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("msg 5").build())
                        .build());
        list.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("msg 6").build())
                        .build());
        list.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("msg 7").build())
                        .build());

        // 前缀的前 5 项未变化，无需完整重写，可增量追加
        assertFalse(ListHashUtil.needsFullRewrite(list, storedHash, 5));
    }

    @Test
    void testNeedsFullRewriteNoChange() {
        List<Msg> list = createMsgList(5);
        String storedHash = ListHashUtil.computeHash(list);

        // 长度和内容完全相同，无需重写
        assertFalse(ListHashUtil.needsFullRewrite(list, storedHash, 5));
    }

    @Test
    void testNeedsFullRewriteFirstSave() {
        List<Msg> list = createMsgList(5);

        // 首次保存时无存储哈希，已有数量为 0，无需完整重写
        assertFalse(ListHashUtil.needsFullRewrite(list, null, 0));
    }

    @Test
    void testNeedsFullRewriteNullList() {
        // 当前列表为 null，但此前存在条目时，必须重写，相当于
        // 删除全部条目
        assertTrue(ListHashUtil.needsFullRewrite(null, "some_hash", 5));

        // 当前列表为 null 且已有数量为 0，无需重写
        assertFalse(ListHashUtil.needsFullRewrite(null, null, 0));
    }

    @Test
    void testNeedsFullRewriteMissingHashButHasData() {
        List<Msg> list = createMsgList(5);

        // 场景：existingCount 为 5，但 storedHash 为 null
        // 例如从不保存哈希的旧版本升级
        assertTrue(ListHashUtil.needsFullRewrite(list, null, 5));
    }

    private List<Msg> createMsgList(int size) {
        List<Msg> list = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            list.add(
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("message " + i).build())
                            .build());
        }
        return list;
    }
}
