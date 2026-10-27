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
package io.agentscope.harness.agent.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.CompositeFilesystem;
import io.agentscope.harness.agent.filesystem.OverlayFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * 展开用户消息中的 {@code @path} 引用：通过当前 {@link AbstractFilesystem}
 * 读取引用文件，并在消息末尾追加 {@code <attached_file>} 块。
 * 对应 Claude Code CLI 的 {@code @file} 输入语法。
 *
 * <p>按文件系统类型分派（只有一个调用点，因此保留内联 {@code instanceof} 判断）：
 * <ul>
 *   <li><b>本地</b>（{@link OverlayFilesystem} 包装 {@link LocalFilesystemWithShell}）——
 *       尝试 {@code fs.read}；成功则附加内容，失败时（通常是路径不在
 *       {@code PathPolicy} 允许列表中）保留原 {@code @path} 文本。
 *   <li><b>沙箱</b>（{@link AbstractSandboxFilesystem}，不由 overlay 包装）——
 *       将引用视为沙箱内路径；宿主机路径上传不属于此阶段职责。
 *   <li><b>远程</b>（{@link CompositeFilesystem}）—— 禁用展开。智能体与用户不在同一机器，
 *       {@code @path} 没有合理的本地解析语义，因此保持原样。
 * </ul>
 *
 * <p>识别三类引用：绝对路径（{@code @/abs/path}）、相对路径
 * （{@code @./rel} 或 {@code @rel/foo}）及用户主目录路径（{@code @~/file}）。
 * 不含斜线、点或波浪号等路径特征的单独 {@code @word} 会被忽略，
 * 避免吞掉 {@code @alice} 这样的用户标记。
 */
public class AtPathExpansionMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(AtPathExpansionMiddleware.class);

    /**
     * 匹配紧随路径词元的 {@code @}。词元必须至少包含斜线、点或波浪号等一种路径标记，
     * 避免吞掉用户标记。末尾标点（{@code . , ; : ! ? CJK marks}）被排除，
     * 防止句子边界进入捕获的路径。
     */
    private static final Pattern AT_PATH =
            Pattern.compile(
                    "(?<![A-Za-z0-9_])"
                            + "@(?<path>[A-Za-z]:[\\\\/][\\w\\\\./\\-~]*"
                            + "|[~./][\\w./\\-~]*"
                            + "|/[\\w./\\-~]+"
                            + "|[\\w\\-]+[/.][\\w./\\-~]*)");

    /** 限制每个附件文件读取的字节数，避免意外的 {@code @log} 引用撑大上下文。 */
    private static final int MAX_ATTACHED_LINES = 1000;

    /** 附件块的结束标签；附件内容中出现此字符串时也需要中和处理。 */
    private static final String ATTACHED_FILE_CLOSE_TAG = "</attached_file>";

    /**
     * 追加预览块的引导文本；提前说明预览可能被截断，
     * 使模型在需要完整内容时读取原路径。
     */
    private static final String ATTACHMENT_LEAD_IN =
            "Attached file previews follow; previews may be truncated - read the original path"
                    + " with file tools when complete content matters.";

    private final WorkspaceManager workspaceManager;

    public AtPathExpansionMiddleware(WorkspaceManager workspaceManager) {
        this.workspaceManager = workspaceManager;
    }

    /** 仅声明所需扩展点；子类若重写更多钩子，必须扩展此集合。 */
    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_AGENT);
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        AbstractFilesystem fs = workspaceManager.getFilesystem();
        if (!supportsExpansion(fs)) {
            return next.apply(input);
        }

        RuntimeContext rc = ctx != null ? ctx : RuntimeContext.empty();

        List<Msg> rewritten = new ArrayList<>(input.msgs().size());
        int[] remainingChars = {64000};
        boolean changed = false;
        for (Msg msg : input.msgs()) {
            if (msg.getRole() != MsgRole.USER) {
                rewritten.add(msg);
                continue;
            }
            Msg expanded = expand(msg, fs, rc, remainingChars);
            if (expanded != msg) {
                changed = true;
            }
            rewritten.add(expanded);
        }
        return next.apply(changed ? new AgentInput(rewritten) : input);
    }

    private boolean supportsExpansion(AbstractFilesystem fs) {
        if (fs == null) {
            return false;
        }
        // 远程（CompositeFilesystem）是分布式部署，没有宿主机路径。
        if (fs instanceof CompositeFilesystem) {
            return false;
        }
        return true;
    }

    private Msg expand(Msg msg, AbstractFilesystem fs, RuntimeContext rc, int[] remainingChars) {
        String text = msg.getTextContent();
        if (text == null || text.indexOf('@') < 0) {
            return msg;
        }

        Matcher m = AT_PATH.matcher(text);
        Map<String, String> attached = new LinkedHashMap<>();
        while (m.find()) {
            if (remainingChars[0] <= 0) break;
            String ref = m.group("path");
            if (attached.containsKey(ref)) {
                continue;
            }
            String content = tryRead(fs, rc, ref);
            if (content != null) {
                if (content.length() > remainingChars[0]) {
                    content =
                            content.substring(0, remainingChars[0])
                                    + "\n"
                                    + "[Attachment preview truncated; read the original path for"
                                    + " more.]";
                }
                remainingChars[0] = Math.max(0, remainingChars[0] - content.length());
                attached.put(ref, content);
            }
        }
        if (attached.isEmpty()) {
            return msg;
        }

        StringBuilder sb = new StringBuilder(ATTACHMENT_LEAD_IN);
        for (Map.Entry<String, String> e : attached.entrySet()) {
            String content = neutralizeCloseTag(e.getValue());
            sb.append("\n\n<attached_file path=\"")
                    .append(e.getKey())
                    .append("\">\n")
                    .append(content)
                    .append(content.endsWith("\n") ? "" : "\n")
                    .append(ATTACHED_FILE_CLOSE_TAG);
        }
        // 将预览作为新块追加，而不是替换内容：原始消息中的图片
        // 和其他非文本块必须在展开后保留。
        List<ContentBlock> expandedContent = new ArrayList<>(msg.getContent());
        expandedContent.add(TextBlock.builder().text(sb.toString()).build());
        return msg.withContent(expandedContent);
    }

    private String tryRead(AbstractFilesystem fs, RuntimeContext rc, String ref) {
        String resolved = expandHome(ref);
        try {
            ReadResult r = fs.read(rc, resolved, 0, MAX_ATTACHED_LINES);
            if (r.isSuccess() && r.fileData() != null && r.fileData().content() != null) {
                String content = r.fileData().content();
                // 跳过二进制附件：用户消息中的 base64 通常没有帮助，
                // 只会消耗词元。
                if ("base64".equals(r.fileData().encoding())) {
                    return null;
                }
                if (countLines(content) >= MAX_ATTACHED_LINES && hasMoreLines(fs, rc, resolved)) {
                    content =
                            content
                                    + "\n[Attachment preview limited to the first "
                                    + MAX_ATTACHED_LINES
                                    + " lines; read the original path for more.]";
                }
                return content;
            }
        } catch (SecurityException e) {
            log.debug("@-path expansion refused for {} ({})", ref, e.getMessage());
        } catch (Exception e) {
            log.debug("@-path expansion failed for {}: {}", ref, e.getMessage());
        }
        return null;
    }

    /** 中和附件内容中的结束标签，防止预览伪造块边界。 */
    private static String neutralizeCloseTag(String content) {
        return content.replace(ATTACHED_FILE_CLOSE_TAG, "<\\/attached_file>");
    }

    /**
     * 在上限之外再探测两行，避免首个被省略行为空而隐藏后续内容。
     * 在 EOF 处读到空内容（包括本地文件系统中单独的末尾换行）不算截断，
     * 但被省略的空白行仍算截断内容。
     */
    private boolean hasMoreLines(AbstractFilesystem fs, RuntimeContext rc, String resolved) {
        try {
            ReadResult probe = fs.read(rc, resolved, MAX_ATTACHED_LINES, 2);
            return probe.isSuccess()
                    && probe.fileData() != null
                    && probe.fileData().content() != null
                    && !probe.fileData().content().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private static int countLines(String content) {
        return content.split("\n", -1).length;
    }

    private static String expandHome(String ref) {
        if (ref.startsWith("~/")) {
            String home = System.getProperty("user.home");
            return home != null ? home + ref.substring(1) : ref;
        }
        return ref;
    }
}
