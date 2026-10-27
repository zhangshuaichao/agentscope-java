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
package io.agentscope.harness.agent.skill.curator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SKILL.md 及技能辅助文件的静态分析扫描器。移植自 hermes-agent 的 {@code tools/skills_guard.py} 正则库，以及“信任级别 × 判定结果”的安装策略。
 *
 * <p>检测类别（每类包含一个或多个正则）：
 * <ul>
 * <li>{@link Category#EXFILTRATION}：通过 curl/wget POST 外传数据。</li>
 * <li>{@link Category#INJECTION}：Markdown 中的提示注入标记。</li>
 * <li>{@link Category#DESTRUCTIVE}：{@code rm -rf /}、{@code mkfs}、{@code dd of=/dev}。</li>
 * <li>{@link Category#PERSISTENCE}：篡改 crontab、systemd 或 shell-rc。</li>
 * <li>{@link Category#NETWORK}：监听套接字、反向 Shell。</li>
 * <li>{@link Category#OBFUSCATION}：base64 转 bash、eval $(curl …) 等混淆。</li>
 * </ul>
 *
 * <p>扫描返回 {@link Verdict}（SAFE / CAUTION / DANGEROUS）及发现列表。调用方（例如 {@code SkillManageTool}、{@code HarnessAgent.promoteSkill}）通过 {@link #shouldAllow(TrustLevel, Verdict)} 将判定与来源映射为安装决策。
 *
 * <p>【学习提示】SAFE 只表示当前正则未发现匹配，不等于代码经过安全证明；模式可能漏报或误报。来源信任策略、人工审批、执行权限与操作系统隔离仍是不同防线，扫描结果不能替代任何一层。
 *
 * <p>这有意只是 Java 侧近似检测，不是安全边界。技能仍运行在宿主配置的执行环境中（见 {@code ShellExecuteTool}）；扫描器用于在编写时捕获明显错误，不能替代沙箱。
 */
public final class SkillSecurityScanner {

    public enum Severity {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }

    public enum Category {
        EXFILTRATION,
        INJECTION,
        DESTRUCTIVE,
        PERSISTENCE,
        NETWORK,
        OBFUSCATION
    }

    public enum Verdict {
        SAFE,
        CAUTION,
        DANGEROUS
    }

    /**
     * 技能的来源。
     */
    public enum TrustLevel {
        BUILTIN,
        TRUSTED,
        COMMUNITY,
        AGENT_CREATED
    }

    public record Finding(
            String patternId,
            Severity severity,
            Category category,
            String file,
            int line,
            String matchText,
            String description) {}

    public record ScanResult(Verdict verdict, List<Finding> findings, String reportText) {}

    /**
     * 单条正则规则。
     */
    private record Rule(
            String id, Severity severity, Category category, Pattern pattern, String description) {}

    // ---------------------------------------------------------------------
    // 内置规则库（移植自 hermes tools/skills_guard.py）
    // ---------------------------------------------------------------------

    private static final List<Rule> RULES = buildRules();

    private static List<Rule> buildRules() {
        List<Rule> rules = new ArrayList<>();

        // 数据外传（EXFILTRATION）--------------------------------------------
        rules.add(
                new Rule(
                        "exfil-curl-post",
                        Severity.HIGH,
                        Category.EXFILTRATION,
                        Pattern.compile("curl\\s+[^\\n]*\\s(-d|--data|-F|--data-binary)\\s"),
                        "curl POST upload — possible data exfiltration"));
        rules.add(
                new Rule(
                        "exfil-wget-post",
                        Severity.HIGH,
                        Category.EXFILTRATION,
                        Pattern.compile("wget\\s+(?:[^\\n]*\\s)?--post-data\\b"),
                        "wget --post-data — possible data exfiltration"));
        rules.add(
                new Rule(
                        "exfil-nc-pipe",
                        Severity.HIGH,
                        Category.EXFILTRATION,
                        Pattern.compile("\\b(cat|tar|gzip)\\s+[^\\n]*\\|\\s*nc\\s"),
                        "piping local data into netcat — exfiltration"));

        // 提示注入（INJECTION）-----------------------------------------------
        rules.add(
                new Rule(
                        "inj-ignore-prev",
                        Severity.MEDIUM,
                        Category.INJECTION,
                        Pattern.compile(
                                "(?i)ignore\\s+(all\\s+)?(your\\s+)?previous\\s+instructions"),
                        "prompt-injection marker: 'ignore previous instructions'"));
        rules.add(
                new Rule(
                        "inj-system-tag",
                        Severity.MEDIUM,
                        Category.INJECTION,
                        Pattern.compile("(?i)<\\s*(system|admin)\\s*>"),
                        "prompt-injection marker: <system> / <admin> tag inside body"));
        rules.add(
                new Rule(
                        "inj-jailbreak",
                        Severity.MEDIUM,
                        Category.INJECTION,
                        Pattern.compile("(?i)\\b(DAN|jailbreak|developer\\s+mode)\\b"),
                        "prompt-injection marker: jailbreak vocabulary"));

        // 破坏性操作（DESTRUCTIVE）------------------------------------------
        rules.add(
                new Rule(
                        "dest-rm-rf-root",
                        Severity.CRITICAL,
                        Category.DESTRUCTIVE,
                        Pattern.compile("rm\\s+-rf?\\s+(--no-preserve-root\\s+)?/(\\s|$)"),
                        "rm -rf / — destroys filesystem"));
        rules.add(
                new Rule(
                        "dest-mkfs",
                        Severity.CRITICAL,
                        Category.DESTRUCTIVE,
                        Pattern.compile("\\bmkfs(\\.[a-z0-9]+)?\\s+/dev/"),
                        "mkfs on a block device — destroys data"));
        rules.add(
                new Rule(
                        "dest-dd-dev",
                        Severity.CRITICAL,
                        Category.DESTRUCTIVE,
                        Pattern.compile("\\bdd\\s+[^\\n]*of=/dev/(sd|nvme|hd|xvd|disk)"),
                        "dd writing to a raw disk device"));
        rules.add(
                new Rule(
                        "dest-redirect-dev",
                        Severity.HIGH,
                        Category.DESTRUCTIVE,
                        Pattern.compile(">\\s*/dev/(sd|nvme|hd|xvd)[a-z0-9]*"),
                        "shell redirect to a raw disk device"));

        // 持久化篡改（PERSISTENCE）------------------------------------------
        rules.add(
                new Rule(
                        "pers-crontab-install",
                        Severity.HIGH,
                        Category.PERSISTENCE,
                        Pattern.compile("\\b(crontab\\s+-)|(echo\\s+[^\\n]*\\s+>>\\s+/etc/cron)"),
                        "installs crontab entry — persistence"));
        rules.add(
                new Rule(
                        "pers-systemd-install",
                        Severity.HIGH,
                        Category.PERSISTENCE,
                        Pattern.compile(
                                "(systemctl\\s+enable\\s+|cp\\s+[^\\n]*\\.service\\s+/etc/systemd"
                                        + "/system|/etc/systemd/system/[^\\s]+\\.service)"),
                        "installs systemd unit — persistence"));
        rules.add(
                new Rule(
                        "pers-rc-tamper",
                        Severity.MEDIUM,
                        Category.PERSISTENCE,
                        Pattern.compile("echo\\s+[^\\n]*>>\\s+~?/?(\\.bashrc|\\.zshrc|\\.profile)"),
                        "writes shell-rc — persistence"));

        // 网络行为（NETWORK）------------------------------------------------
        rules.add(
                new Rule(
                        "net-reverse-shell-bash",
                        Severity.CRITICAL,
                        Category.NETWORK,
                        Pattern.compile("bash\\s+-i\\s+>&\\s*/dev/tcp/"),
                        "bash reverse shell"));
        rules.add(
                new Rule(
                        "net-nc-listen",
                        Severity.HIGH,
                        Category.NETWORK,
                        Pattern.compile("\\bnc\\s+(?:[^\\n]*\\s)?-(l|lvp|nlvp)\\b"),
                        "netcat listener"));
        rules.add(
                new Rule(
                        "net-nc-exec",
                        Severity.CRITICAL,
                        Category.NETWORK,
                        Pattern.compile("\\bnc\\s+(?:[^\\n]*\\s)?-e\\b"),
                        "netcat -e (command execution)"));

        // 混淆（OBFUSCATION）-------------------------------------------------
        rules.add(
                new Rule(
                        "obf-base64-pipe-shell",
                        Severity.HIGH,
                        Category.OBFUSCATION,
                        Pattern.compile("base64\\s+(-d|--decode)\\b[^\\n]*\\|\\s*(bash|sh|zsh)"),
                        "base64 -d | shell — obfuscated execution"));
        rules.add(
                new Rule(
                        "obf-eval-curl",
                        Severity.CRITICAL,
                        Category.OBFUSCATION,
                        Pattern.compile("\\beval\\s+[^\\n]*\\$\\(\\s*(curl|wget)\\b"),
                        "eval $(curl ...) — remote-code execution"));
        rules.add(
                new Rule(
                        "obf-curl-pipe-shell",
                        Severity.HIGH,
                        Category.OBFUSCATION,
                        Pattern.compile("(curl|wget)\\s+[^\\n]*\\|\\s*(bash|sh|zsh)"),
                        "curl/wget piped to shell — remote-code execution"));

        return List.copyOf(rules);
    }

    // ---------------------------------------------------------------------
    // 公共 API
    // ---------------------------------------------------------------------

    /**
     * 扫描完整技能：SKILL.md 加全部辅助文件。{@code resources} 是“相对路径 → 内容”的映射（与 {@code AgentSkill.getResources()} 一致）。返回所有文件中最严重的判定。
     */
    public static ScanResult scan(String skillName, String skillMd, Map<String, String> resources) {
        List<Finding> all = new ArrayList<>();
        if (skillMd != null) {
            all.addAll(scanText("SKILL.md", skillMd));
        }
        if (resources != null) {
            for (Map.Entry<String, String> e : resources.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                all.addAll(scanText(e.getKey(), e.getValue()));
            }
        }
        Verdict verdict = verdictFor(all);
        return new ScanResult(verdict, all, formatReport(skillName, verdict, all));
    }

    /**
     * 扫描单个文件（在 {@code write_file} / {@code patch} 后使用）。
     */
    public static ScanResult scanSingleFile(String relPath, String content) {
        List<Finding> findings = scanText(relPath, content);
        Verdict verdict = verdictFor(findings);
        return new ScanResult(verdict, findings, formatReport(relPath, verdict, findings));
    }

    /**
     * 将“信任级别 × 判定结果”映射为安装决策，对齐 hermes 的 {@code tools/skills_guard.py::INSTALL_POLICY}。
     *
     * @return {@code true} 表示可继续写入技能；{@code false} 表示必须阻止或回滚
     */
    public static boolean shouldAllow(TrustLevel trust, Verdict verdict) {
        return switch (trust) {
            case BUILTIN -> true; // 无条件信任内置技能。
            case TRUSTED -> verdict != Verdict.DANGEROUS;
            case COMMUNITY -> verdict == Verdict.SAFE;
            case AGENT_CREATED ->
                    // 允许 SAFE / CAUTION，阻止 DANGEROUS。Java 移植版将 Hermes 的 ASK 模式
                    // 收敛为 BLOCK，智能体必须修改技能正文，而不是等待交互确认，
                    // 符合企业默认策略。
                    verdict != Verdict.DANGEROUS;
        };
    }

    // ---------------------------------------------------------------------
    // 内部辅助方法
    // ---------------------------------------------------------------------

    private static List<Finding> scanText(String fileLabel, String content) {
        List<Finding> findings = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return findings;
        }
        // 逐规则扫描，报告全部匹配及行号。
        for (Rule rule : RULES) {
            Matcher m = rule.pattern().matcher(content);
            while (m.find()) {
                int line = lineNumberAt(content, m.start());
                String text = m.group();
                if (text.length() > 200) {
                    text = text.substring(0, 200) + "…";
                }
                findings.add(
                        new Finding(
                                rule.id(),
                                rule.severity(),
                                rule.category(),
                                fileLabel,
                                line,
                                text,
                                rule.description()));
            }
        }
        return findings;
    }

    private static int lineNumberAt(String content, int charIndex) {
        int line = 1;
        for (int i = 0; i < charIndex && i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static Verdict verdictFor(List<Finding> findings) {
        if (findings.isEmpty()) {
            return Verdict.SAFE;
        }
        for (Finding f : findings) {
            if (f.severity() == Severity.CRITICAL || f.severity() == Severity.HIGH) {
                return Verdict.DANGEROUS;
            }
        }
        return Verdict.CAUTION;
    }

    private static String formatReport(String title, Verdict verdict, List<Finding> findings) {
        StringBuilder sb = new StringBuilder();
        sb.append("Security scan: ").append(title).append(" — ").append(verdict).append("\n");
        if (findings.isEmpty()) {
            sb.append("  (no findings)\n");
            return sb.toString();
        }
        for (Finding f : findings) {
            sb.append("  [")
                    .append(f.severity())
                    .append("/")
                    .append(f.category())
                    .append("] ")
                    .append(f.patternId())
                    .append(" @ ")
                    .append(f.file())
                    .append(":")
                    .append(f.line())
                    .append(" — ")
                    .append(f.description())
                    .append("\n      match: ")
                    .append(f.matchText())
                    .append("\n");
        }
        return sb.toString();
    }

    private SkillSecurityScanner() {}
}
