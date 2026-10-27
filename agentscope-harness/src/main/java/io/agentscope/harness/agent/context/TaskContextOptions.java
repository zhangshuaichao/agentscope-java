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
package io.agentscope.harness.agent.context;

/** 按需启用任务投影和候选工具。不会运行验证器，也不会强制任务完成。 */
public record TaskContextOptions(
        boolean requirements, boolean verificationResults, boolean requirementProposals) {
    public TaskContextOptions {
        if (requirementProposals && !requirements)
            throw new IllegalArgumentException(
                    "Requirement proposals require requirement projection");
    }

    public static TaskContextOptions disabled() {
        return new TaskContextOptions(false, false, false);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean requirements;
        private boolean verificationResults;
        private boolean requirementProposals;

        public Builder includeRequirements() {
            requirements = true;
            return this;
        }

        public Builder includeVerificationResults() {
            verificationResults = true;
            return this;
        }

        /** 同时启用候选需求投影，使提案保持可见。 */
        public Builder allowRequirementProposals() {
            requirementProposals = true;
            requirements = true;
            return this;
        }

        public TaskContextOptions build() {
            return new TaskContextOptions(requirements, verificationResults, requirementProposals);
        }
    }
}
