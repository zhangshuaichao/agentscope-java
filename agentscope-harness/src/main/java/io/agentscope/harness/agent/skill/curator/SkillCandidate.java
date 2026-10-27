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

import java.util.List;

/**
 * 草稿技能考虑晋级时交给 {@link SkillPromotionGate} 的材料，包含技能正文、辅助文件、遥测记录及晋级前安全扫描结果。
 *
 * <p>{@code scriptFiles} 是与沙箱模式相关的载荷：在 {@code SkillBox .codeExecution()} 场景中，{@code scripts/*} 文件会上传到执行环境供调用。评审者在批准前必须看到每个脚本文件的头部和 sha256；仅上传文件不代表它已自动执行。
 */
public record SkillCandidate(
        String name,
        String description,
        String skillMdContent,
        List<String> supportFilePaths,
        SkillUsageRecord usage,
        SkillSecurityScanner.ScanResult securityScan,
        List<ScriptFilePreview> scriptFiles) {

    public record ScriptFilePreview(
            String relPath, String headPreview, int totalLines, String sha256) {}
}
