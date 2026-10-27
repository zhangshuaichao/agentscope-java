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

/**
 * {@link SkillManageTool} 的配置。
 *
 * <p>默认值面向企业生产部署：新技能写入草稿子目录（而非正式技能根目录），
 * 启用静态安全扫描，且智能体不能通过 {@code create} 静默覆盖现有技能。
 *
 * <p>对于个人助理或实验用途，建议覆盖为
 * {@code SkillManageConfig.builder().autoPromote(true).build()}：
 * 智能体直接写入正式技能根目录，在下一轮推理中可见。
 */
public final class SkillManageConfig {

    /**
     * 工作区下默认的草稿暂存子目录。
     */
    public static final String DEFAULT_DRAFTS_DIR = "skills/_drafts";

    /**
     * 工作区下默认的已晋升技能子目录。
     */
    public static final String DEFAULT_MAIN_DIR = "skills";

    private final boolean autoPromote;
    private final boolean securityScan;
    private final String draftsDir;
    private final String mainDir;

    private SkillManageConfig(Builder b) {
        this.autoPromote = b.autoPromote;
        this.securityScan = b.securityScan;
        this.draftsDir = b.draftsDir;
        this.mainDir = b.mainDir;
    }

    /**
     * 跳过草稿暂存路径，直接写入正式技能根目录。
     */
    public boolean autoPromote() {
        return autoPromote;
    }

    /**
     * 每次写入后运行 {@code SkillSecurityScanner}。
     */
    public boolean securityScan() {
        return securityScan;
    }

    /**
     * 草稿存放目录，相对于工作区。
     */
    public String draftsDir() {
        return draftsDir;
    }

    /**
     * 已晋升技能存放目录，相对于工作区。
     */
    public String mainDir() {
        return mainDir;
    }

    public static SkillManageConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean autoPromote = false;
        private boolean securityScan = true;
        private String draftsDir = DEFAULT_DRAFTS_DIR;
        private String mainDir = DEFAULT_MAIN_DIR;

        private Builder() {}

        public Builder autoPromote(boolean autoPromote) {
            this.autoPromote = autoPromote;
            return this;
        }

        public Builder securityScan(boolean securityScan) {
            this.securityScan = securityScan;
            return this;
        }

        public Builder draftsDir(String draftsDir) {
            if (draftsDir != null && !draftsDir.isBlank()) {
                this.draftsDir = draftsDir;
            }
            return this;
        }

        public Builder mainDir(String mainDir) {
            if (mainDir != null && !mainDir.isBlank()) {
                this.mainDir = mainDir;
            }
            return this;
        }

        public SkillManageConfig build() {
            return new SkillManageConfig(this);
        }
    }
}
