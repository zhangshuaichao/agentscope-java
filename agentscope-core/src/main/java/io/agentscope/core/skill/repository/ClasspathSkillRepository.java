/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.skill.repository;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.util.SkillFileSystemHelper;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ClasspathSkillRepository：从类路径资源和 JAR 文件加载技能。
 *
 * <p>此仓库通过以下步骤，衔接 JAR 资源与基于文件系统的技能加载：
 * <ol>
 *   <li>为 JAR 内资源创建虚拟文件系统</li>
 *   <li>获取技能目录的 Path 对象</li>
 *   <li>将技能 I/O 委派给 {@link SkillFileSystemHelper}</li>
 * </ol>
 *
 * <p><b>重要：</b>技能必须组织在父目录下。应传入父目录名，而非单个技能路径，
 * 因为仓库会扫描该目录下的多个技能子目录。
 *
 * <p><b>目录结构：</b>
 * <pre>
 * resources/
 * └── skills/              ← Pass "skills" to repository
 *     ├── skill-a/
 *     │   └── SKILL.md
 *     ├── skill-b/
 *     │   └── SKILL.md
 *     └── skill-c/
 *         └── SKILL.md
 * </pre>
 *
 * <p><b>使用示例：</b>
 * <pre>{@code
 * // Load from parent directory containing multiple skills
 * try (ClasspathSkillRepository repository = new ClasspathSkillRepository("skills")) {
 *     // Get all available skill names
 *     List<String> skillNames = repository.getAllSkillNames();
 *
 *     // Load a specific skill
 *     AgentSkill skillA = repository.getSkill("skill-a");
 *
 *     // Load all skills at once
 *     List<AgentSkill> allSkills = repository.getAllSkills();
 * }
 * }</pre>
 */
public class ClasspathSkillRepository implements AgentSkillRepository {

    private static final Object FILE_SYSTEM_MONITOR = new Object();

    private static final Map<URI, SharedFileSystemRef> SHARED_FILE_SYSTEMS = new HashMap<>();

    private final Logger logger = LoggerFactory.getLogger(ClasspathSkillRepository.class);

    private final FileSystem fileSystem;
    private final Path skillBasePath;
    private final boolean isJar;
    private final URI jarFileSystemUri;
    private final String source;
    private final String resourcePath;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * 创建从资源加载技能的仓库。
     *
     * @param resourcePath resources 下技能的路径，例如 "writing-skills"
     * @throws IOException 初始化失败时抛出
     */
    public ClasspathSkillRepository(String resourcePath) throws IOException {
        this(resourcePath, null, ClasspathSkillRepository.class.getClassLoader());
    }

    /**
     * 创建从资源加载技能、并带自定义来源的仓库。
     *
     * @param resourcePath resources 下技能的路径，例如 "writing-skills"
     * @param source 自定义来源标识符（null 表示使用默认值）
     * @throws IOException 初始化失败时抛出
     */
    public ClasspathSkillRepository(String resourcePath, String source) throws IOException {
        this(resourcePath, source, ClasspathSkillRepository.class.getClassLoader());
    }

    /**
     * 创建使用指定 ClassLoader 从资源加载技能的仓库。
     *
     * @param resourcePath resources 下技能的路径，例如 "writing-skills"
     * @param classLoader 用于加载资源的 ClassLoader
     * @throws IOException 初始化失败时抛出
     */
    protected ClasspathSkillRepository(String resourcePath, ClassLoader classLoader)
            throws IOException {
        this(resourcePath, null, classLoader);
    }

    /**
     * 创建使用指定 ClassLoader 从资源加载技能的仓库。
     *
     * @param resourcePath resources 下技能的路径，例如 "writing-skills"
     * @param source 自定义来源标识符（null 表示使用默认值）
     * @param classLoader 用于加载资源的 ClassLoader
     * @throws IOException 初始化失败时抛出
     */
    protected ClasspathSkillRepository(String resourcePath, String source, ClassLoader classLoader)
            throws IOException {
        try {
            this.resourcePath = resourcePath;
            URL resourceUrl = classLoader.getResource(resourcePath);
            logger.info("Resource URL: {}", resourceUrl);

            if (resourceUrl == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }

            URI uri = resourceUrl.toURI();

            if ("jar".equals(uri.getScheme())) {
                this.isJar = true;
                this.jarFileSystemUri = toFileSystemUri(uri);
                this.fileSystem = acquireFileSystem(jarFileSystemUri);
                String schemeSpecificUriPath = uri.getSchemeSpecificPart();
                String actualResourcePath =
                        schemeSpecificUriPath.substring(schemeSpecificUriPath.lastIndexOf("!") + 1);
                logger.info("Actual resource path: {}", actualResourcePath);
                this.skillBasePath = fileSystem.getPath(actualResourcePath);
            } else {
                this.isJar = false;
                this.fileSystem = null;
                this.jarFileSystemUri = null;
                this.skillBasePath = Path.of(uri);
            }
            logger.info("is in Jar environment: {}", this.isJar);
            this.source = source != null ? source : buildDefaultSource(resourcePath);

        } catch (URISyntaxException e) {
            throw new IOException("Invalid resource URI", e);
        }
    }

    /**
     * 按名称获取技能。
     *
     * @param skillName 技能名称（来自 SKILL.md 元数据）
     * @return 已加载的 AgentSkill 对象
     * @throws IllegalStateException 仓库已关闭时抛出
     */
    @Override
    public AgentSkill getSkill(String skillName) {
        checkNotClosed();
        return SkillFileSystemHelper.loadSkill(skillBasePath, skillName, source);
    }

    /**
     * 获取仓库中全部可用技能名称。
     *
     * @return 排序后的技能名称列表
     * @throws IllegalStateException 仓库已关闭时抛出
     */
    @Override
    public List<String> getAllSkillNames() {
        checkNotClosed();
        return SkillFileSystemHelper.getAllSkillNames(skillBasePath);
    }

    /**
     * 获取仓库中全部可用技能。
     *
     * @return 已加载的全部 AgentSkill 对象列表
     * @throws IllegalStateException 仓库已关闭时抛出
     */
    @Override
    public List<AgentSkill> getAllSkills() {
        checkNotClosed();
        return SkillFileSystemHelper.getAllSkills(skillBasePath, source);
    }

    @Override
    public boolean save(List<AgentSkill> skills, boolean force) {
        logger.warn("ClasspathSkillRepository is read-only, save operation ignored");
        return false;
    }

    @Override
    public boolean delete(String skillName) {
        logger.warn("ClasspathSkillRepository is read-only, delete operation ignored");
        return false;
    }

    @Override
    public boolean skillExists(String skillName) {
        checkNotClosed();
        return SkillFileSystemHelper.skillExists(skillBasePath, skillName);
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo("classpath", resourcePath, false);
    }

    @Override
    public String getSource() {
        return source;
    }

    private String buildDefaultSource(String resourcePath) {
        String normalized = resourcePath == null ? "" : resourcePath.replace('\\', '/');
        String trimmed =
                normalized.endsWith("/")
                        ? normalized.substring(0, normalized.length() - 1)
                        : normalized;

        if (trimmed.isEmpty()) {
            return "classpath-unknown";
        }

        int lastSlash = trimmed.lastIndexOf('/');
        if (lastSlash < 0) {
            return "classpath-" + trimmed;
        }

        int secondLastSlash = trimmed.lastIndexOf('/', lastSlash - 1);
        if (secondLastSlash < 0) {
            return "classpath-" + trimmed.substring(lastSlash + 1);
        }

        return "classpath-" + trimmed.substring(secondLastSlash + 1);
    }

    @Override
    public void setWriteable(boolean writeable) {
        logger.warn("ClasspathSkillRepository is read-only, set writeable operation ignored");
    }

    @Override
    public boolean isWriteable() {
        return false;
    }

    /**
     * 检查是否在 JAR 环境运行。
     *
     * @return 从 JAR 运行时为 true，开发环境中为 false
     */
    public boolean isJarEnvironment() {
        return isJar;
    }

    private static URI toFileSystemUri(URI resourceUri) {
        String schemeSpecificPart = resourceUri.getSchemeSpecificPart();
        int lastBang = schemeSpecificPart.lastIndexOf('!');
        if (lastBang < 0) {
            return resourceUri;
        }
        return URI.create(
                resourceUri.getScheme() + ":" + schemeSpecificPart.substring(0, lastBang));
    }

    private static FileSystem acquireFileSystem(URI uri) throws IOException {
        synchronized (FILE_SYSTEM_MONITOR) {
            SharedFileSystemRef existingRef = SHARED_FILE_SYSTEMS.get(uri);
            if (existingRef != null && existingRef.fileSystem.isOpen()) {
                existingRef.refCount++;
                return existingRef.fileSystem;
            }
            SHARED_FILE_SYSTEMS.remove(uri);

            FileSystem fileSystem;
            boolean closeWhenUnused = false;
            try {
                fileSystem = FileSystems.getFileSystem(uri);
            } catch (FileSystemNotFoundException ignored) {
                fileSystem = FileSystems.newFileSystem(uri, Collections.emptyMap());
                closeWhenUnused = true;
            }

            SHARED_FILE_SYSTEMS.put(uri, new SharedFileSystemRef(fileSystem, closeWhenUnused));
            return fileSystem;
        }
    }

    private static void releaseFileSystem(URI uri) throws IOException {
        synchronized (FILE_SYSTEM_MONITOR) {
            SharedFileSystemRef ref = SHARED_FILE_SYSTEMS.get(uri);
            if (ref == null) {
                return;
            }

            ref.refCount--;
            if (ref.refCount > 0) {
                return;
            }

            SHARED_FILE_SYSTEMS.remove(uri);
            if (ref.closeWhenUnused && ref.fileSystem.isOpen()) {
                ref.fileSystem.close();
            }
        }
    }

    private void checkNotClosed() {
        if (closed.get()) {
            throw new IllegalStateException("ClasspathSkillRepository has been closed");
        }
    }

    /**
     * 关闭文件系统（如果在 JAR 环境中）。
     *
     * <p>此方法是幂等的，可以安全地重复调用，适合与 try-with-resources 一起使用。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (jarFileSystemUri != null) {
            try {
                releaseFileSystem(jarFileSystemUri);
            } catch (IOException e) {
                throw new RuntimeException("Failed to close classpath file system", e);
            }
        }
    }

    private static final class SharedFileSystemRef {

        private final FileSystem fileSystem;
        private final boolean closeWhenUnused;
        private int refCount = 1;

        private SharedFileSystemRef(FileSystem fileSystem, boolean closeWhenUnused) {
            this.fileSystem = fileSystem;
            this.closeWhenUnused = closeWhenUnused;
        }
    }
}
