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
package io.agentscope.core.tool.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.tool.AgentTool;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 注册 MCP 工具必须通过公开 {@link AgentTool} 接口保留服务端可读显示标题，使权限确认界面能向终端用户标识操作，而非显示 {@code repair__create_ticket}。
 *
 * <p>标题同样是不可信输入：MCP 服务端选择此字符串，展示给即将批准操作的人，因此测试也固定其清理规则与长度上限。
 */
class McpToolTitleTest {

    private McpClientWrapper clientWrapper;
    private Map<String, Object> parameters;

    private static final Map<String, Object> INPUT_SCHEMA = Map.of("type", "object");

    @BeforeEach
    void setUp() {
        clientWrapper = McpClientWrapperTestSupport.mockWrapper("test-client", true);
        parameters = new HashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", new HashMap<>());
    }

    private static McpSchema.Tool toolWith(String title, String annotationTitle) {
        McpSchema.Tool.Builder builder =
                McpSchema.Tool.builder("create_ticket", INPUT_SCHEMA)
                        .description("Creates a repair ticket");
        if (title != null) {
            builder.title(title);
        }
        if (annotationTitle != null) {
            builder.annotations(McpSchema.ToolAnnotations.builder().title(annotationTitle).build());
        }
        return builder.build();
    }

    @Test
    void resolveDisplayTitle_prefersToolTitle() {
        McpSchema.Tool tool = toolWith("Repair Ticket", "Annotation Title");

        assertEquals("Repair Ticket", McpTool.resolveDisplayTitle(tool));
    }

    @Test
    void resolveDisplayTitle_fallsBackToAnnotationTitle() {
        McpSchema.Tool tool = toolWith(null, "Repair Ticket");

        assertEquals("Repair Ticket", McpTool.resolveDisplayTitle(tool));
    }

    @Test
    void resolveDisplayTitle_skipsBlankTitles() {
        McpSchema.Tool blank =
                McpSchema.Tool.builder("create_ticket", INPUT_SCHEMA).description("desc").build();
        assertNull(McpTool.resolveDisplayTitle(blank), "a blank title is no title");

        assertNull(
                McpTool.resolveDisplayTitle(null),
                "a missing tool definition must not fail the registration");
    }

    @Test
    void resolveDisplayTitle_absentAnnotationsYieldsNull() {
        McpSchema.Tool tool =
                McpSchema.Tool.builder("create_ticket", INPUT_SCHEMA).description("desc").build();

        assertNull(McpTool.resolveDisplayTitle(tool));
    }

    // ==================== 服务端输入的显示净化 ====================

    @Test
    void resolveDisplayTitle_dropsZeroWidthAndBidiControlsWithoutAddingSpace() {
        // U+202E/U+202D 会重排后续行内容，U+200C 和 U+00AD 不可见；
        // 这些字符均不得进入确认提示，也都不是词语分隔符。
        assertEquals(
                "DeleteThisNow",
                McpTool.resolveDisplayTitle(toolWith("Delete\u202EThis\u200CNow", null)),
                "bidi and zero-width controls must vanish, not be escaped");
        assertEquals(
                "RepairTicket", McpTool.resolveDisplayTitle(toolWith("Repair\u00ADTicket", null)));
    }

    @Test
    void resolveDisplayTitle_collapsesNewlinesAndControlsIntoOneSpace() {
        assertEquals(
                "Line one Line two",
                McpTool.resolveDisplayTitle(toolWith("  Line one\n\tLine   two  ", null)),
                "the label stays on a single line");
        assertEquals(
                "A B",
                McpTool.resolveDisplayTitle(toolWith("A\u0000\u0007B", null)),
                "C0 controls separate but never pass through");
    }

    @Test
    void resolveDisplayTitle_treatsUnprintableTitleAsAbsentAndFallsBack() {
        McpSchema.Tool tool = toolWith("\n\u200F ", "Repair Ticket");

        assertEquals(
                "Repair Ticket",
                McpTool.resolveDisplayTitle(tool),
                "a title with nothing printable left is no title");
    }

    @Test
    void resolveDisplayTitle_capsLengthAndMarksTruncation() {
        String longTitle = "x".repeat(200);
        String resolved = McpTool.resolveDisplayTitle(toolWith(longTitle, null));

        assertEquals(120, resolved.length(), "the cap is inclusive of the ellipsis");
        assertEquals("x".repeat(117) + "...", resolved);
    }

    @Test
    void resolveDisplayTitle_truncationNeverSplitsASurrogatePair() {
        String title = "y".repeat(116) + "\uD83D\uDE00tail";
        String resolved = McpTool.resolveDisplayTitle(toolWith(title, null));

        // 上限按码点计数，因此边界处 emoji 应完整保留。
        assertEquals("y".repeat(116) + "\uD83D\uDE00" + "...", resolved);
        assertTrue(
                resolved.codePoints()
                        .noneMatch(
                                cp ->
                                        cp >= Character.MIN_SURROGATE
                                                && cp <= Character.MAX_SURROGATE),
                "an unpaired surrogate would corrupt every consumer that re-encodes the label");
    }

    @Test
    void registeredTool_sanitisesATitleHandedStraightToTheConstructor() {
        // 注册不是唯一生产路径，扩展也会直接创建 McpTool，因此防护
        // 必须
        // 位于保存值的地方，而非仅在 resolveDisplayTitle。
        McpTool tool =
                new McpTool(
                        "mcp__create_ticket",
                        "create_ticket",
                        "Creates a repair ticket",
                        parameters,
                        null,
                        clientWrapper,
                        null,
                        "test-client",
                        false,
                        "Delete\u202EAll\u0000Data\nnow");

        assertEquals("DeleteAll Data now", tool.getTitle());
        assertTrue(
                tool.getTitle().codePointCount(0, tool.getTitle().length()) <= 120,
                "the stored title is already display-safe for a prompt");
    }

    @Test
    void registeredTool_exposesTitleThroughAgentToolSurface() {
        McpTool tool =
                new McpTool(
                        "mcp__create_ticket",
                        "create_ticket",
                        "Creates a repair ticket",
                        parameters,
                        null,
                        clientWrapper,
                        null,
                        "test-client",
                        false,
                        "Repair Ticket");

        // Toolkit 向调用方交付 AgentTool，因此必须能通过该接口访问标题。
        AgentTool asAgentTool = tool;
        assertEquals("Repair Ticket", asAgentTool.getTitle());
        assertEquals(
                "mcp__create_ticket",
                asAgentTool.getName(),
                "the programmatic name stays untouched by the display title");
    }

    @Test
    void registeredToolWithoutTitle_returnsNullRatherThanThrowing() {
        McpTool tool =
                new McpTool(
                        "create_ticket",
                        "Creates a repair ticket",
                        parameters,
                        null,
                        clientWrapper,
                        null,
                        "test-client",
                        false);

        assertNull(tool.getTitle(), "servers that send no title keep working unchanged");
    }
}
