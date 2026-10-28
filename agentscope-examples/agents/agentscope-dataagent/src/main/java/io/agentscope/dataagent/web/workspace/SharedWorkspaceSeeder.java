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
package io.agentscope.dataagent.web.workspace;

import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

/** 应用首次启动时，将随附的 {@code classpath:shared/**} 树（默认技能、子智能体、记忆片段）落盘到 {@code ${cwd}/shared/}。<p>采用<strong>缺失才写入</strong>语义：{@link io.agentscope.dataagent.web.marketplace.MarketContributionService} 已写入的管理员批准贡献不会被重启覆盖，运维可在部署间手动编辑磁盘副本。若要强制刷新随附文件，需先删除磁盘文件再重启进程。<p>种子目标 {@code ${cwd}/shared/} 与 {@link UserSandboxRegistry} 投影到新容器的只读工作区路径、{@code LocalApprovalMarketplace} 列举/获取贡献技能的路径一致。因此无需逐租户复制，即可让所有租户使用 {@code sql-analysis} / {@code chart-rendering} 技能及 {@code data-explorer} / {@code report-writer} 子智能体。 */
@Component
public class SharedWorkspaceSeeder {

    private static final Logger log = LoggerFactory.getLogger(SharedWorkspaceSeeder.class);
    private static final String CLASSPATH_PREFIX = "shared/";

    private final DataAgentBootstrap bootstrap;
    private final ResourcePatternResolver resolver;

    public SharedWorkspaceSeeder(DataAgentBootstrap bootstrap) {
        this.bootstrap = bootstrap;
        this.resolver = new PathMatchingResourcePatternResolver();
    }

    @PostConstruct
    void seed() {
        Path target = bootstrap.cwd().resolve("shared").normalize();
        try {
            Files.createDirectories(target);
        } catch (IOException e) {
            log.warn("SharedWorkspaceSeeder: failed to create {}: {}", target, e.getMessage());
            return;
        }

        int copied = 0;
        int skipped = 0;
        try {
            List<LoadedFile> files = loadClasspathFiles();
            for (LoadedFile lf : files) {
                Path dest = target.resolve(lf.relPath());
                if (Files.exists(dest)) {
                    skipped++;
                    continue;
                }
                Path parent = dest.getParent();
                if (parent != null) Files.createDirectories(parent);
                writeAtomic(dest, lf.content());
                copied++;
            }
        } catch (IOException e) {
            log.warn("SharedWorkspaceSeeder: scan/copy failed: {}", e.getMessage());
        }
        log.info(
                "SharedWorkspaceSeeder: target={}, copied={}, kept-existing={}",
                target,
                copied,
                skipped);
    }

    /** 立即枚举 {@code classpath*:shared/**} 下所有普通文件，无论类路径元素是开发环境展开目录还是生产 fat-jar。返回相对于 {@code shared/} 的路径。 */
    private List<LoadedFile> loadClasspathFiles() throws IOException {
        Resource[] resources = resolver.getResources("classpath*:" + CLASSPATH_PREFIX + "**/*");
        java.util.List<LoadedFile> out = new java.util.ArrayList<>();
        for (Resource r : resources) {
            if (!r.isReadable()) continue;
            URI uri;
            try {
                uri = r.getURI();
            } catch (IOException e) {
                continue;
            }
            String scheme = uri.getScheme();
            String rel = extractRelative(uri.toString());
            if (rel == null || rel.isEmpty() || rel.endsWith("/")) continue;

            if ("file".equals(scheme)) {
                Path p = Paths.get(uri);
                if (Files.isDirectory(p)) continue;
                out.add(new LoadedFile(rel, Files.readAllBytes(p)));
            } else if ("jar".equals(scheme)) {
                String s = uri.toString();
                int bang = s.indexOf("!/");
                if (bang < 0) continue;
                URI jarFile = URI.create(s.substring(0, bang + 2));
                String inJarPath = s.substring(bang + 2);
                try (FileSystem fs = openOrCreateJarFs(jarFile)) {
                    Path p = fs.getPath(inJarPath);
                    if (!Files.isRegularFile(p)) continue;
                    out.add(new LoadedFile(rel, Files.readAllBytes(p)));
                }
            } else {
                try (InputStream in = r.getInputStream()) {
                    out.add(new LoadedFile(rel, in.readAllBytes()));
                }
            }
        }

        // 某些类路径根（尤其 Spring Boot devtools 布局）不会通过 **/* glob 暴露
        // 中间目录；以上逐文件遍历才是权威来源。
        // 兜底过滤非 shared/ 前缀的内容。
        out.removeIf(lf -> lf.relPath().isEmpty());
        return out;
    }

    /** 从类路径资源 URI 中提取 {@code /shared/} 后的路径部分。URI 不包含预期前缀时返回 {@code null}。 */
    private static String extractRelative(String uriString) {
        int idx = uriString.indexOf("/" + CLASSPATH_PREFIX);
        if (idx < 0) {
            // 少数 classpath* 根可能使用不带前导斜杠的 shared/...。
            idx = uriString.indexOf(CLASSPATH_PREFIX);
            if (idx < 0) return null;
            return uriString.substring(idx + CLASSPATH_PREFIX.length());
        }
        return uriString.substring(idx + CLASSPATH_PREFIX.length() + 1);
    }

    private static FileSystem openOrCreateJarFs(URI jarFile) throws IOException {
        try {
            return FileSystems.getFileSystem(jarFile);
        } catch (Exception ignore) {
            return FileSystems.newFileSystem(jarFile, Map.of());
        }
    }

    private static void writeAtomic(Path target, byte[] content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, content);
        try {
            Files.move(
                    tmp,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record LoadedFile(String relPath, byte[] content) {}
}
