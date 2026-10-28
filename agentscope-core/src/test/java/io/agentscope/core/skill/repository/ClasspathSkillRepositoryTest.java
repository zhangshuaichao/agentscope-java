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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.spi.FileSystemProvider;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.CRC32;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ClasspathSkillRepository 单元测试。
 *
 * <p>验证适配器从以下来源加载技能的能力：
 * <ul>
 * <li>文件系统，开发环境</li>
 * <li>JAR 文件，生产环境</li>
 * </ul>
 *
 * <p>标记为 "unit"，无需外部依赖的快速测试。
 */
@Tag("unit")
@DisplayName("ClasspathSkillRepository Unit Tests")
class ClasspathSkillRepositoryTest {

    @TempDir Path tempDir;

    private ClasspathSkillRepository repository;

    @AfterEach
    void tearDown() throws IOException {
        if (repository != null) {
            repository.close();
        }
    }

    // ==================== 文件系统加载测试 ====================

    @Test
    @DisplayName("Should load single skill from file system")
    void testLoadSingleSkillFromFileSystem() throws IOException {
        repository = new ClasspathSkillRepository("test-skills");

        assertFalse(repository.isJarEnvironment(), "Should detect file system environment");

        AgentSkill skill = repository.getSkill("writing-skill");
        assertNotNull(skill);
        assertEquals("writing-skill", skill.getName());
        assertEquals("A skill for writing and content creation", skill.getDescription());
        assertTrue(skill.getSkillContent().contains("Writing Skill"));
    }

    @Test
    @DisplayName("Should load skill with nested resources from file system")
    void testLoadSkillWithResourcesFromFileSystem() throws IOException {
        repository = new ClasspathSkillRepository("test-skills");

        AgentSkill skill = repository.getSkill("writing-skill");
        assertNotNull(skill);

        // 验证已加载嵌套资源
        assertTrue(skill.getResources().containsKey("references/guide.md"));
        String guideContent = skill.getResources().get("references/guide.md");
        assertTrue(guideContent.contains("Writing Guide"));
        assertTrue(guideContent.contains("Best Practices"));
    }

    @Test
    @DisplayName("Should load all skills from file system")
    void testLoadAllSkillsFromFileSystem() throws IOException {
        repository = new ClasspathSkillRepository("test-skills");

        // 测试 getAllSkillNames()
        List<String> skillNames = repository.getAllSkillNames();
        assertNotNull(skillNames);
        assertEquals(2, skillNames.size());
        assertTrue(skillNames.contains("writing-skill"));
        assertTrue(skillNames.contains("calculation-skill"));

        // 测试 getAllSkills()
        List<AgentSkill> skills = repository.getAllSkills();
        assertNotNull(skills);
        assertEquals(2, skills.size());
        List<String> loadedNames = skills.stream().map(AgentSkill::getName).toList();
        assertTrue(loadedNames.contains("writing-skill"));
        assertTrue(loadedNames.contains("calculation-skill"));
    }

    // ==================== JAR 加载测试 ====================

    @Test
    @DisplayName("Should load single skill from JAR")
    void testLoadSingleSkillFromJar() throws Exception {
        Path jarPath = createTestJarInFolder("test-skill", "Test Skill", "Test content");

        try (URLClassLoader classLoader = new URLClassLoader(new URL[] {jarPath.toUri().toURL()})) {
            repository = new ClasspathSkillRepositoryWithClassLoader("jar-skills", classLoader);

            assertTrue(repository.isJarEnvironment(), "Should detect JAR environment");

            AgentSkill skill = repository.getSkill("test-skill");
            assertNotNull(skill);
            assertEquals("test-skill", skill.getName());
            assertEquals("Test Skill", skill.getDescription());
            assertTrue(skill.getSkillContent().contains("Test content"));
        }
    }

    @Test
    @DisplayName("Should load skill with nested resources from JAR")
    void testLoadSkillWithResourcesFromJar() throws Exception {
        Path jarPath = createTestJarWithResources();

        try (URLClassLoader classLoader = new URLClassLoader(new URL[] {jarPath.toUri().toURL()})) {
            repository = new ClasspathSkillRepositoryWithClassLoader("jar-skills", classLoader);

            assertTrue(repository.isJarEnvironment());

            AgentSkill skill = repository.getSkill("jar-skill");
            assertNotNull(skill);
            assertEquals("jar-skill", skill.getName());

            // 验证已加载嵌套资源
            assertTrue(skill.getResources().containsKey("config.json"));
            assertEquals("{\"key\": \"value\"}", skill.getResources().get("config.json"));

            assertTrue(skill.getResources().containsKey("data/sample.txt"));
            assertEquals("Sample data", skill.getResources().get("data/sample.txt"));
        }
    }

    @Test
    @DisplayName("Should load all skills from JAR")
    void testLoadAllSkillsFromJar() throws Exception {
        Path jarPath = createTestJarWithMultipleSkills();

        try (URLClassLoader classLoader = new URLClassLoader(new URL[] {jarPath.toUri().toURL()})) {
            repository = new ClasspathSkillRepositoryWithClassLoader("jar-skills", classLoader);

            assertTrue(repository.isJarEnvironment(), "Should detect JAR environment");

            // 测试 getAllSkillNames()
            List<String> skillNames = repository.getAllSkillNames();
            assertNotNull(skillNames);
            assertEquals(2, skillNames.size());
            assertTrue(skillNames.contains("skill-one"));
            assertTrue(skillNames.contains("skill-two"));

            // 测试 getAllSkills()
            List<AgentSkill> skills = repository.getAllSkills();
            assertNotNull(skills);
            assertEquals(2, skills.size());

            // 测试通过 getSkill() 获取单个技能
            AgentSkill skill1 = repository.getSkill("skill-one");
            assertNotNull(skill1);
            assertEquals("skill-one", skill1.getName());
            assertEquals("First skill", skill1.getDescription());

            AgentSkill skill2 = repository.getSkill("skill-two");
            assertNotNull(skill2);
            assertEquals("skill-two", skill2.getName());
            assertEquals("Second skill", skill2.getDescription());
        }
    }

    @Test
    @DisplayName("Should reuse JAR file system across repository instances")
    void testReuseJarFileSystemAcrossRepositories() throws Exception {
        Path jarPath = createTestJarInFolder("shared-skill", "Shared Skill", "Shared content");

        try (URLClassLoader classLoader = new URLClassLoader(new URL[] {jarPath.toUri().toURL()});
                ClasspathSkillRepository firstRepository =
                        new ClasspathSkillRepositoryWithClassLoader("jar-skills", classLoader);
                ClasspathSkillRepository secondRepository =
                        new ClasspathSkillRepositoryWithClassLoader("jar-skills", classLoader)) {

            AgentSkill firstSkill = firstRepository.getSkill("shared-skill");
            AgentSkill secondSkill = secondRepository.getSkill("shared-skill");

            assertEquals("shared-skill", firstSkill.getName());
            assertEquals("shared-skill", secondSkill.getName());

            firstRepository.close();

            AgentSkill stillAccessible = secondRepository.getSkill("shared-skill");
            assertEquals("shared-skill", stillAccessible.getName());
        }
    }

    @Test
    @DisplayName("Should reuse JAR file system across different classpath roots in same JAR")
    void testReuseJarFileSystemAcrossDifferentRootsInSameJar() throws Exception {
        Path jarPath = createTestJarWithTwoSkillRoots();

        try (URLClassLoader classLoader = new URLClassLoader(new URL[] {jarPath.toUri().toURL()});
                ClasspathSkillRepository firstRepository =
                        new ClasspathSkillRepositoryWithClassLoader("jar-skills-a", classLoader);
                ClasspathSkillRepository secondRepository =
                        new ClasspathSkillRepositoryWithClassLoader("jar-skills-b", classLoader)) {

            AgentSkill firstSkill = firstRepository.getSkill("skill-a");
            AgentSkill secondSkill = secondRepository.getSkill("skill-b");

            assertEquals("skill-a", firstSkill.getName());
            assertEquals("skill-b", secondSkill.getName());

            firstRepository.close();

            AgentSkill stillAccessible = secondRepository.getSkill("skill-b");
            assertEquals("skill-b", stillAccessible.getName());
        }
    }

    @Test
    @DisplayName("Should load skills from Spring Boot Fat JAR (BOOT-INF/classes/)")
    void testLoadFromSpringBootJar() throws Exception {
        Path jarPath = createSpringBootTestJar("sb-skill", "SB Skill", "SB content");

        // 创建模拟 Spring Boot LaunchedURLClassLoader 行为的自定义 ClassLoader
        // Spring Boot 中 classLoader.getResource("jar-skills") 自动解析为
        // "BOOT-INF/classes/jar-skills" 并返回有效 URL
        try (URLClassLoader baseClassLoader =
                new URLClassLoader(new URL[] {jarPath.toUri().toURL()})) {
            ClassLoader springBootClassLoader =
                    new ClassLoader(baseClassLoader) {
                        @Override
                        public URL getResource(String name) {
                            // 模拟 Spring Boot 行为：自动添加前缀
                            // BOOT-INF/classes/
                            URL resource = super.getResource("BOOT-INF/classes/" + name);
                            if (resource != null) {
                                return resource;
                            }
                            // 回退到原始行为
                            return super.getResource(name);
                        }
                    };

            repository =
                    new ClasspathSkillRepositoryWithClassLoader(
                            "jar-skills", springBootClassLoader);

            assertTrue(repository.isJarEnvironment(), "Should detect JAR environment");

            AgentSkill skill = repository.getSkill("sb-skill");
            assertNotNull(skill);
            assertEquals("sb-skill", skill.getName());
            assertEquals("SB Skill", skill.getDescription());
            assertTrue(skill.getSkillContent().contains("SB content"));
        }
    }

    @Test
    @DisplayName("Should load skills from Spring Boot nested lib JAR (BOOT-INF/lib/)")
    void testLoadFromSpringBootNestedLibJar() throws Exception {
        // 模拟 Spring Boot 3.2+ 多模块项目使用的 URL 模式：
        // jar:nested:/opt/app/nested-springboot.jar/!BOOT-INF/lib/nested-skill.jar!/jar-skills
        Path outerJarPath =
                createSpringBootNestedLibTestJar(
                        "nested-lib-skill", "Nested Lib Skill", "Nested lib content");

        // 将内部 JAR 从外部 JAR 提取到临时文件，
        // 模拟 Spring Boot 运行时解析嵌套 JAR
        Path innerJarPath = extractInnerJar(outerJarPath, "BOOT-INF/lib/nested-skill.jar");

        // 配置测试 FileSystemProvider，使 ZipFileSystemProvider 能
        // 将 nested: URI 解析为提取出的内部 JAR 路径。
        // 生产中由 Spring Boot 的 NestedFileSystemProvider 处理。
        TestNestedFileSystemProvider.configuredInnerJarPath = innerJarPath;
        try {
            // 构建返回 jar:nested: 格式 URL 的 ClassLoader。
            ClassLoader nestedClassLoader =
                    new ClassLoader(ClassLoader.getSystemClassLoader()) {
                        @Override
                        public URL getResource(String name) {
                            if ("jar-skills".equals(name)) {
                                try {
                                    String nestedUrlStr =
                                            "jar:nested:"
                                                    + outerJarPath.toUri().getRawPath()
                                                    + "/!BOOT-INF/lib/nested-skill.jar!/"
                                                    + name;
                                    return new URL(
                                            null,
                                            nestedUrlStr,
                                            new URLStreamHandler() {
                                                @Override
                                                protected URLConnection openConnection(URL u)
                                                        throws IOException {
                                                    throw new UnsupportedOperationException(
                                                            "nested URL for test only");
                                                }
                                            });
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }
                            return super.getResource(name);
                        }
                    };

            repository =
                    new ClasspathSkillRepositoryWithClassLoader("jar-skills", nestedClassLoader);

            assertTrue(repository.isJarEnvironment(), "Should detect JAR environment");

            AgentSkill skill = repository.getSkill("nested-lib-skill");
            assertNotNull(skill);
            assertEquals("nested-lib-skill", skill.getName());
            assertEquals("Nested Lib Skill", skill.getDescription());
            assertTrue(skill.getSkillContent().contains("Nested lib content"));
        } finally {
            TestNestedFileSystemProvider.configuredInnerJarPath = null;
        }
    }

    @Test
    @DisplayName("Should reuse nested JAR file system across repository instances")
    void testReuseNestedJarFileSystemAcrossRepositories() throws Exception {
        Path outerJarPath =
                createSpringBootNestedLibTestJar(
                        "nested-shared-skill", "Nested Shared Skill", "Nested shared content");
        Path innerJarPath = extractInnerJar(outerJarPath, "BOOT-INF/lib/nested-skill.jar");

        TestNestedFileSystemProvider.configuredInnerJarPath = innerJarPath;
        try (ClasspathSkillRepository firstRepository =
                        new ClasspathSkillRepositoryWithClassLoader(
                                "jar-skills", createNestedJarClassLoader(outerJarPath));
                ClasspathSkillRepository secondRepository =
                        new ClasspathSkillRepositoryWithClassLoader(
                                "jar-skills", createNestedJarClassLoader(outerJarPath))) {

            AgentSkill firstSkill = firstRepository.getSkill("nested-shared-skill");
            AgentSkill secondSkill = secondRepository.getSkill("nested-shared-skill");

            assertEquals("nested-shared-skill", firstSkill.getName());
            assertEquals("nested-shared-skill", secondSkill.getName());

            firstRepository.close();

            AgentSkill stillAccessible = secondRepository.getSkill("nested-shared-skill");
            assertEquals("nested-shared-skill", stillAccessible.getName());
        } finally {
            TestNestedFileSystemProvider.configuredInnerJarPath = null;
        }
    }

    // ==================== getSource 测试 ====================

    @Test
    @DisplayName("Should return default source with format: classpath-path")
    void testGetSource_DefaultSource() throws IOException {
        repository = new ClasspathSkillRepository("test-skills");
        assertEquals("classpath-test-skills", repository.getSource());
    }

    @Test
    @DisplayName("Should return custom source when provided")
    void testGetSource_CustomSource() throws IOException {
        repository = new ClasspathSkillRepository("test-skills", "my-custom-source");
        assertEquals("my-custom-source", repository.getSource());
    }

    @Test
    @DisplayName("Should handle trailing slash in path")
    void testGetSource_TrailingSlash() throws IOException {
        repository = new ClasspathSkillRepository("test-skills/");
        assertEquals("classpath-test-skills", repository.getSource());
    }

    // ==================== 错误处理测试 ====================

    @Test
    @DisplayName("Should throw IOException when resource not found")
    void testResourceNotFound() {
        assertThrows(
                IOException.class,
                () -> new ClasspathSkillRepository("non-existent-skill"),
                "Should throw IOException for non-existent resource");
    }

    @Test
    @DisplayName("Should throw exception when skill directory not found")
    void testSkillDirectoryNotFound() throws IOException {
        repository = new ClasspathSkillRepository("test-skills");

        assertThrows(
                IllegalArgumentException.class,
                () -> repository.getSkill("non-existent"),
                "Should throw exception when skill directory doesn't exist");
    }

    // ==================== 生命周期管理测试 ====================

    @Test
    @DisplayName("Should correctly detect environment type")
    void testEnvironmentDetection() throws Exception {
        // 文件系统环境
        try (ClasspathSkillRepository fsRepository = new ClasspathSkillRepository("test-skills")) {
            assertFalse(fsRepository.isJarEnvironment());
        }

        // JAR 环境
        Path jarPath = createTestJarInFolder("env-test", "Env Test", "Content");
        try (URLClassLoader classLoader = new URLClassLoader(new URL[] {jarPath.toUri().toURL()})) {
            try (ClasspathSkillRepository jarRepository =
                    new ClasspathSkillRepositoryWithClassLoader("jar-skills", classLoader)) {
                assertTrue(jarRepository.isJarEnvironment());
            }
        }
    }

    @Test
    @DisplayName("Should handle close properly for both environments")
    void testCloseHandling() throws Exception {
        // 测试关闭文件系统适配器
        repository = new ClasspathSkillRepository("test-skills");
        assertFalse(repository.isJarEnvironment());
        repository.close();
        repository.close(); // 幂等关闭

        // 测试关闭 JAR 适配器
        Path jarPath = createTestJarInFolder("closeable-skill", "Closeable", "Content");
        try (URLClassLoader classLoader = new URLClassLoader(new URL[] {jarPath.toUri().toURL()})) {
            repository = new ClasspathSkillRepositoryWithClassLoader("jar-skills", classLoader);
            assertTrue(repository.isJarEnvironment());

            repository.getSkill("closeable-skill"); // 加载技能以确保创建文件系统
            repository.close();
            repository.close(); // 幂等关闭
        }
    }

    @Test
    @DisplayName("Should throw exception when using closed adapter")
    void testOperationsAfterClose() throws Exception {
        repository = new ClasspathSkillRepository("test-skills");

        // 关闭适配器
        repository.close();

        // 全部操作都应抛 IllegalStateException
        assertThrows(
                IllegalStateException.class,
                () -> repository.getSkill("writing-skill"),
                "Should throw exception when getting skill after close");

        assertThrows(
                IllegalStateException.class,
                () -> repository.getAllSkillNames(),
                "Should throw exception when getting all skill names after close");

        assertThrows(
                IllegalStateException.class,
                () -> repository.getAllSkills(),
                "Should throw exception when getting all skills after close");
    }

    // ==================== 辅助方法 ====================

    /**
     * 创建测试 JAR，在 jar-skills 父目录中包含单个技能。
     */
    private Path createTestJarInFolder(String skillName, String description, String content)
            throws IOException {
        Path jarPath = tempDir.resolve(skillName + "-folder.jar");

        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            // 添加父目录
            jos.putNextEntry(new JarEntry("jar-skills/"));
            jos.closeEntry();

            // 添加技能目录
            jos.putNextEntry(new JarEntry("jar-skills/" + skillName + "/"));
            jos.closeEntry();

            // 添加 SKILL.md
            String skillMd =
                    "---\n"
                            + "name: "
                            + skillName
                            + "\n"
                            + "description: "
                            + description
                            + "\n"
                            + "---\n"
                            + content;

            JarEntry entry = new JarEntry("jar-skills/" + skillName + "/SKILL.md");
            jos.putNextEntry(entry);
            jos.write(skillMd.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        return jarPath;
    }

    /**
     * 创建包含技能及多个资源的测试 JAR。
     */
    private Path createTestJarWithResources() throws IOException {
        Path jarPath = tempDir.resolve("skill-with-resources.jar");

        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            // 添加父目录
            jos.putNextEntry(new JarEntry("jar-skills/"));
            jos.closeEntry();

            // 添加技能目录
            jos.putNextEntry(new JarEntry("jar-skills/jar-skill/"));
            jos.closeEntry();

            // 添加 SKILL.md
            String skillMd =
                    "---\n"
                            + "name: jar-skill\n"
                            + "description: Skill with resources\n"
                            + "---\n"
                            + "Main content";

            JarEntry skillEntry = new JarEntry("jar-skills/jar-skill/SKILL.md");
            jos.putNextEntry(skillEntry);
            jos.write(skillMd.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();

            // 添加 config.json
            JarEntry configEntry = new JarEntry("jar-skills/jar-skill/config.json");
            jos.putNextEntry(configEntry);
            jos.write("{\"key\": \"value\"}".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();

            // 添加嵌套资源目录与文件
            jos.putNextEntry(new JarEntry("jar-skills/jar-skill/data/"));
            jos.closeEntry();

            JarEntry dataEntry = new JarEntry("jar-skills/jar-skill/data/sample.txt");
            jos.putNextEntry(dataEntry);
            jos.write("Sample data".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        return jarPath;
    }

    /**
     * 创建包含多个技能的测试 JAR。
     */
    private Path createTestJarWithMultipleSkills() throws IOException {
        Path jarPath = tempDir.resolve("multiple-skills.jar");

        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            // 添加父目录
            jos.putNextEntry(new JarEntry("jar-skills/"));
            jos.closeEntry();

            // 添加第一个技能目录
            jos.putNextEntry(new JarEntry("jar-skills/skill-one/"));
            jos.closeEntry();

            // 添加第一个技能
            String skill1Md =
                    "---\n"
                            + "name: skill-one\n"
                            + "description: First skill\n"
                            + "---\n"
                            + "Content one";
            JarEntry entry1 = new JarEntry("jar-skills/skill-one/SKILL.md");
            jos.putNextEntry(entry1);
            jos.write(skill1Md.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();

            // 添加第二个技能目录
            jos.putNextEntry(new JarEntry("jar-skills/skill-two/"));
            jos.closeEntry();

            // 添加第二个技能
            String skill2Md =
                    "---\n"
                            + "name: skill-two\n"
                            + "description: Second skill\n"
                            + "---\n"
                            + "Content two";
            JarEntry entry2 = new JarEntry("jar-skills/skill-two/SKILL.md");
            jos.putNextEntry(entry2);
            jos.write(skill2Md.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        return jarPath;
    }

    private Path createTestJarWithTwoSkillRoots() throws IOException {
        Path jarPath = tempDir.resolve("two-skill-roots.jar");

        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            jos.putNextEntry(new JarEntry("jar-skills-a/"));
            jos.closeEntry();
            jos.putNextEntry(new JarEntry("jar-skills-a/skill-a/"));
            jos.closeEntry();
            jos.putNextEntry(new JarEntry("jar-skills-a/skill-a/SKILL.md"));
            jos.write(
                    "---\nname: skill-a\ndescription: Skill A\n---\nContent A"
                            .getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();

            jos.putNextEntry(new JarEntry("jar-skills-b/"));
            jos.closeEntry();
            jos.putNextEntry(new JarEntry("jar-skills-b/skill-b/"));
            jos.closeEntry();
            jos.putNextEntry(new JarEntry("jar-skills-b/skill-b/SKILL.md"));
            jos.write(
                    "---\nname: skill-b\ndescription: Skill B\n---\nContent B"
                            .getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        return jarPath;
    }

    /**
     * 创建具有 Spring Boot 结构（BOOT-INF/classes/）的测试 JAR。
     */
    private Path createSpringBootTestJar(String skillName, String description, String content)
            throws IOException {
        Path jarPath = tempDir.resolve(skillName + "-springboot.jar");

        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            // 添加 Spring Boot 结构
            jos.putNextEntry(new JarEntry("BOOT-INF/"));
            jos.closeEntry();
            jos.putNextEntry(new JarEntry("BOOT-INF/classes/"));
            jos.closeEntry();
            jos.putNextEntry(new JarEntry("BOOT-INF/classes/jar-skills/"));
            jos.closeEntry();

            // 添加技能目录
            jos.putNextEntry(new JarEntry("BOOT-INF/classes/jar-skills/" + skillName + "/"));
            jos.closeEntry();

            // 添加 SKILL.md
            String skillMd =
                    "---\n"
                            + "name: "
                            + skillName
                            + "\n"
                            + "description: "
                            + description
                            + "\n"
                            + "---\n"
                            + content;

            JarEntry entry = new JarEntry("BOOT-INF/classes/jar-skills/" + skillName + "/SKILL.md");
            jos.putNextEntry(entry);
            jos.write(skillMd.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        return jarPath;
    }

    /**
     * 创建模拟 Spring Boot 多模块项目的测试 fat JAR。外部 JAR 包含 BOOT-INF/lib/nested-skill.jar，内部再包含 jar-skills/{skillName}/SKILL.md。
     *
     * <p>模拟 URL 模式：
     * jar:nested:/opt/app/xxx-app.jar/!BOOT-INF/lib/nested-skill.jar!/jar-skills
     */
    private Path createSpringBootNestedLibTestJar(
            String skillName, String description, String content) throws IOException {
        // 先创建内部 JAR，即库模块 JAR
        byte[] innerJarBytes = createInnerSkillJar(skillName, description, content);

        // 再创建外部 fat JAR，将内部 JAR 放在 BOOT-INF/lib/ 下
        Path outerJarPath = tempDir.resolve(skillName + "-nested-springboot.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(outerJarPath))) {
            // 添加 Spring Boot 目录结构
            jos.putNextEntry(new JarEntry("BOOT-INF/"));
            jos.closeEntry();
            jos.putNextEntry(new JarEntry("BOOT-INF/lib/"));
            jos.closeEntry();

            // 将内部 JAR 作为嵌套条目嵌入，使用 STORED、不压缩
            JarEntry nestedJarEntry = new JarEntry("BOOT-INF/lib/nested-skill.jar");
            nestedJarEntry.setMethod(JarEntry.STORED);
            nestedJarEntry.setSize(innerJarBytes.length);
            nestedJarEntry.setCompressedSize(innerJarBytes.length);
            CRC32 crc = new CRC32();
            crc.update(innerJarBytes);
            nestedJarEntry.setCrc(crc.getValue());
            jos.putNextEntry(nestedJarEntry);
            jos.write(innerJarBytes);
            jos.closeEntry();
        }

        return outerJarPath;
    }

    /**
     * 创建在 jar-skills/{skillName}/SKILL.md 含技能的内部 JAR 字节数组。
     */
    private byte[] createInnerSkillJar(String skillName, String description, String content)
            throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(baos)) {
            // 添加父目录
            jos.putNextEntry(new JarEntry("jar-skills/"));
            jos.closeEntry();

            // 添加技能目录
            jos.putNextEntry(new JarEntry("jar-skills/" + skillName + "/"));
            jos.closeEntry();

            // 添加 SKILL.md
            String skillMd =
                    "---\n"
                            + "name: "
                            + skillName
                            + "\n"
                            + "description: "
                            + description
                            + "\n"
                            + "---\n"
                            + content;

            JarEntry entry = new JarEntry("jar-skills/" + skillName + "/SKILL.md");
            jos.putNextEntry(entry);
            jos.write(skillMd.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
        return baos.toByteArray();
    }

    /**
     * 将内部 JAR 从外部 JAR 提取到临时文件，模拟 Spring Boot 运行时解析嵌套 JAR。
     */
    private Path extractInnerJar(Path outerJarPath, String innerEntryName) throws IOException {
        Path innerJarPath = tempDir.resolve("extracted-inner.jar");
        try (JarFile outerJar = new JarFile(outerJarPath.toFile())) {
            JarEntry innerEntry = outerJar.getJarEntry(innerEntryName);
            assertNotNull(innerEntry, "Inner JAR entry should exist: " + innerEntryName);
            try (InputStream is = outerJar.getInputStream(innerEntry)) {
                Files.copy(is, innerJarPath);
            }
        }
        return innerJarPath;
    }

    private ClassLoader createNestedJarClassLoader(Path outerJarPath) {
        return new ClassLoader(ClassLoader.getSystemClassLoader()) {
            @Override
            public URL getResource(String name) {
                if ("jar-skills".equals(name)) {
                    try {
                        String nestedUrlStr =
                                "jar:nested:"
                                        + outerJarPath.toUri().getRawPath()
                                        + "/!BOOT-INF/lib/nested-skill.jar!/"
                                        + name;
                        return new URL(
                                null,
                                nestedUrlStr,
                                new URLStreamHandler() {
                                    @Override
                                    protected URLConnection openConnection(URL u)
                                            throws IOException {
                                        throw new UnsupportedOperationException(
                                                "nested URL for test only");
                                    }
                                });
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
                return super.getResource(name);
            }
        };
    }

    /**
     * 使用指定 ClassLoader 测试 JAR 加载的自定义适配器。
     */
    private static class ClasspathSkillRepositoryWithClassLoader extends ClasspathSkillRepository {

        public ClasspathSkillRepositoryWithClassLoader(String resourcePath, ClassLoader classLoader)
                throws IOException {
            super(resourcePath, classLoader);
        }
    }

    /**
     * 仅供测试的 {@code nested:} 协议 {@link FileSystemProvider}。
     *
     * <p>Spring Boot 3.2+ 中，{@code nested:} 协议由 {@code spring-boot-loader} 的 {@code NestedFileSystemProvider} 处理。测试环境不含 Spring Boot，此提供者模拟相同行为。
     *
     * <p>{@link ClasspathSkillRepository} 处理 {@code jar:nested:} URI 时，JDK 的 {@code ZipFileSystemProvider} 在内部调用 {@code Path.of(new URI("nested:..."))} 定位 JAR。此提供者拦截调用并返回提取出的内部 JAR 路径。
     *
     * <p>通过 {@code META-INF/services/java.nio.file.spi.FileSystemProvider} 的 SPI 注册。
     */
    public static class TestNestedFileSystemProvider extends FileSystemProvider {

        /**
         * 提取出的内部 JAR 路径。使用 {@code jar:nested:} URL 创建 {@link ClasspathSkillRepository} 前必须设置。
         */
        static volatile Path configuredInnerJarPath;

        @Override
        public String getScheme() {
            return "nested";
        }

        /**
         * 为给定 {@code nested:} URI 返回配置的内部 JAR 路径。
         *
         * <p>{@code ZipFileSystemProvider.uriToPath()} 遇到 {@code nested:/path/outer.jar/!BOOT-INF/lib/inner.jar} 等 {@code nested:} URI 时调用。
         */
        @Override
        public Path getPath(URI uri) {
            if (configuredInnerJarPath == null) {
                throw new IllegalStateException(
                        "TestNestedFileSystemProvider.configuredInnerJarPath not set");
            }
            return configuredInnerJarPath;
        }

        // ---- 下方方法均不使用，仅为满足抽象契约 ----

        @Override
        public FileSystem newFileSystem(URI uri, Map<String, ?> env) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileSystem getFileSystem(URI uri) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SeekableByteChannel newByteChannel(
                Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DirectoryStream<Path> newDirectoryStream(
                Path dir, DirectoryStream.Filter<? super Path> filter) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void createDirectory(Path dir, FileAttribute<?>... attrs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(Path path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void copy(Path source, Path target, CopyOption... options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void move(Path source, Path target, CopyOption... options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isSameFile(Path path, Path path2) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isHidden(Path path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileStore getFileStore(Path path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void checkAccess(Path path, AccessMode... modes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <V extends FileAttributeView> V getFileAttributeView(
                Path path, Class<V> type, LinkOption... options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <A extends BasicFileAttributes> A readAttributes(
                Path path, Class<A> type, LinkOption... options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, Object> readAttributes(
                Path path, String attributes, LinkOption... options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setAttribute(Path path, String attribute, Object value, LinkOption... options) {
            throw new UnsupportedOperationException();
        }
    }
}
