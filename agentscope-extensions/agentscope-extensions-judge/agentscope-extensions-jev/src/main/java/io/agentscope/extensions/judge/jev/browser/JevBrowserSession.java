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

package io.agentscope.extensions.judge.jev.browser;

import io.agentscope.core.agent.RuntimeContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/**
 * 宿主拥有且独占租用的只读页面。工具调用之间绝不能共享会话。
 */
public interface JevBrowserSession {
    record Scope(String user, String session, String tab) {
        public Scope {
            required(user);
            required(session);
            required(tab);
        }

        public boolean matches(RuntimeContext context) {
            return user.equals(context.getUserId()) && session.equals(context.getSessionId());
        }
    }

    record Link(String id, String label, String href) {
        public Link {
            required(id);
            required(label);
            required(href);
            if (!id.matches("[a-zA-Z0-9_-]{1,64}")
                    || id.equals("none")
                    || label.length() > 1000
                    || href.length() > 2000)
                throw new IllegalArgumentException("invalid observed link");
        }
    }

    record Snapshot(
            Scope scope,
            String version,
            String url,
            String title,
            String text,
            List<Link> links,
            boolean scrollUp,
            boolean scrollDown,
            int omitted) {
        public Snapshot {
            Objects.requireNonNull(scope);
            required(version);
            required(url);
            Objects.requireNonNull(title);
            Objects.requireNonNull(text);
            links = List.copyOf(links);
            if (links.size() > 128
                    || links.stream().map(Link::id).distinct().count() != links.size()
                    || text.length() > 12000
                    || title.length() > 1000
                    || url.length() > 2000
                    || omitted < 0) throw new IllegalArgumentException("invalid snapshot bounds");
        }
    }

    enum Operation {
        CLICK,
        SCROLL_UP,
        SCROLL_DOWN,
        WAIT,
        DONE,
        BLOCKED
    }

    record Action(Operation operation, String target) {
        public Action {
            Objects.requireNonNull(operation);
            if ((operation == Operation.CLICK) != (target != null))
                throw new IllegalArgumentException("target only for CLICK");
        }
    }

    /**
     * 适配器在任何输入前消耗此许可，包括失败或过期尝试。
     */
    final class Permit {
        private final AtomicBoolean used = new AtomicBoolean();

        public boolean consume() {
            return used.compareAndSet(false, true);
        }
    }

    enum Outcome {
        APPLIED,
        STALE,
        REJECTED,
        UNKNOWN
    }

    record Receipt(Outcome outcome, String reason) {
        public Receipt {
            Objects.requireNonNull(outcome);
            required(reason);
        }
    }

    record Request(RuntimeContext context, String goal) {}

    /**
     * 工厂必须非阻塞，返回全新的独占会话，并落实宿主授权。
     */
    record Source(Function<Request, JevBrowserSession> open, Verifier verifier) {
        public Source {
            Objects.requireNonNull(open);
            Objects.requireNonNull(verifier);
        }
    }

    @FunctionalInterface
    interface Verifier {
        Mono<Verification> verify(String goal, Snapshot snapshot);
    }

    record Verification(
            String goalDigest, String pageVersion, boolean passed, List<String> evidence) {
        public Verification {
            required(goalDigest);
            required(pageVersion);
            evidence = List.copyOf(evidence);
            if (evidence.size() > 32
                    || evidence.stream().anyMatch(s -> s == null || s.length() > 2000))
                throw new IllegalArgumentException("verification evidence limit");
        }
    }

    Scope scope();

    Mono<Snapshot> observe();

    /**
     * 必须在输入前原子地重新检查页面/目标防护条件并消耗许可。
     * STALE 保证没有发送任何输入；发送之后发生的错误必须为 UNKNOWN。
     */
    Mono<Receipt> execute(Permit permit, Snapshot expected, Action action);

    /**
     * 立即禁止后续调度；清理可异步完成。此操作幂等。
     */
    Mono<Void> close();

    static String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void required(String value) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("nonblank value required");
    }
}
