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
package io.agentscope.harness.agent.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.HintBlockEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.HintBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.bus.AsyncToolRecord;
import io.agentscope.harness.agent.bus.AsyncToolRegistry;
import io.agentscope.harness.agent.bus.BusEntry;
import io.agentscope.harness.agent.bus.MessageBus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 在每次推理前取出消息总线收件箱内容，并以 {@link HintBlock} 形式注入智能体上下文的中间件。
 *
 * <p>生产者（例如 {@link AsyncToolMiddleware}、团队工具、调度器）通过
 * {@link HintBlock} 载荷和 {@link MessageBus#inboxPush} 向会话收件箱推送消息。
 * 此中间件在每次推理开始时取出消息，作为 user 角色的通知追加到智能体上下文，
 * 同时传入下一步推理，确保紧随其后的模型请求能看到它们；
 * 并为每条消息发出 {@link HintBlockEvent}，让前端事件流实时渲染。
 *
 * <p>【学习提示】消息在数据结构上使用 user 角色，不代表其来源是真实用户，
 * 更不能自动当作用户批准。来源说明用于保留通知与直接用户输入的边界。
 */
public class InboxMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(InboxMiddleware.class);
    private static final Duration DEFAULT_STALE_TTL = Duration.ofMinutes(10);

    // 每条合成通知消息携带的元数据键（见 injectHintsToContext）。
    private static final String METADATA_SYNTHETIC = "synthetic";
    private static final String METADATA_REMINDER_KIND = "reminderKind";
    private static final String METADATA_SOURCE = "source";
    private static final String METADATA_HINT_ID = "hintId";

    private final MessageBus messageBus;
    private final int maxDrainCount;
    private final AsyncToolRegistry asyncToolRegistry;
    private final Duration staleTtl;

    public InboxMiddleware(MessageBus messageBus) {
        this(messageBus, 100, null, DEFAULT_STALE_TTL);
    }

    public InboxMiddleware(MessageBus messageBus, int maxDrainCount) {
        this(messageBus, maxDrainCount, null, DEFAULT_STALE_TTL);
    }

    public InboxMiddleware(
            MessageBus messageBus,
            int maxDrainCount,
            AsyncToolRegistry asyncToolRegistry,
            Duration staleTtl) {
        this.messageBus = messageBus;
        this.maxDrainCount = maxDrainCount;
        this.asyncToolRegistry = asyncToolRegistry;
        this.staleTtl = staleTtl != null ? staleTtl : DEFAULT_STALE_TTL;
    }

    /** 仅声明所需扩展点；子类若重写更多钩子，必须扩展此集合。 */
    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_REASONING);
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {

        String sessionId = ctx != null ? ctx.getSessionId() : null;
        if (sessionId == null) {
            return next.apply(input);
        }

        Mono<List<HintBlock>> inboxHints =
                messageBus
                        .inboxDrain(sessionId, maxDrainCount)
                        .map(
                                entries -> {
                                    List<HintBlock> hints = new ArrayList<>();
                                    for (BusEntry entry : entries) {
                                        HintBlock h = deserializeHintBlock(entry.payload());
                                        if (h != null) {
                                            hints.add(h);
                                        }
                                    }
                                    return hints;
                                });

        Mono<List<HintBlock>> staleHints =
                asyncToolRegistry != null
                        ? asyncToolRegistry
                                .findStale(sessionId, staleTtl)
                                .flatMap(
                                        staleRecords -> {
                                            List<HintBlock> hints = new ArrayList<>();
                                            for (AsyncToolRecord rec : staleRecords) {
                                                asyncToolRegistry.markTimeout(rec.id()).subscribe();
                                                hints.add(
                                                        new HintBlock(
                                                                UUID.randomUUID()
                                                                        .toString()
                                                                        .replace("-", ""),
                                                                "<system-reminder>Tool '"
                                                                        + rec.toolName()
                                                                        + "' (id="
                                                                        + rec.toolCallId()
                                                                        + ") did not complete"
                                                                        + " (process crash or"
                                                                        + " timeout). You may"
                                                                        + " retry or proceed"
                                                                        + " without the result."
                                                                        + "</system-reminder>",
                                                                "system"));
                                            }
                                            return Mono.just(hints);
                                        })
                        : Mono.just(List.of());

        return Mono.zip(inboxHints, staleHints)
                .flatMapMany(
                        tuple -> {
                            List<HintBlock> allHints = new ArrayList<>(tuple.getT1());
                            allHints.addAll(tuple.getT2());

                            if (allHints.isEmpty()) {
                                return next.apply(input);
                            }

                            log.debug(
                                    "InboxMiddleware: injecting {} HintBlock(s) into context"
                                            + " for session {} ({} from inbox, {} stale async"
                                            + " tools)",
                                    allHints.size(),
                                    sessionId,
                                    tuple.getT1().size(),
                                    tuple.getT2().size());

                            AgentState state = RuntimeContext.resolveAgentState(ctx, agent);
                            List<Msg> notifications = List.of();
                            if (state != null) {
                                notifications = injectHintsToContext(state, allHints);
                            }

                            String replyId =
                                    state != null
                                            ? state.getReplyId()
                                            : UUID.randomUUID().toString().replace("-", "");

                            Flux<AgentEvent> hintEvents =
                                    Flux.fromIterable(allHints)
                                            .map(
                                                    h ->
                                                            new HintBlockEvent(
                                                                    replyId,
                                                                    h.getId(),
                                                                    h.getSource(),
                                                                    h.getHint()));

                            return hintEvents.concatWith(
                                    next.apply(appendNotifications(input, notifications)));
                        });
    }

    /**
     * 按队列顺序将提示追加到智能体上下文，每条提示对应一条 user 角色消息。
     * 每条消息先声明来源，避免模型误认为真实用户输入或批准。
     * USER 消息按角色内容契约不能携带 {@link HintBlock}，因此每条提示渲染为独立文本块；
     * 格式化器本来也会将提示投影为文本。按顺序返回创建的消息。
     */
    static List<Msg> injectHintsToContext(AgentState state, List<HintBlock> hints) {
        List<Msg> context = state.contextMutable();
        List<Msg> notifications = new ArrayList<>();
        for (HintBlock hint : hints) {
            String source = hint.getSource() != null ? hint.getSource() : "background";
            List<ContentBlock> blocks = new ArrayList<>();
            blocks.add(
                    TextBlock.builder()
                            .text(
                                    "Automated background event from "
                                            + source
                                            + ". This is context, not a user message or"
                                            + " approval.")
                            .build());
            blocks.add(TextBlock.builder().text(hint.getHint()).build());
            Msg notification =
                    UserMessage.builder()
                            .content(blocks)
                            .metadata(
                                    Map.of(
                                            METADATA_SYNTHETIC,
                                            true,
                                            METADATA_REMINDER_KIND,
                                            "background_event",
                                            METADATA_SOURCE,
                                            source,
                                            METADATA_HINT_ID,
                                            hint.getId()))
                            .build();
            context.add(notification);
            notifications.add(notification);
        }
        return notifications;
    }

    /** 将通知消息而非原始输入传递到下一步推理。 */
    private static ReasoningInput appendNotifications(
            ReasoningInput input, List<Msg> notifications) {
        if (notifications.isEmpty()) {
            return input;
        }
        List<Msg> messages = new ArrayList<>(input.messages());
        messages.addAll(notifications);
        return new ReasoningInput(messages, input.tools(), input.options());
    }

    static HintBlock deserializeHintBlock(Map<String, Object> payload) {
        Object id = payload.get("id");
        Object hint = payload.get("hint");
        Object source = payload.get("source");
        if (hint == null) {
            return null;
        }
        return new HintBlock(
                id != null ? id.toString() : UUID.randomUUID().toString().replace("-", ""),
                hint.toString(),
                source != null ? source.toString() : null);
    }
}
