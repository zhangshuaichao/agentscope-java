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
package io.agentscope.core.skill.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.skill.AgentSkill;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * GitSkillRepository 单元测试。
 *
 * <p>使用本地 Git 仓库（file:// 协议）测试基于 Git 的技能仓库功能，避免网络依赖。
 *
 * <p>标记为 "unit"，属于无需外部网络的快速测试。
 */
@Tag("unit")
@DisplayName("GitSkillRepository Unit Tests")
class GitSkillRepositoryTest {

    @TempDir Path tempDir;

    private GitSkillRepository repository;

    @AfterEach
    void tearDown() {
        if (repository != null) {
            try {
                repository.close();
            } catch (Exception e) {
                // 测试中忽略清理错误。
            }
        }
    }

    // ==================== 构造方法测试 ====================

    @Test
    @DisplayName("Should create repository with valid URL")
    void testConstructor_ValidUrl() {
        repository = new GitSkillRepository("https://github.com/test/repo.git");
        assertNotNull(repository);
        assertEquals("git", repository.getRepositoryInfo().getType());
        assertFalse(repository.isWriteable());
    }

    @Test
    @DisplayName("Should throw exception when URL is null")
    void testConstructor_NullUrl() {
        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> new GitSkillRepository(null));
        assertTrue(exception.getMessage().contains("cannot be null"));
    }

    @Test
    @DisplayName("Should throw exception when URL is empty")
    void testConstructor_EmptyUrl() {
        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> new GitSkillRepository(""));
        assertTrue(exception.getMessage().contains("cannot be null or empty"));
    }

    @Test
    @DisplayName("Should create repository with branch")
    void testConstructor_WithBranch() {
        repository = new GitSkillRepository("https://github.com/test/repo.git", "develop");
        assertNotNull(repository);
    }

    // ==================== 本地 Git 仓库测试 ====================

    /**
     * 创建带测试技能的本地 Git 仓库。
     *
     * @return 所创建 Git 仓库路径
     * @throws Exception 仓库创建失败时
     */
    private Path createLocalGitRepository() throws Exception {
        Path repoDir = tempDir.resolve("test-repo");
        Files.createDirectories(repoDir);

        // 使用 JGit 初始化仓库。
        try (Git git = Git.init().setDirectory(repoDir.toFile()).call()) {
            // 创建技能目录结构。
            Path skillDir = repoDir.resolve("test-skill");
            Files.createDirectories(skillDir);

            // 创建 SKILL.md。
            String skillContent =
                    """
                    ---
                    name: test-skill
                    description: A test skill
                    ---

                    # Test Skill
                    This is a test skill.
                    """;
            Files.writeString(skillDir.resolve("SKILL.md"), skillContent);

            // 提交。
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Initial commit").call();
        }

        return repoDir;
    }

    /**
     * 创建技能位于 "skills" 子目录的本地 Git 仓库。
     *
     * @return 所创建 Git 仓库路径
     * @throws Exception 仓库创建失败时
     */
    private Path createLocalGitRepositoryWithSkillsSubdir() throws Exception {
        Path repoDir = tempDir.resolve("test-repo-with-subdir");
        Files.createDirectories(repoDir);

        // 使用 JGit 初始化仓库。
        try (Git git = Git.init().setDirectory(repoDir.toFile()).call()) {
            // 创建 skills 子目录。
            Path skillsDir = repoDir.resolve("skills");
            Files.createDirectories(skillsDir);

            // 在 skills 子目录创建测试技能。
            Path skillDir = skillsDir.resolve("subdir-skill");
            Files.createDirectories(skillDir);

            String skillContent =
                    """
                    ---
                    name: subdir-skill
                    description: A skill in skills subdirectory
                    ---

                    # Subdir Skill
                    This skill is located in the skills/ subdirectory.
                    """;
            Files.writeString(skillDir.resolve("SKILL.md"), skillContent);

            // 提交。
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Initial commit with skills subdir").call();
        }

        return repoDir;
    }

    @Test
    @DisplayName("Should load skill from local Git repository")
    void testGetSkill_FromLocalRepo() throws Exception {
        // 创建本地 Git 仓库。
        Path localRepo = createLocalGitRepository();

        // 通过 file:// 协议访问本地仓库，无需网络。
        repository = new GitSkillRepository("file://" + localRepo.toString());

        // 验证可加载技能。
        AgentSkill skill = repository.getSkill("test-skill");
        assertNotNull(skill);
        assertEquals("test-skill", skill.getName());
        assertEquals("A test skill", skill.getDescription());
    }

    @Test
    @DisplayName("Should require manual sync when autoSync is disabled")
    void testManualSyncRequiredWhenAutoSyncDisabled() throws Exception {
        Path localRepo = createLocalGitRepository();
        repository = new GitSkillRepository("file://" + localRepo.toString(), false);

        assertThrows(IllegalStateException.class, () -> repository.getSkill("test-skill"));

        repository.sync();
        AgentSkill skill = repository.getSkill("test-skill");
        assertNotNull(skill);
        assertEquals("test-skill", skill.getName());
    }

    @Test
    @DisplayName("Should load skill from skills subdirectory when it exists")
    void testGetSkill_FromSkillsSubdirectory() throws Exception {
        // 创建技能位于子目录的本地 Git 仓库。
        Path localRepo = createLocalGitRepositoryWithSkillsSubdir();

        // 访问本地仓库。
        repository = new GitSkillRepository("file://" + localRepo.toString());

        // 验证可从 skills 子目录加载技能。
        AgentSkill skill = repository.getSkill("subdir-skill");
        assertNotNull(skill);
        assertEquals("subdir-skill", skill.getName());
        assertEquals("A skill in skills subdirectory", skill.getDescription());
    }

    @Test
    @DisplayName("Should use repository root when skills subdirectory does not exist")
    void testGetSkill_FallbackToRoot() throws Exception {
        // 创建不带 skills 子目录的仓库。
        Path localRepo = createLocalGitRepository();

        // 访问本地仓库。
        repository = new GitSkillRepository("file://" + localRepo.toString());

        // 验证可从仓库根目录加载技能。
        AgentSkill skill = repository.getSkill("test-skill");
        assertNotNull(skill);
        assertEquals("test-skill", skill.getName());
    }

    @Test
    @DisplayName("Should get all skill names from local repository")
    void testGetAllSkillNames_FromLocalRepo() throws Exception {
        Path localRepo = createLocalGitRepository();
        repository = new GitSkillRepository("file://" + localRepo.toString());

        List<String> skillNames = repository.getAllSkillNames();
        assertNotNull(skillNames);
        assertEquals(1, skillNames.size());
        assertTrue(skillNames.contains("test-skill"));
    }

    @Test
    @DisplayName("Should get all skills from local repository")
    void testGetAllSkills_FromLocalRepo() throws Exception {
        Path localRepo = createLocalGitRepository();
        repository = new GitSkillRepository("file://" + localRepo.toString());

        List<AgentSkill> skills = repository.getAllSkills();
        assertNotNull(skills);
        assertEquals(1, skills.size());
        assertEquals("test-skill", skills.get(0).getName());
    }

    @Test
    @DisplayName("Should check skill existence in local repository")
    void testSkillExists_FromLocalRepo() throws Exception {
        Path localRepo = createLocalGitRepository();
        repository = new GitSkillRepository("file://" + localRepo.toString());

        assertTrue(repository.skillExists("test-skill"));
        assertFalse(repository.skillExists("non-existent-skill"));
    }

    @Test
    @DisplayName("Should pull updates from local repository")
    void testPull_FromLocalRepo() throws Exception {
        // 创建本地 Git 仓库。
        Path localRepo = createLocalGitRepository();

        // 首次访问，克隆。
        repository = new GitSkillRepository("file://" + localRepo.toString());
        List<String> skillNames1 = repository.getAllSkillNames();
        assertEquals(1, skillNames1.size());

        // 向原始仓库添加新技能。
        Path newSkillDir = localRepo.resolve("new-skill");
        Files.createDirectories(newSkillDir);
        Files.writeString(
                newSkillDir.resolve("SKILL.md"),
                """
                ---
                name: new-skill
                description: A new skill
                ---
                # New Skill
                """);

        try (Git git = Git.open(localRepo.toFile())) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Add new skill").call();
        }

        // 第二次访问，拉取。
        List<String> skillNames2 = repository.getAllSkillNames();
        assertEquals(2, skillNames2.size());
        assertTrue(skillNames2.contains("new-skill"));
    }

    // ==================== 分支测试 ====================

    @Test
    @DisplayName("Should clone specific branch")
    void testClone_SpecificBranch() throws Exception {
        // 创建含多个分支的本地仓库。
        Path localRepo = createLocalGitRepository();

        try (Git git = Git.open(localRepo.toFile())) {
            // 创建新分支。
            git.branchCreate().setName("develop").call();
            git.checkout().setName("develop").call();

            // 在 develop 分支添加新技能。
            Path devSkillDir = localRepo.resolve("dev-skill");
            Files.createDirectories(devSkillDir);
            Files.writeString(
                    devSkillDir.resolve("SKILL.md"),
                    """
                    ---
                    name: dev-skill
                    description: Dev branch skill
                    ---
                    # Dev Skill
                    """);

            git.add().addFilepattern(".").call();
            git.commit().setMessage("Add dev skill").call();
        }

        // 克隆 develop 分支。
        repository = new GitSkillRepository("file://" + localRepo.toString(), "develop");

        List<String> skillNames = repository.getAllSkillNames();
        assertTrue(skillNames.contains("dev-skill"), "Should contain skill from develop branch");
    }

    // ==================== 只读测试 ====================

    @Test
    @DisplayName("Should return false for save operation")
    void testSave_ReadOnly() throws Exception {
        Path localRepo = createLocalGitRepository();
        repository = new GitSkillRepository("file://" + localRepo.toString());

        AgentSkill skill = repository.getSkill("test-skill");
        boolean result = repository.save(List.of(skill), false);

        assertFalse(result, "Save should return false for read-only repository");
    }

    @Test
    @DisplayName("Should return false for delete operation")
    void testDelete_ReadOnly() throws Exception {
        Path localRepo = createLocalGitRepository();
        repository = new GitSkillRepository("file://" + localRepo.toString());

        boolean result = repository.delete("test-skill");

        assertFalse(result, "Delete should return false for read-only repository");
    }

    @Test
    @DisplayName("Should always return false for isWriteable")
    void testIsWriteable() throws Exception {
        Path localRepo = createLocalGitRepository();
        repository = new GitSkillRepository("file://" + localRepo.toString());

        assertFalse(repository.isWriteable());

        // 尝试设置为可写，应被忽略。
        repository.setWriteable(true);
        assertFalse(repository.isWriteable());
    }

    // ==================== 仓库信息测试 ====================

    @Test
    @DisplayName("Should return correct repository info")
    void testGetRepositoryInfo() {
        String testUrl = "https://github.com/test/repo.git";
        repository = new GitSkillRepository(testUrl);

        AgentSkillRepositoryInfo info = repository.getRepositoryInfo();
        assertNotNull(info);
        assertEquals("git", info.getType());
        assertEquals(testUrl, info.getLocation());
        assertFalse(info.isWritable());
    }

    @Test
    @DisplayName("Should return correct source identifier without Windows reserved characters")
    void testGetSource() {
        String testUrl = "https://github.com/test/repo.git";
        repository = new GitSkillRepository(testUrl);

        String source = repository.getSource();
        // 使用连字符而非冒号，避免 Windows 路径问题。
        assertEquals("git-test/repo", source);
        assertFalse(source.contains(":"), "Source should not contain Windows reserved characters");
    }

    @Test
    @DisplayName("Should return custom source identifier when provided")
    void testGetSource_Custom() {
        String testUrl = "https://github.com/test/repo.git";
        String customSource = "git:custom-source";
        repository = new GitSkillRepository(testUrl, null, null, customSource);

        String source = repository.getSource();
        assertEquals(customSource, source);
    }

    // ==================== 技能根目录测试 ====================

    @Test
    @DisplayName("Should load skills from configured skillsRoot subdirectory")
    void testGetSkill_FromCustomSkillsRoot() throws Exception {
        // 构建技能嵌套在 packs/marketing/ 下的仓库，
        // 默认约定不会找到这种布局。
        Path repoDir = tempDir.resolve("custom-root-repo");
        Files.createDirectories(repoDir);
        try (Git git = Git.init().setDirectory(repoDir.toFile()).call()) {
            Path nested = repoDir.resolve("packs").resolve("marketing").resolve("greeter");
            Files.createDirectories(nested);
            Files.writeString(
                    nested.resolve("SKILL.md"),
                    """
                    ---
                    name: greeter
                    description: A skill nested under packs/marketing
                    ---
                    # Greeter
                    """);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("nested skill").call();
        }

        repository =
                new GitSkillRepository(
                        "file://" + repoDir.toString(), null, null, null, true, "packs/marketing");

        AgentSkill skill = repository.getSkill("greeter");
        assertNotNull(skill);
        assertEquals("greeter", skill.getName());
        assertEquals("A skill nested under packs/marketing", skill.getDescription());
    }

    @Test
    @DisplayName("Should reject skillsRoot containing '..'")
    void testConstructor_RejectsParentSkillsRoot() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new GitSkillRepository(
                                "https://github.com/test/repo.git",
                                null,
                                null,
                                null,
                                true,
                                "../escape"));
    }

    // ==================== 错误场景测试 ====================

    @Test
    @DisplayName("Should throw exception when repository not found")
    void testClone_RepositoryNotFound() {
        repository =
                new GitSkillRepository(
                        "https://github.com/definitely-does-not-exist-repo-99999.git");

        RuntimeException exception =
                assertThrows(RuntimeException.class, () -> repository.getSkill("test"));

        String message = exception.getMessage();
        // 错误消息可能包含多种网络／仓库相关文本。
        // 网络错误因环境而异，因此接受任何非 null 消息。
        assertNotNull(message, "Exception message should not be null");
        // 记录实际消息用于调试。
        // CI 环境可能出现不同错误消息。
        assertTrue(
                message.contains("not found")
                        || message.contains("does not exist")
                        || message.contains("Unable to connect")
                        || message.contains("inaccessible")
                        || message.contains("Invalid remote repository address")
                        || message.contains("Authentication")
                        || message.contains("HTTP")
                        || message.contains("404")
                        || message.contains("timeout")
                        || message.toLowerCase().contains("error")
                        || message.toLowerCase().contains("failed"),
                "Error message should mention repository or network issue: " + message);
    }

    @Test
    @DisplayName("Should throw exception for invalid URL format")
    void testClone_InvalidUrl() {
        repository = new GitSkillRepository("not-a-valid-git-url");

        RuntimeException exception =
                assertThrows(RuntimeException.class, () -> repository.getSkill("test"));

        String message = exception.getMessage();
        assertNotNull(message, "Exception message should not be null");
        // 错误格式因 JGit 版本与环境而异，因此接受任何非空消息。
        assertFalse(message.isEmpty(), "Exception message should not be empty");
    }
}
