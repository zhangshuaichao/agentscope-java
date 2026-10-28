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

package io.agentscope.core.a2a.server.executor.runner;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

@DisplayName("Agent Runner Tests")
class AgentRunnerTest {

    private AgentRequestOptions requestOptions;
    private ReActAgent.Builder mockBuilder;
    private ReActAgent mockAgent;
    private ReActAgentWithBuilderRunner runner;

    @BeforeEach
    void setUp() {
        requestOptions = new AgentRequestOptions();
        mockBuilder = mock(ReActAgent.Builder.class);
        mockAgent = mock(ReActAgent.class);
        when(mockBuilder.build()).thenReturn(mockAgent);
        runner = ReActAgentWithBuilderRunner.newInstance(mockBuilder);
    }

    @Test
    @DisplayName("Should set and get task ID")
    void testSetAndGetTaskId() {
        String taskId = "test-task-id";
        requestOptions.setTaskId(taskId);
        assertEquals(taskId, requestOptions.getTaskId());
    }

    @Test
    @DisplayName("Should set and get session ID")
    void testSetAndGetSessionId() {
        String sessionId = "test-session-id";
        requestOptions.setSessionId(sessionId);
        assertEquals(sessionId, requestOptions.getSessionId());
    }

    @Test
    @DisplayName("Should set and get user ID")
    void testSetAndGetUserId() {
        String userId = "test-user-id";
        requestOptions.setUserId(userId);
        assertEquals(userId, requestOptions.getUserId());
    }

    @Test
    @DisplayName("Should return null for unset fields")
    void testReturnNullForUnsetFields() {
        assertNull(requestOptions.getTaskId());
        assertNull(requestOptions.getSessionId());
        assertNull(requestOptions.getUserId());
    }

    @Test
    @DisplayName("Should handle empty string values")
    void testHandleEmptyStringValues() {
        requestOptions.setTaskId("");
        requestOptions.setSessionId("");
        requestOptions.setUserId("");

        assertEquals("", requestOptions.getTaskId());
        assertEquals("", requestOptions.getSessionId());
        assertEquals("", requestOptions.getUserId());
    }

    // ReActAgentWithBuilderRunner 测试

    @Test
    @DisplayName("Should create new instance with builder")
    void testCreateNewInstanceWithBuilder() {
        assertNotNull(runner);
    }

    @Test
    @DisplayName("Should build agent from builder")
    void testBuildAgentFromBuilder() {
        // 执行操作
        ReActAgent agent = runner.buildReActAgent();

        // 验证结果
        assertNotNull(agent);
        verify(mockBuilder, times(1)).build();
    }

    @Test
    @DisplayName("Should return agent name from built agent")
    void testGetAgentName() {
        // 给定条件
        String agentName = "Test Agent";
        when(mockAgent.getName()).thenReturn(agentName);

        // 执行操作
        String name = runner.getAgentName();

        // 验证结果
        assertEquals(agentName, name);
        verify(mockAgent, times(1)).getName();
    }

    @Test
    @DisplayName("Should return agent description from built agent")
    void testGetAgentDescription() {
        // 给定条件
        String agentDescription = "Test Agent Description";
        when(mockAgent.getDescription()).thenReturn(agentDescription);

        // 执行操作
        String description = runner.getAgentDescription();

        // 验证结果
        assertEquals(agentDescription, description);
        verify(mockAgent, times(1)).getDescription();
    }

    @Test
    @DisplayName("Should stream messages and cache agent")
    void testStreamMessagesAndCacheAgent() {
        // 给定条件
        String taskId = UUID.randomUUID().toString();
        requestOptions.setTaskId(taskId);

        List<Msg> messages = List.of(mock(Msg.class));

        Flux<AgentEvent> mockFlux = mock(Flux.class);
        when(mockAgent.streamEvents(messages)).thenReturn(mockFlux);
        when(mockFlux.doFinally(any())).thenReturn(mockFlux);

        // 执行操作
        Flux<AgentEvent> result = runner.streamEvents(messages, requestOptions);

        // 验证结果
        assertNotNull(result);
        verify(mockBuilder, times(1)).build();
        verify(mockAgent, times(1)).streamEvents(messages);
    }

    @Test
    @DisplayName("Should throw exception when agent already exists for task ID")
    void testThrowExceptionWhenAgentAlreadyExists() {
        // 给定条件
        String taskId = UUID.randomUUID().toString();
        requestOptions.setTaskId(taskId);

        List<Msg> messages = List.of(mock(Msg.class));

        Flux<AgentEvent> mockFlux = mock(Flux.class);
        when(mockAgent.streamEvents(messages)).thenReturn(mockFlux);

        // 首次调用填充缓存。
        runner.streamEvents(messages, requestOptions);

        // 执行并验证。
        assertThrows(
                IllegalStateException.class, () -> runner.streamEvents(messages, requestOptions));
    }

    @Test
    @DisplayName("Should remove agent from cache when stream completes")
    void testRemoveAgentFromCacheOnComplete() {
        // 给定条件
        String taskId = UUID.randomUUID().toString();
        requestOptions.setTaskId(taskId);

        List<Msg> messages = List.of(mock(Msg.class));

        // 设置模拟完成过程的 Flux。
        Flux<AgentEvent> mockFlux = Flux.empty();
        when(mockAgent.streamEvents(messages)).thenReturn(mockFlux);

        // 执行操作
        Flux<AgentEvent> result = runner.streamEvents(messages, requestOptions);

        // 订阅以触发 doFinally 块。
        result.subscribe();

        // 留出时间让响应式流完成。
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            fail("Test interrupted");
        }

        // 用相同 taskId 再次尝试流式调用，智能体已移除，应成功。
        Flux<AgentEvent> secondResult = runner.streamEvents(messages, requestOptions);
        assertNotNull(secondResult);
    }

    @Test
    @DisplayName("Should stop agent and remove from cache")
    void testStopAgentAndRemoveFromCache() {
        // 给定条件
        String taskId = UUID.randomUUID().toString();
        requestOptions.setTaskId(taskId);

        List<Msg> messages = List.of(mock(Msg.class));

        Flux<AgentEvent> mockFlux = mock(Flux.class);
        when(mockAgent.streamEvents(messages)).thenReturn(mockFlux);
        when(mockFlux.doFinally(any())).thenReturn(mockFlux);
        runner.streamEvents(messages, requestOptions);

        runner.stop(taskId);
        verify(mockAgent, times(1)).interrupt();

        // 用相同 taskId 再次尝试流式调用，智能体已移除，应成功。
        Flux<AgentEvent> result = runner.streamEvents(messages, requestOptions);
        assertNotNull(result);
    }

    @Test
    @DisplayName("Should handle stop for non-existent task ID")
    void testStopNonExistentTaskId() {
        assertDoesNotThrow(() -> runner.stop("non-existent-task-id"));
    }
}
