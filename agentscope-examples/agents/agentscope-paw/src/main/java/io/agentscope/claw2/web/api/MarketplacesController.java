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
package io.agentscope.claw2.web.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentscope.claw2.marketplace.ClawMarketplace;
import io.agentscope.claw2.marketplace.ClawMarketplaceRegistry;
import io.agentscope.claw2.marketplace.MarketSkillContent;
import io.agentscope.claw2.marketplace.MarketSkillSummary;
import io.agentscope.claw2.marketplace.MarketplacePersistence;
import io.agentscope.claw2.runtime.config.MarketplaceConfigEntry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * 全局市场管理。通过 {@link MarketplacePersistence} 对 {@code agentscope.json} 的 {@code marketplaces} 映射执行增删改查，并经 {@link ClawMarketplaceRegistry} 只读浏览市场技能。
 *
 * <p>端点为全局作用域，市场不绑定特定智能体，因此路径不含 {@code {agentId}}。逐智能体安装由 {@code AgentSkillsController} 完成，目标为该智能体的 {@code workspace/skills/}。
 */
@RestController
@RequestMapping("/api/marketplaces")
public class MarketplacesController {

    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9._-]+");

    /** 不回显到 API 响应中的敏感属性键，避免密码通过响应历史泄露。 */
    // 【学习提示】这里只过滤已知字段名，不是通用脱敏器；URL 内嵌凭据、其他键或错误文本仍需单独检查。
    private static final Set<String> SECRET_KEYS = Set.of("password", "secretKey");

    private static final Set<String> SUPPORTED_TYPES = Set.of("git", "nacos");

    private final ClawMarketplaceRegistry registry;
    private final MarketplacePersistence persistence;

    public MarketplacesController(
            ClawMarketplaceRegistry registry, MarketplacePersistence persistence) {
        this.registry = registry;
        this.persistence = persistence;
    }

    // -----------------------------------------------------------------
    // 增删改查
    // -----------------------------------------------------------------

    @GetMapping("")
    public Mono<List<MarketplaceSummary>> listMarketplaces() {
        return Mono.fromCallable(
                () -> {
                    // 从磁盘读取，使响应反映最新持久状态，而不是
                    // 启动时快照。注册表缓存活动实例，但磁盘
                    // 映射才是 properties 等展示字段的依据。
                    Map<String, MarketplaceConfigEntry> declared =
                            persistence.currentConfig().getMarketplaces();
                    if (declared == null || declared.isEmpty()) return List.of();
                    List<MarketplaceSummary> out = new ArrayList<>(declared.size());
                    for (Map.Entry<String, MarketplaceConfigEntry> e : declared.entrySet()) {
                        out.add(toSummary(e.getKey(), e.getValue()));
                    }
                    out.sort(Comparator.comparing(MarketplaceSummary::id));
                    return out;
                });
    }

    @PostMapping("")
    public Mono<MarketplaceSummary> createMarketplace(@RequestBody MarketplaceWriteRequest req) {
        return Mono.fromCallable(
                () -> {
                    validateRequest(req, true);
                    String id = req.id().trim();
                    MarketplaceConfigEntry entry = toConfigEntry(req);
                    return persistence.mutate(
                            map -> {
                                if (map.containsKey(id)) {
                                    throw new ResponseStatusException(
                                            HttpStatus.CONFLICT,
                                            "Marketplace already exists: " + id);
                                }
                                map.put(id, entry);
                                return toSummary(id, entry);
                            },
                            List.of(id));
                });
    }

    @PutMapping("/{id}")
    public Mono<MarketplaceSummary> updateMarketplace(
            @PathVariable String id, @RequestBody MarketplaceWriteRequest req) {
        return Mono.fromCallable(
                () -> {
                    validateId(id);
                    if (req == null) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "request body is required");
                    }
                    // PUT 忽略请求体 ID，以路径 ID 为准，防止拼写错误导致条目移位。
                    MarketplaceWriteRequest normalized =
                            new MarketplaceWriteRequest(id, req.type(), req.properties());
                    validateRequest(normalized, false);
                    MarketplaceConfigEntry entry = toConfigEntry(normalized);
                    return persistence.mutate(
                            map -> {
                                if (!map.containsKey(id)) {
                                    throw new ResponseStatusException(
                                            HttpStatus.NOT_FOUND, "Marketplace not found: " + id);
                                }
                                map.put(id, entry);
                                return toSummary(id, entry);
                            },
                            List.of(id));
                });
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteMarketplace(@PathVariable String id) {
        return Mono.fromRunnable(
                () -> {
                    validateId(id);
                    persistence.mutate(
                            map -> {
                                if (map.remove(id) == null) {
                                    throw new ResponseStatusException(
                                            HttpStatus.NOT_FOUND, "Marketplace not found: " + id);
                                }
                                return null;
                            },
                            List.of(id));
                });
    }

    // -----------------------------------------------------------------
    // 连接测试
    // -----------------------------------------------------------------

    @PostMapping("/test")
    public Mono<TestConnectionResult> testTransient(@RequestBody MarketplaceWriteRequest req) {
        return Mono.fromCallable(
                () -> {
                    validateRequest(req, true);
                    MarketplaceConfigEntry entry = toConfigEntry(req);
                    return probe(req.id().trim(), entry);
                });
    }

    @PostMapping("/{id}/test")
    public Mono<TestConnectionResult> testExisting(@PathVariable String id) {
        return Mono.fromCallable(
                () -> {
                    validateId(id);
                    MarketplaceConfigEntry entry =
                            persistence.currentConfig().getMarketplaces() == null
                                    ? null
                                    : persistence.currentConfig().getMarketplaces().get(id);
                    if (entry == null) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Marketplace not found: " + id);
                    }
                    return probe(id, entry);
                });
    }

    // -----------------------------------------------------------------
    // 列出与获取技能
    // -----------------------------------------------------------------

    @GetMapping("/{id}/skills")
    public Mono<List<MarketSkillBrief>> listSkills(@PathVariable String id) {
        return Mono.fromCallable(
                () -> {
                    ClawMarketplace mp = requireRegistered(id);
                    List<MarketSkillSummary> raw;
                    try {
                        raw = mp.list();
                    } catch (RuntimeException e) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_GATEWAY,
                                "Marketplace listing failed: " + e.getMessage(),
                                e);
                    }
                    List<MarketSkillBrief> out = new ArrayList<>(raw.size());
                    for (MarketSkillSummary s : raw) {
                        out.add(new MarketSkillBrief(s.name(), s.description(), s.version()));
                    }
                    out.sort(Comparator.comparing(MarketSkillBrief::name));
                    return out;
                });
    }

    @GetMapping("/{id}/skills/{name}")
    public Mono<MarketSkillDetail> getSkill(@PathVariable String id, @PathVariable String name) {
        return Mono.fromCallable(
                () -> {
                    if (name == null || name.isBlank()) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "skill name is required");
                    }
                    ClawMarketplace mp = requireRegistered(id);
                    MarketSkillContent content;
                    try {
                        content = mp.fetch(name);
                    } catch (RuntimeException e) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_GATEWAY,
                                "Marketplace fetch failed: " + e.getMessage(),
                                e);
                    }
                    if (content == null) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Skill '" + name + "' not found in marketplace '" + id + "'");
                    }
                    Map<String, String> resources =
                            content.resources() != null
                                    ? new LinkedHashMap<>(content.resources())
                                    : Map.of();
                    return new MarketSkillDetail(
                            content.name(), content.description(), content.markdown(), resources);
                });
    }

    // -----------------------------------------------------------------
    // 辅助方法
    // -----------------------------------------------------------------

    private ClawMarketplace requireRegistered(String id) {
        validateId(id);
        return registry.find(id)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Marketplace not registered: " + id));
    }

    private TestConnectionResult probe(String id, MarketplaceConfigEntry entry) {
        ClawMarketplace mp;
        try {
            mp = registry.build(id, entry);
        } catch (IllegalArgumentException e) {
            return new TestConnectionResult(false, e.getMessage(), null);
        } catch (RuntimeException e) {
            return new TestConnectionResult(
                    false, "Failed to build marketplace: " + e.getMessage(), null);
        }
        try {
            // 探测不需要完整列表；Nacos 仍可能产生额外分页请求，
            // 而 Git 会执行克隆，从而在一次调用中验证凭据
            // 及 skills/ 布局。
            List<MarketSkillSummary> sample = mp.list();
            int total = sample != null ? sample.size() : 0;
            return new TestConnectionResult(true, "Connected", total);
        } catch (RuntimeException e) {
            return new TestConnectionResult(false, e.getMessage(), null);
        } finally {
            try {
                mp.close();
            } catch (RuntimeException ignored) {
                // 探测实例用后即弃；释放临时资源失败不视为致命错误
            }
        }
    }

    private static void validateRequest(MarketplaceWriteRequest req, boolean requireId) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        if (requireId) {
            validateId(req.id());
        }
        if (req.type() == null || req.type().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type is required");
        }
        String type = req.type().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_TYPES.contains(type)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "unsupported marketplace type '"
                            + req.type()
                            + "', expected one of "
                            + SUPPORTED_TYPES);
        }
        Map<String, Object> props = req.properties();
        if ("git".equals(type)) {
            requireStringProp(props, "remoteUrl");
        } else if ("nacos".equals(type)) {
            requireStringProp(props, "serverAddr");
        }
    }

    private static void requireStringProp(Map<String, Object> props, String key) {
        if (props == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'" + key + "' is required");
        }
        Object v = props.get(key);
        if (v == null || v.toString().trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'" + key + "' is required");
        }
    }

    private static void validateId(String id) {
        if (id == null || id.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id is required");
        }
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "invalid marketplace id: '" + id + "' (allowed: letters, digits, ._-)");
        }
    }

    private static MarketplaceConfigEntry toConfigEntry(MarketplaceWriteRequest req) {
        MarketplaceConfigEntry entry = new MarketplaceConfigEntry();
        entry.setType(req.type().toLowerCase(Locale.ROOT));
        if (req.properties() != null) {
            for (Map.Entry<String, Object> e : req.properties().entrySet()) {
                if (e.getKey() == null || e.getKey().isBlank()) continue;
                entry.setProperty(e.getKey(), e.getValue());
            }
        }
        return entry;
    }

    private static MarketplaceSummary toSummary(String id, MarketplaceConfigEntry entry) {
        // 从返回的属性集合中剔除已知敏感字段，避免它们经 UI 往返。
        Map<String, Object> safeProps = new LinkedHashMap<>();
        if (entry.getProperties() != null) {
            for (Map.Entry<String, Object> e : entry.getProperties().entrySet()) {
                if (SECRET_KEYS.contains(e.getKey())) continue;
                safeProps.put(e.getKey(), e.getValue());
            }
        }
        return new MarketplaceSummary(id, entry.getType(), safeProps);
    }

    // -----------------------------------------------------------------
    // 数据传输对象
    // -----------------------------------------------------------------

    /** 创建／更新／测试端点的写入载荷。创建要求 {@code id}；更新时忽略它（以路径为准）；临时测试用它作标签。{@code properties} 携带类型专用字段：Git 使用 remoteUrl、branch；Nacos 使用 serverAddr、namespaceId、username、password、accessKey、secretKey。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MarketplaceWriteRequest(String id, String type, Map<String, Object> properties) {}

    /** 响应载荷：按敏感属性键列表过滤凭据。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MarketplaceSummary(String id, String type, Map<String, Object> properties) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TestConnectionResult(boolean ok, String message, Integer skillCount) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MarketSkillBrief(String name, String description, String version) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MarketSkillDetail(
            String name, String description, String markdown, Map<String, String> resources) {}
}
