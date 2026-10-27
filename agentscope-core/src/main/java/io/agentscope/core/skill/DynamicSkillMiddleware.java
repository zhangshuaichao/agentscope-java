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
package io.agentscope.core.skill;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.RuntimeContextSkillRepository;
import io.agentscope.core.tool.Toolkit;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 每次 {@code call()} 时，通过 {@link #onSystemPrompt(Agent, RuntimeContext, String)} 从有序的{@link AgentSkillRepository repositories}列表动态组合技能，以单次调用视图替换静态技能提示，支持按用户隔离技能及便捷接入技能市场。
 *
 * <p>仓库列表从低优先级开始遍历；若两个仓库提供相同 {@link AgentSkill#getName()} 的技能，后出现的高优先级条目胜出。
 *
 * <p>有意在每次调用时重建：随着 {@link RuntimeContext} 切换用户，按用户命名空间划分的仓库可能为同名技能返回不同内容，仅按技能 ID 缓存会掩盖这些变化。以可见视图签名为键的内容寻址缓存，在可见内容不变时复用已物化的不可变 box，因此仅在内容真正变化时重新运行 {@code uploadSkillFiles}。不会随技能视图变化在共享工具包上重新注册工具。
 *
 * <p>子类可重写 {@link #filterVisible(List, RuntimeContext)} 接入运行时可见性逻辑（灰度名单、环境限制等）；默认实现原样返回输入列表。
 *
 * <p>【学习提示】这里分离了三种生命周期：仓库视图按调用重新读取，加载工具在工具包中共享，激活状态归当前会话。内容相同可复用物化目录，但目录中的执行副作用也会被后续同签名调用看到；内容寻址不等于文件系统隔离。
 *
 * <p>约定：
 * <ul>
 * <li>构造时不注册工具；共享 {@code load_skill_through_path} 工具在首次提示处理时注册到调用智能体当前的工具包，早于工具模式枚举，因此模型在同次调用即可看到它。也可通过 {@link #registerSkillTools(Toolkit)} 手动配置。</li>
 * <li>每次处理的运行时对象从调用智能体解析：当前工具包（{@link Agent#getToolkit()}）用于构建 box，过滤基线与稳定工作目录（{@link Agent#getSkillWorkDir()}）决定过滤和物化。实例不持有智能体专属运行时引用。</li>
 * <li>同签名缓存命中共享一个物化目录：技能执行产生的修改对后续相同内容调用仍可见；内容变化则物化到新的隔离目录。</li>
 * <li>框架为每次调用创建新的 {@link RuntimeContext}；每次处理都会覆盖或清除 {@link SkillBox} 绑定。</li>
 * <li>生效过滤器将单次调用上下文的叠加规则应用到调用智能体的基线（{@link Agent#getSkillFilter()}）；智能体未提供时默认为 {@link SkillFilter#all()}。</li>
 * </ul>
 */
@SuppressWarnings("deprecation")
public class DynamicSkillMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(DynamicSkillMiddleware.class);
    private static final int MAX_CACHED_BOXES = 32;

    private final List<AgentSkillRepository> repositories;

    private final boolean codeExecutionEnabled;

    /**
     * 调用智能体未提供目录时，用于物化 {@link SkillBox} 目录的 JVM 全局备用根目录。只分配一次，通过关闭钩子在 JVM 退出时清理，保留重构前的目录处理行为；智能体提供的根目录由调用方管理。
     */
    private static volatile Path fallbackWorkDir;

    /**
     * 以可见视图签名为键、缓存已物化 {@link SkillBox} 的内容寻址缓存。box 构建后不可变，因此可由内容相同的会话安全共享，并避免重复运行 {@code uploadSkillFiles}。
     */
    private final Map<String, SkillBox> boxCache = new LinkedHashMap<>();

    /**
     * 便捷构造方法，委托给禁用代码执行的完整构造方法。
     *
     * @param repositories 按低到高优先级排序的仓库列表，按技能名称合并
     */
    public DynamicSkillMiddleware(List<AgentSkillRepository> repositories) {
        this(repositories, false);
    }

    /**
     * 完整构造方法，仅初始化字段。共享加载工具可在组装时通过 {@link #registerSkillTools(Toolkit)} 注册（由智能体构建器调用）；在智能体组装流程外使用时，也可延迟到首次提示处理前注册。每次处理使用的工具包、过滤基线和物化根目录均从调用智能体解析。
     *
     * @param repositories 按低到高优先级排序的仓库列表，按技能名称合并
     * @param codeExecutionEnabled 为 {@code true} 时启用提示提供者的代码执行块；仅当智能体已接入类似 Shell 的工具时才应开启，否则提示会要求模型做无法执行的操作
     */
    public DynamicSkillMiddleware(
            List<AgentSkillRepository> repositories, boolean codeExecutionEnabled) {
        this.repositories = repositories != null ? List.copyOf(repositories) : List.of();
        this.codeExecutionEnabled = codeExecutionEnabled;
    }

    /**
     * 向给定工具包注册共享 {@code load_skill_through_path} 工具。每次提示处理时以调用智能体当前工具包调用，早于工具模式枚举，使工具在同次调用可见；也可直接调用以手动配置。工具包为 {@code null} 或已有同名工具时不操作（从不覆盖）。由于检查直接针对工具包，工具被移除后会在下次调用重新注册。
     *
     * @param toolkit 接收共享加载工具的工具包，可以为 {@code null}
     */
    public void registerSkillTools(Toolkit toolkit) {
        if (toolkit == null || SkillToolFactory.isRuntimeLoadToolRegistered(toolkit)) {
            return;
        }
        // 共享加载工具从 RuntimeContext 解析当前调用的 SkillBox，
        // 因此无需为每次调用的技能视图修改共享工具包。
        SkillToolFactory.registerRuntimeLoadTool(toolkit);
    }

    /**
     * 仅声明实际使用的钩子；子类若重写更多钩子，必须扩展此集合。
     */
    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_SYSTEM_PROMPT);
    }

    /**
     * 重新加载可见技能视图，并将渲染后的提示追加到 {@code currentPrompt}。
     *
     * <p>重载包含阻塞 I/O（仓库读取、文件物化），因此运行在 {@link Schedulers#boundedElastic()} 而不是订阅线程上，避免阻塞响应式事件循环。语义不变：每次调用仍会重载，而可见内容不变时内容寻址缓存复用已物化的 box。缓存命中的快速路径也无法留在调用线程，因为只有阻塞仓库读取之后才知道缓存键（内容签名），因此包括缓存命中在内的整个重载都移到 boundedElastic。
     *
     * <p>智能体约定：框架调用此钩子时提供非 null 的 {@code agent}；直接调用方应提供智能体，否则只能接受 null 带来的降级行为（容忍但不承诺）。
     *
     * @param agent 其工具包、技能过滤基线和工作目录决定此次处理的智能体；在组装流程外直接使用时可以为 {@code null}
     * @param ctx 单次调用的 {@link RuntimeContext}；{@code null} 视为空上下文
     * @param currentPrompt 此前中间件已组合的系统提示
     * @return 追加本次技能部分后的提示；没有可见技能时原样返回 {@code currentPrompt}
     */
    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        // 工具跟随中间件绑定：首次提示处理前注册到调用智能体当前的
        // 工具包（幂等：存在同名工具即视为已注册）。
        // 此操作早于工具模式枚举，因此加载工具
        // 在同次调用中即可被模型看到。
        if (agent != null) {
            registerSkillTools(agent.getToolkit());
        }
        RuntimeContext rc = ctx != null ? ctx : RuntimeContext.empty();
        return Mono.fromCallable(
                        () -> {
                            SkillBox box = reloadSkills(agent, rc);
                            // 暴露本次调用的 SkillBox，或在 null 时清除绑定，
                            // 让共享加载工具解析本次技能视图，避免复用的
                            // RuntimeContext 提供过期 box。
                            rc.put(SkillBox.class, box);
                            return appendSkillPrompt(agent, box, rc, currentPrompt);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 将 box 渲染的技能提示追加到 {@code currentPrompt} 后返回；若 {@code box} 为 {@code null} 或没有渲染出提示，则原样返回 {@code currentPrompt}。
     */
    private String appendSkillPrompt(
            Agent agent, SkillBox box, RuntimeContext rc, String currentPrompt) {
        if (box == null) {
            return currentPrompt;
        }
        SkillFilter effectiveFilter = resolveFilter(agent, rc);
        String prompt = box.getSkillPrompt(effectiveFilter);
        if (prompt == null || prompt.isEmpty()) {
            return currentPrompt;
        }
        String base = currentPrompt != null ? currentPrompt : "";
        String separator = base.isEmpty() || base.endsWith("\n") ? "" : "\n";
        return base + separator + prompt;
    }

    /**
     * 供子类移除当前请求不应看到的技能（灰度发布、允许名单、环境限制等）的钩子。每次 {@code call()} 调用一次，位于跨仓库合并之后、重建 {@link SkillBox} 之前。
     *
     * <p>默认实现原样返回 {@code raw}。
     *
     * @param raw 合并并去重后的技能列表（仓库优先级从低到高）
     * @param ctx 单次调用的 {@link RuntimeContext}，不会为 {@code null}
     * @return 可见子集（不得为 {@code null}；空列表会短路本次调用的提示更新）
     */
    protected List<AgentSkill> filterVisible(List<AgentSkill> raw, RuntimeContext ctx) {
        return raw;
    }

    /**
     * 生效过滤器 = 在调用智能体基线之上叠加单次调用上下文规则。
     */
    private SkillFilter resolveFilter(Agent agent, RuntimeContext rc) {
        SkillFilter baseline = resolveBaseline(agent);
        SkillFilter runtimeOverlay = rc != null ? rc.get(SkillFilter.class) : null;
        return baseline.overlay(runtimeOverlay);
    }

    private SkillFilter resolveBaseline(Agent agent) {
        SkillFilter fromAgent = agent != null ? agent.getSkillFilter() : null;
        return fromAgent != null ? fromAgent : SkillFilter.all();
    }

    private SkillBox reloadSkills(Agent agent, RuntimeContext ctx) {
        if (repositories.isEmpty()) {
            return null;
        }
        Map<String, AgentSkill> skillsByName = new LinkedHashMap<>();
        for (AgentSkillRepository repo : repositories) {
            List<AgentSkill> skills;
            try {
                skills =
                        repo instanceof RuntimeContextSkillRepository contextual
                                ? contextual.getAllSkills(ctx)
                                : repo.getAllSkills();
            } catch (Exception e) {
                log.warn(
                        "Skill repository {} failed to load: {}",
                        repo.getClass().getSimpleName(),
                        e.getMessage());
                continue;
            }
            if (skills == null) {
                continue;
            }
            for (AgentSkill skill : skills) {
                if (skill == null || skill.getName() == null) {
                    continue;
                }
                skillsByName.put(skill.getName(), skill);
            }
        }
        if (skillsByName.isEmpty()) {
            return null;
        }
        // 重建 SkillBox 前应用子类可见性钩子，确保提示
        // 只包含当前 ctx 获准看到的技能。
        List<AgentSkill> visible;
        try {
            List<AgentSkill> filtered = filterVisible(new ArrayList<>(skillsByName.values()), ctx);
            visible = filtered != null ? filtered : new ArrayList<>(skillsByName.values());
        } catch (Exception e) {
            log.warn(
                    "filterVisible() in {} failed; treating as pass-through: {}",
                    getClass().getSimpleName(),
                    e.getMessage());
            visible = new ArrayList<>(skillsByName.values());
        }
        if (visible.isEmpty()) {
            return null;
        }

        String signature = computeSignature(visible);
        synchronized (boxCache) {
            SkillBox box = boxCache.get(signature);
            if (box == null) {
                box = buildBox(agent, visible, signature);
                if (boxCache.size() >= MAX_CACHED_BOXES) {
                    // 仅限制保留的 box 数量。活动请求可能仍引用已淘汰的
                    // box 及文件，因此资源目录必须保持可用。
                    boxCache.clear();
                }
                boxCache.put(signature, box);
            }
            return box;
        }
    }

    private SkillBox buildBox(Agent agent, List<AgentSkill> visible, String signature) {
        SkillBox box = new SkillBox(agent != null ? agent.getToolkit() : null);
        // 每次物化都拥有独立目录。淘汰后重建同一签名时，
        // 不得截断较早请求仍可能读取的文件。
        try {
            Path root = ensureStableWorkDir(agent);
            Files.createDirectories(root);
            box.setWorkDir(Files.createTempDirectory(root, signature + "-"));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to allocate skill resource directory", e);
        }
        for (AgentSkill skill : visible) {
            box.registerSkill(skill);
        }
        if (box.isAutoUploadSkill()) {
            try {
                box.uploadSkillFiles();
            } catch (Exception e) {
                log.warn("Failed to upload skill files: {}", e.getMessage());
            }
        }
        // 切换代码执行提示块：启用 codeExecutionEnabled 时，提示
        // 提供者会输出每个技能的 <files-root>（所有可见技能均有
        // originDir），或回退到单一 uploadDir 模板。两种方式都让 LLM
        // 获得可寻址路径，而不是只有 `load_skill_through_path` 返回的内容块。
        box.getSkillPromptProvider().setCodeExecutionEnable(codeExecutionEnabled);
        return box;
    }

    /**
     * 解析此次处理的 workDir：优先采用调用智能体提供的稳定目录（由调用方管理），否则使用首次调用时延迟分配并注册退出清理的共享备用根目录。JVM 运行期间资源树持续可用，即使对应缓存项已被淘汰。
     */
    private Path ensureStableWorkDir(Agent agent) {
        Path fromAgent = agent != null ? agent.getSkillWorkDir() : null;
        if (fromAgent != null) {
            return fromAgent;
        }
        Path dir = fallbackWorkDir;
        if (dir != null) {
            return dir;
        }
        synchronized (DynamicSkillMiddleware.class) {
            if (fallbackWorkDir != null) {
                return fallbackWorkDir;
            }
            try {
                Path created = Files.createTempDirectory("agentscope-skill-workdir-");
                Runtime.getRuntime()
                        .addShutdownHook(
                                new Thread(
                                        () -> {
                                            try {
                                                deleteRecursively(created);
                                            } catch (IOException e) {
                                                log.debug(
                                                        "shutdown cleanup of {} failed: {}",
                                                        created,
                                                        e.getMessage());
                                            }
                                        }));
                fallbackWorkDir = created;
                return created;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to allocate stable skill workDir", e);
            }
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            List<Path> all = new ArrayList<>();
            stream.forEach(all::add);
            all.sort((a, b) -> b.getNameCount() - a.getNameCount());
            for (Path p : all) {
                Files.deleteIfExists(p);
            }
        }
    }

    /**
     * 为合并后的可见技能视图计算确定性 SHA-256：每个技能使用 {@code name + sha256(content) + sha256(sorted-resource-keys) + originDir}。相同可见内容的两次重载生成相同签名，不受映射遍历顺序影响，因此内容寻址的 {@link #boxCache} 可复用 box，无需重新上传文件。
     */
    private static String computeSignature(List<AgentSkill> visible) {
        Set<String> sortedNames = new TreeSet<>();
        for (AgentSkill s : visible) {
            sortedNames.add(s.getName());
        }
        Map<String, AgentSkill> byName = new LinkedHashMap<>();
        for (AgentSkill s : visible) {
            byName.put(s.getName(), s);
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String name : sortedNames) {
                AgentSkill s = byName.get(name);
                md.update(name.getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(
                        s.getSource() != null
                                ? s.getSource().getBytes(StandardCharsets.UTF_8)
                                : new byte[0]);
                md.update((byte) 0);
                hashMetadata(md, s.getMetadata());
                md.update((byte) 0);
                md.update(
                        s.getSkillContent() != null
                                ? s.getSkillContent().getBytes(StandardCharsets.UTF_8)
                                : new byte[0]);
                md.update((byte) 0);
                for (String key : new TreeSet<>(s.getResourcePaths())) {
                    md.update(key.getBytes(StandardCharsets.UTF_8));
                    String val = s.getResource(key);
                    if (val != null) {
                        md.update(val.getBytes(StandardCharsets.UTF_8));
                    }
                    md.update((byte) 0);
                }
                s.getOriginDir()
                        .ifPresent(p -> md.update(p.toString().getBytes(StandardCharsets.UTF_8)));
                md.update((byte) 1);
            }
            byte[] hash = md.digest();
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * 对技能的全部元数据做哈希（键排序，递归处理嵌套映射和列表），使渲染到提示中的自定义字段（version/homepage/license 等）也参与内容签名。
     */
    private static void hashMetadata(MessageDigest md, Map<String, Object> metadata) {
        if (metadata == null) {
            return;
        }
        for (String key : new TreeSet<>(metadata.keySet())) {
            md.update(key.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            hashMetadataValue(md, metadata.get(key));
            md.update((byte) 0);
        }
    }

    @SuppressWarnings("unchecked")
    private static void hashMetadataValue(MessageDigest md, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String s) {
            md.update(s.getBytes(StandardCharsets.UTF_8));
        } else if (value instanceof Map<?, ?> rawMap) {
            @SuppressWarnings("unchecked")
            Map<Object, Object> map = (Map<Object, Object>) rawMap;
            md.update((byte) '{');
            List<Object> keys = new ArrayList<>(map.keySet());
            keys.sort(Comparator.comparing(Object::toString));
            for (Object key : keys) {
                md.update(String.valueOf(key).getBytes(StandardCharsets.UTF_8));
                md.update((byte) ':');
                hashMetadataValue(md, map.get(key));
            }
            md.update((byte) '}');
        } else if (value instanceof List<?> list) {
            md.update((byte) '[');
            for (Object element : list) {
                hashMetadataValue(md, element);
            }
            md.update((byte) ']');
        } else {
            md.update(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
        }
    }
}
