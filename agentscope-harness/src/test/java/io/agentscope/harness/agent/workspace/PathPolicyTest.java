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
package io.agentscope.harness.agent.workspace;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

class PathPolicyTest {

    /** 将 Unix 风格字面路径锚定为当前平台真实绝对路径：Linux/macOS 不变；Windows 加入当前工作目录盘符，使 {@code isAbsolute()} 返回 {@code true}，符合测试意图。 */
    private static Path abs(String p) {
        return Paths.get(p).toAbsolutePath().normalize();
    }

    @Test
    void empty_rejectsEverything() {
        PathPolicy policy = PathPolicy.empty();
        assertTrue(policy.isEmpty());
        assertFalse(policy.isAllowed(abs("/etc/passwd")));
        assertFalse(policy.isAllowed(abs("/")));
    }

    @Test
    void of_acceptsChildOfAnyRoot() {
        Path projectRoot = abs("/users/alice/project");
        Path workspace = abs("/var/agent/workspace");
        PathPolicy policy = PathPolicy.of(projectRoot, workspace);

        assertTrue(policy.isAllowed(abs("/users/alice/project/src/Main.java")));
        assertTrue(policy.isAllowed(abs("/var/agent/workspace/MEMORY.md")));
        assertTrue(policy.isAllowed(projectRoot)); // 根目录本身
    }

    @Test
    void of_rejectsPathsOutsideAllRoots() {
        Path projectRoot = abs("/users/alice/project");
        PathPolicy policy = PathPolicy.of(projectRoot);

        assertFalse(policy.isAllowed(abs("/etc/passwd")));
        // 兄弟目录即使共享路径字符串前缀，但不符合路径分段
        // 边界，也必须拒绝。
        assertFalse(policy.isAllowed(abs("/users/alice/project-other/file")));
    }

    @Test
    void isAllowed_rejectsRelativeAndNullPaths() {
        PathPolicy policy = PathPolicy.of(abs("/users/alice/project"));
        assertFalse(policy.isAllowed(null));
        assertFalse(policy.isAllowed(Paths.get("relative/path.txt")));
    }

    @Test
    void of_normalizesRootsSoTrailingDotsAreIgnored() {
        Path project = abs("/users/alice/project/./sub/..");
        PathPolicy policy = PathPolicy.of(project);

        assertTrue(policy.isAllowed(abs("/users/alice/project/foo.txt")));
    }

    @Test
    void of_collection_skipsNullEntries() {
        PathPolicy policy = PathPolicy.of(java.util.Arrays.asList(abs("/a"), null, abs("/b")));
        assertTrue(policy.isAllowed(abs("/a/x")));
        assertTrue(policy.isAllowed(abs("/b/y")));
    }
}
