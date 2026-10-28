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

package io.agentscope.extensions.judge.jev.supervision;

import java.util.List;
import java.util.Objects;

/**
 * 宿主提供的证据。由宿主落实访问控制、脱敏与修订版本一致性。
 */
public record SupervisionEvidence(
        String revision,
        String repositoryStatus,
        String diff,
        List<String> changedFiles,
        String instructions,
        boolean documentationRequired,
        Verification verification) {
    public SupervisionEvidence {
        Objects.requireNonNull(revision);
        Objects.requireNonNull(repositoryStatus);
        Objects.requireNonNull(diff);
        changedFiles = List.copyOf(changedFiles);
        Objects.requireNonNull(instructions);
    }

    /**
     * 范围与修订版本必须来自验证器，绝非工作智能体的完成声明。
     */
    public record Verification(
            String userId,
            String sessionId,
            String runId,
            String revision,
            String source,
            boolean passed,
            String summary) {
        public Verification {
            Objects.requireNonNull(userId);
            Objects.requireNonNull(sessionId);
            Objects.requireNonNull(runId);
            Objects.requireNonNull(revision);
            Objects.requireNonNull(source);
            Objects.requireNonNull(summary);
        }
    }

    /**
     * 缺少提供者明确表示证据缺失，不是验证成功。
     */
    public static SupervisionEvidence missing() {
        return new SupervisionEvidence("", "", "", List.of(), "", false, null);
    }
}
