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
package io.agentscope.core.model;

import io.agentscope.core.model.spi.ModelProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 根据字符串标识符解析 {@link Model} 实例的注册表，支持命名实例和
 * {@code provider:model} 模式。用户注册的工厂优先于通过 {@link ModelProvider} SPI 加载的服务商。
 *
 * <p>服务商扩展模块自动创建模型时，从各自标准环境变量中读取 API 密钥。
 */
public final class ModelRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ModelRegistry.class);

    private static final ConcurrentHashMap<String, Model> namedModels = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<ProviderEntry> userFactories =
            new CopyOnWriteArrayList<>();

    /**
     * 工厂创建的实例。每个条目在创建开始前就发布到缓存，
     * 因此同一个键的并发首次解析会等待同一次创建，而不是各自创建并遗留多余实例。
     *
     * <p>【学习提示】缓存中的条目承担“正在创建”的占位作用，不只是保存已完成对象。
     * 这可合并并发首访，但必须同时处理创建失败与递归自解析，否则可能永久等待。
     */
    private static final ConcurrentHashMap<ModelCacheKey, CompletableFuture<Model>> resolvedCache =
            new ConcurrentHashMap<>();

    /** 当前线程正在创建的缓存键，用于在递归解析自身时快速失败。 */
    private static final ThreadLocal<Set<ModelCacheKey>> CREATING =
            ThreadLocal.withInitial(HashSet::new);

    private static volatile List<ModelProvider> serviceProviders;

    private ModelRegistry() {}

    /**
     * 注册命名的 {@link Model} 实例。{@link #resolve(String)} 遇到精确匹配的
     * {@code name} 时返回此实例（不适用工厂创建实例的缓存机制）。
     */
    public static void register(String name, Model model) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(model, "model");
        namedModels.put(name, model);
    }

    /**
     * 注册对完整 {@code modelId} 字符串使用 {@link
     * Pattern#matches} 匹配的工厂。
     * 新注册的工厂优先于较早的用户注册项以及 SPI 服务商。
     *
     * @param modelNameRegex 对完整模型 ID 使用 Pattern#matches(CharSequence) 语义的正则表达式
     * @param factory 根据完整模型 ID 创建 {@link Model} 的工厂
     */
    public static void registerFactory(String modelNameRegex, ModelFactory factory) {
        Objects.requireNonNull(modelNameRegex, "modelNameRegex");
        Objects.requireNonNull(factory, "factory");
        registerFactory(modelNameRegex, (modelId, context) -> factory.create(modelId));
    }

    /**
     * 注册感知上下文的工厂，使用 {@code modelId} 的完整字符串与
     * {@link Pattern#matches} 匹配。新注册的工厂优先于较早的用户注册项以及 SPI 服务商。
     *
     * <p>模型 ID 匹配新模式的缓存实例会被移除，因此下次 {@link #resolve} 该 ID 时
     * 由此工厂处理。被移除的实例不会被关闭：注册表不拥有这些实例，
     * 已经持有它们的调用方可能仍在使用。
     *
     * @param modelNameRegex 对完整模型 ID 使用 Pattern#matches(CharSequence) 语义的正则表达式
     * @param factory 根据完整模型 ID 和创建上下文构建 {@link Model} 的工厂
     */
    public static void registerFactory(String modelNameRegex, ContextModelFactory factory) {
        Objects.requireNonNull(modelNameRegex, "modelNameRegex");
        Objects.requireNonNull(factory, "factory");
        Pattern pattern = Pattern.compile(modelNameRegex);
        // 先添加再移除缓存：resolve() 会先发布缓存条目，再查找工厂；
        // 因此，即使某次解析与新增操作竞争并选中了旧工厂，
        // 执行移除时它也已经发布了缓存条目，该条目会在此被移除。
        userFactories.add(0, new ProviderEntry(pattern, factory));
        resolvedCache.keySet().removeIf(key -> pattern.matcher(key.modelId()).matches());
    }

    /**
     * 为指定 ID 解析 {@link Model}：先找命名注册项，再找缓存的工厂实例，
     * 然后查用户工厂（最新优先），最后查 SPI 服务商。
     *
     * @throws IllegalArgumentException 无法解析 ID 或创建失败时抛出
     */
    public static Model resolve(String modelId) {
        return resolve(modelId, ModelCreationContext.empty());
    }

    /**
     * 根据指定 ID 和创建上下文解析 {@link Model}：先找命名注册项，
     * 再在启用缓存时查缓存，然后查用户工厂（最新优先），最后查 SPI 服务商。
     *
     * <p>非空上下文的解析默认不缓存，除非上下文使用 {@link CachePolicy#ENABLED}。
     *
     * <p>启用缓存时，同一键的并发首次调用共享同一次创建并获得相同实例。
     * 创建失败不会缓存。
     *
     * @throws IllegalArgumentException 无法解析 ID、上下文无法安全缓存或创建失败时抛出
     */
    public static Model resolve(String modelId, ModelCreationContext context) {
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(context, "context");
        String trimmed = modelId.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("modelId must not be blank");
        }

        Model named = namedModels.get(trimmed);
        if (named != null) {
            return named;
        }

        ModelCacheKey cacheKey = cacheKey(trimmed, context);
        if (cacheKey == null) {
            return create(trimmed, context);
        }
        CompletableFuture<Model> pending = new CompletableFuture<>();
        CompletableFuture<Model> existing = resolvedCache.putIfAbsent(cacheKey, pending);
        if (existing != null) {
            if (!existing.isDone() && CREATING.get().contains(cacheKey)) {
                // 工厂若解析自己的 ID，否则会永远等待自身完成。
                throw new IllegalStateException(
                        "Recursive resolution of model id while it is being created: " + trimmed);
            }
            return awaitCached(existing);
        }
        Set<ModelCacheKey> creating = CREATING.get();
        creating.add(cacheKey);
        try {
            Model created = create(trimmed, context);
            pending.complete(created);
            return created;
        } catch (RuntimeException | Error e) {
            // 不缓存失败：等待方收到本次错误，之后的调用方可重新尝试创建。
            resolvedCache.remove(cacheKey, pending);
            pending.completeExceptionally(e);
            throw e;
        } finally {
            creating.remove(cacheKey);
        }
    }

    private static Model awaitCached(CompletableFuture<Model> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            if (e.getCause() instanceof Error cause) {
                throw cause;
            }
            throw e;
        }
    }

    private static Model create(String trimmed, ModelCreationContext context) {
        ProviderEntry entry = findMatchingUserEntry(trimmed);
        ModelProvider provider = entry == null ? findServiceProvider(trimmed, context) : null;
        if (entry == null && provider == null) {
            throw new IllegalArgumentException(buildNotFoundMessage(trimmed));
        }

        try {
            Model created;
            if (entry != null) {
                created = entry.factory().create(trimmed, context);
                Objects.requireNonNull(created, "ModelFactory returned null for: " + trimmed);
            } else {
                created = provider.create(trimmed, context);
                Objects.requireNonNull(
                        created,
                        "ModelProvider "
                                + provider.providerId()
                                + " returned null for: "
                                + trimmed);
            }
            return created;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "Failed to create model for id: " + trimmed + ": " + e.getMessage(), e);
        }
    }

    /**
     * 返回 {@code true} 表示 {@link #resolve(String)} 能找到命名模型或匹配的工厂／服务商模式，
     * 此检查不会创建实例。
     */
    public static boolean canResolve(String modelId) {
        return canResolve(modelId, ModelCreationContext.empty());
    }

    /**
     * 返回 {@code true} 表示 {@link #resolve(String, ModelCreationContext)} 能找到命名模型
     * 或匹配的工厂／服务商模式，此检查不会创建实例。
     */
    public static boolean canResolve(String modelId, ModelCreationContext context) {
        Objects.requireNonNull(context, "context");
        if (modelId == null) {
            return false;
        }
        String trimmed = modelId.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        if (namedModels.containsKey(trimmed)) {
            return true;
        }
        return findMatchingUserEntry(trimmed) != null
                || findServiceProvider(trimmed, context) != null;
    }

    /**
     * 清除命名模型、用户注册工厂、工厂解析缓存以及已缓存的 SPI 服务商。
     * 用于测试。
     */
    public static void reset() {
        namedModels.clear();
        userFactories.clear();
        resolvedCache.clear();
        reloadProviders();
    }

    /** 清除已缓存的 SPI 服务商，以便下一次解析从当前类加载器上下文中重新发现服务商。 */
    public static void reloadProviders() {
        serviceProviders = null;
    }

    @FunctionalInterface
    public interface ModelFactory {
        Model create(String modelId);
    }

    @FunctionalInterface
    public interface ContextModelFactory {
        Model create(String modelId, ModelCreationContext context);
    }

    private record ProviderEntry(Pattern pattern, ContextModelFactory factory) {}

    private record ModelCacheKey(String modelId, String cacheIdentity) {}

    private static ProviderEntry findMatchingUserEntry(String modelId) {
        for (ProviderEntry e : userFactories) {
            if (e.pattern().matcher(modelId).matches()) {
                return e;
            }
        }
        return null;
    }

    private static ModelProvider findServiceProvider(String modelId, ModelCreationContext context) {
        ModelProvider matched = null;
        for (ModelProvider provider : loadServiceProviders()) {
            boolean supports;
            try {
                supports = provider.supports(modelId, context);
            } catch (RuntimeException | LinkageError e) {
                logger.warn(
                        "Skipping ModelProvider {} because supports(\"{}\") failed",
                        provider.getClass().getName(),
                        modelId,
                        e);
                continue;
            }
            if (!supports) {
                continue;
            }
            if (matched != null) {
                logger.warn(
                        "Multiple ModelProvider implementations support model id \"{}\": {} and"
                                + " {}. Using the first discovered provider.",
                        modelId,
                        matched.getClass().getName(),
                        provider.getClass().getName());
                continue;
            }
            matched = provider;
        }
        return matched;
    }

    /**
     * 构造模型解析使用的注册表缓存键。
     *
     * <p>这些规则刻意避免假设任意上下文对象均可比较：
     * <ul>
     *   <li>空上下文保留旧版行为，仅按 {@code modelId} 缓存。
     *   <li>非空上下文仅在选择 {@link CachePolicy#ENABLED} 时缓存。
     *   <li>显式 {@code cacheId} 是复杂上下文的权威标识。
     *   <li>没有 {@code cacheId} 时，仅对标准简单字段生成指纹。选项与组件保持不透明，
     *       不得依赖深度哈希或对象的 {@code hashCode()}。
     * </ul>
     */
    private static ModelCacheKey cacheKey(String modelId, ModelCreationContext context) {
        if (context.isEmpty()) {
            return new ModelCacheKey(modelId, "legacy");
        }
        if (context.getCachePolicy() == CachePolicy.DISABLED
                || context.getCachePolicy() == CachePolicy.DEFAULT) {
            return null;
        }

        String cacheId = context.getCacheId();
        if (cacheId != null) {
            return new ModelCacheKey(modelId, "explicit:" + cacheId);
        }
        if (context.hasOpaqueCacheInputs()) {
            throw new IllegalArgumentException(
                    "ModelCreationContext cachePolicy(ENABLED) with options or components "
                            + "requires an explicit cacheId");
        }
        return new ModelCacheKey(modelId, "standard:" + standardContextFingerprint(context));
    }

    /**
     * 仅根据与服务商无关的简单字段创建缓存指纹。
     *
     * <p>API 密钥绝不会以明文写入键。该摘要并非安全边界，
     * 仅用于避免密钥意外出现在堆转储、日志和测试诊断中。
     */
    private static String standardContextFingerprint(ModelCreationContext context) {
        return "apiKeySha256="
                + sha256Hex(context.getApiKey())
                + "|baseUrl="
                + valueOf(context.getBaseUrl())
                + "|endpointPath="
                + valueOf(context.getEndpointPath())
                + "|stream="
                + valueOf(context.getStream())
                + "|enableThinking="
                + valueOf(context.getEnableThinking());
    }

    private static String valueOf(Object value) {
        return value == null ? "<null>" : value.toString();
    }

    private static String sha256Hex(String value) {
        if (value == null) {
            return "<null>";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static List<ModelProvider> loadServiceProviders() {
        List<ModelProvider> providers = serviceProviders;
        if (providers != null) {
            return providers;
        }
        synchronized (ModelRegistry.class) {
            providers = serviceProviders;
            if (providers == null) {
                providers = new ArrayList<>();
                providers.addAll(
                        loadServiceProviders(Thread.currentThread().getContextClassLoader()));
                Collection<ModelProvider> fallback =
                        loadServiceProviders(ModelRegistry.class.getClassLoader());
                for (ModelProvider provider : fallback) {
                    if (!containsProvider(providers, provider)) { // 去重
                        providers.add(provider);
                    }
                }
                providers = List.copyOf(providers);
                serviceProviders = providers;
            }
            return providers;
        }
    }

    private static Collection<ModelProvider> loadServiceProviders(ClassLoader classLoader) {
        List<ModelProvider> providers = new ArrayList<>();
        ServiceLoader<ModelProvider> loader =
                classLoader != null
                        ? ServiceLoader.load(ModelProvider.class, classLoader)
                        : ServiceLoader.load(ModelProvider.class);
        Iterator<ModelProvider> iterator = loader.iterator();
        while (true) {
            boolean hasNext;
            try {
                hasNext = iterator.hasNext();
            } catch (ServiceConfigurationError | LinkageError e) {
                logger.warn("Skipping invalid ModelProvider service declaration", e);
                break;
            }
            if (!hasNext) {
                break;
            }
            try {
                providers.add(iterator.next());
            } catch (ServiceConfigurationError | LinkageError e) {
                logger.warn("Skipping invalid ModelProvider service declaration", e);
            }
        }
        return providers;
    }

    private static boolean containsProvider(
            List<ModelProvider> providers, ModelProvider candidate) {
        for (ModelProvider provider : providers) {
            if (provider.getClass().getName().equals(candidate.getClass().getName())) {
                return true;
            }
        }
        return false;
    }

    private static String buildNotFoundMessage(String modelId) {
        return "Cannot resolve model: \""
                + modelId
                + "\".\n\nPossible causes:\n"
                + "  - No named model registered with this name. Use ModelRegistry.register(\""
                + modelId
                + "\", instance).\n"
                + "  - No matching provider factory or extension SPI provider.\n"
                + "    Format: \"<provider>:<model-name>\", e.g. \"openai:gpt-5.5\","
                + " \"gemini:gemini-2.0-flash\", \"dashscope:qwen-max\","
                + " \"dashscope:qwen3.7-plus\".\n"
                + "  - OpenAI models require the agentscope-extensions-model-openai module on the"
                + " classpath and OPENAI_API_KEY.\n"
                + "  - Gemini models require the agentscope-extensions-model-gemini module on the"
                + " classpath and GEMINI_API_KEY.\n"
                + "  - Anthropic models require the agentscope-extensions-model-anthropic module"
                + " on the classpath.\n"
                + "  - DashScope models require the agentscope-extensions-model-dashscope module"
                + " on the classpath and DASHSCOPE_API_KEY.\n"
                + "  - DashScope short form: \"qwen*\" model ids (e.g. \"qwen-max\","
                + " \"qwen3.7-plus\") are provided by agentscope-extensions-model-dashscope.\n"
                + "  - Ollama models require the agentscope-extensions-model-ollama module on the"
                + " classpath. OLLAMA_BASE_URL is optional and defaults to"
                + " http://localhost:11434.\n"
                + "  - Missing API key environment variable (e.g., DASHSCOPE_API_KEY).";
    }
}
