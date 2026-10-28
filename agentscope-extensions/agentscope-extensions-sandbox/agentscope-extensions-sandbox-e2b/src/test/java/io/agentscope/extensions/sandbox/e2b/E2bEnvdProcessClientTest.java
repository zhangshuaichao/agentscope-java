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
package io.agentscope.extensions.sandbox.e2b;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import io.agentscope.harness.agent.sandbox.SandboxErrorCode;
import io.agentscope.harness.agent.sandbox.SandboxException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Call;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class E2bEnvdProcessClientTest {

    @Test
    void jsonCodecUsesConnectJsonAndJsonPayload() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));

        byte[] envelope = client.encodeStartRequestEnvelope("echo hello", "/workspace");

        assertEquals(MediaType.get("application/connect+json"), client.connectMediaType());
        assertEquals(0x00, envelope[0] & 0xFF);
        int len = ByteBuffer.wrap(envelope, 1, 4).order(ByteOrder.BIG_ENDIAN).getInt();
        byte[] payload = java.util.Arrays.copyOfRange(envelope, 5, 5 + len);
        String json = new String(payload, StandardCharsets.UTF_8);
        assertEquals(
                "{\"process\":{\"cmd\":\"/bin/bash\",\"args\":[\"-l\",\"-c\","
                        + "\"echo hello\"],\"cwd\":\"/workspace\"},\"stdin\":false}",
                json);
    }

    @Test
    void protoCodecKeepsBinaryPayload() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.PROTO));

        byte[] envelope = client.encodeStartRequestEnvelope("echo hello", "/workspace");
        DynamicMessage expected = client.buildStartRequest("echo hello", "/workspace");

        assertEquals(MediaType.get("application/connect+proto"), client.connectMediaType());
        int len = ByteBuffer.wrap(envelope, 1, 4).order(ByteOrder.BIG_ENDIAN).getInt();
        byte[] payload = java.util.Arrays.copyOfRange(envelope, 5, 5 + len);
        assertArrayEquals(expected.toByteArray(), payload);
    }

    @Test
    void jsonCodecParsesStartResponseFrame() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        DynamicMessage response = dataResponse(client, "hello\n", null);
        String json = responseJson(base64("hello\n"), null, null);

        DynamicMessage parsed =
                client.parseStartResponseFrame(json.getBytes(StandardCharsets.UTF_8));

        assertEquals(response, parsed);
    }

    @Test
    void jsonCodecReturnsEmptyMessageForNullOrMissingEvent() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));

        assertTrue(
                client.parseStartResponseFrame("null".getBytes(StandardCharsets.UTF_8))
                        .getAllFields()
                        .isEmpty());
        assertTrue(
                client.parseStartResponseFrame("{}".getBytes(StandardCharsets.UTF_8))
                        .getAllFields()
                        .isEmpty());
        assertTrue(
                client.parseStartResponseFrame("{\"event\":null}".getBytes(StandardCharsets.UTF_8))
                        .getAllFields()
                        .isEmpty());
    }

    @Test
    void jsonCodecSkipsMalformedBase64AndKeepsStreaming() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit =
                drainStartStream(
                        client,
                        connectFrames(
                                responseJson("%%%bad-base64%%%", null, null),
                                responseJson(null, base64("stderr\n"), null),
                                responseJson(null, null, 7)),
                        stdout,
                        stderr);

        assertEquals(7, exit);
        assertEquals("", stdout.toString(StandardCharsets.UTF_8));
        assertEquals("stderr\n", stderr.toString(StandardCharsets.UTF_8));
    }

    @Test
    void jsonCodecRejectsEofBeforeEnd() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        IOException exception =
                assertThrows(
                        IOException.class,
                        () ->
                                drainStartStream(
                                        client,
                                        connectFrames(
                                                responseJson(base64("hello\n"), null, null),
                                                responseJson(null, base64("warn\n"), null)),
                                        stdout,
                                        stderr));

        assertTrue(exception.getMessage().contains("before receiving a process exit code"));
        assertEquals("hello\n", stdout.toString(StandardCharsets.UTF_8));
        assertEquals("warn\n", stderr.toString(StandardCharsets.UTF_8));
    }

    @Test
    void jsonCodecRejectsTruncatedFrameLengthBeforeEnd() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        byte[] truncatedLength = new byte[] {0x00, 0x00, 0x00};

        IOException exception =
                assertThrows(
                        IOException.class,
                        () ->
                                drainStartStream(
                                        client,
                                        truncatedLength,
                                        new ByteArrayOutputStream(),
                                        new ByteArrayOutputStream()));

        assertTrue(exception.getMessage().contains("before receiving a process exit code"));
    }

    @Test
    void jsonCodecRejectsTruncatedFramePayloadBeforeEnd() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        ByteBuffer truncatedPayload = ByteBuffer.allocate(7).order(ByteOrder.BIG_ENDIAN);
        truncatedPayload.put((byte) 0x00);
        truncatedPayload.putInt(4);
        truncatedPayload.put((byte) '{');
        truncatedPayload.put((byte) '}');

        IOException exception =
                assertThrows(
                        IOException.class,
                        () ->
                                drainStartStream(
                                        client,
                                        truncatedPayload.array(),
                                        new ByteArrayOutputStream(),
                                        new ByteArrayOutputStream()));

        assertTrue(exception.getMessage().contains("before receiving a process exit code"));
    }

    @Test
    void jsonCodecReturnsEmptyOutputsWhenOnlyEndPresent() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit =
                drainStartStream(client, connectFrame(responseJson(null, null, 5)), stdout, stderr);

        assertEquals(5, exit);
        assertEquals("", stdout.toString(StandardCharsets.UTF_8));
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));
    }

    @Test
    void jsonCodecPreservesZeroExitCode() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit =
                drainStartStream(client, connectFrame(responseJson(null, null, 0)), stdout, stderr);

        assertEquals(0, exit);
    }

    // Connect 协议：最终信封的 flags 位 1 表示 EndStreamMessage JSON。
    // Python SDK ServerStreamParser：data 含 error 时抛出 make_error(data["error"])
    // https://connectrpc.com/docs/protocol/#error-end-stream
    // https://github.com/e2b-dev/e2b/blob/main/packages/python-sdk/e2b_connect/client.py#L230-L238
    @Test
    void endStreamResponseWithErrorThrows() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        String errorJson = "{\"error\":{\"code\":\"internal\",\"message\":\"process crash\"}}";
        byte[] frame = endStreamFrame(errorJson);

        SandboxException.SandboxRuntimeException ex =
                assertThrows(
                        SandboxException.SandboxRuntimeException.class,
                        () ->
                                drainStartStream(
                                        client,
                                        frame,
                                        new ByteArrayOutputStream(),
                                        new ByteArrayOutputStream()));
        assertEquals(SandboxErrorCode.WORKSPACE_START_ERROR, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("internal"));
        assertTrue(ex.getMessage().contains("process crash"));
    }

    // 对应官方 connectrpc ConnectEnvelopeReader.handle_end_message：
    //   error = end_stream_message.get("error")
    //   if error: raise
    // 空 error 对象在 Python 中为假，按成功结束处理，
    // 不抛 SandboxRuntimeException；未收到 EndEvent 时，后续排空以
    // IOException 失败，与没有前序事件的 "{}" 尾帧相同。
    @Test
    void endStreamResponseWithEmptyErrorObjectBreaksCleanly() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        byte[] frame = endStreamFrame("{\"error\":{}}");

        IOException ex =
                assertThrows(
                        IOException.class,
                        () ->
                                drainStartStream(
                                        client,
                                        frame,
                                        new ByteArrayOutputStream(),
                                        new ByteArrayOutputStream()));
        assertTrue(ex.getMessage().contains("before receiving a process exit code"));
    }

    // 非空 error 即使缺少 code，在 Python 的 if error: raise 中仍为真。
    @Test
    void endStreamResponseWithMessageOnlyErrorThrows() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        byte[] frame = endStreamFrame("{\"error\":{\"message\":\"boom\"}}");

        SandboxException.SandboxRuntimeException ex =
                assertThrows(
                        SandboxException.SandboxRuntimeException.class,
                        () ->
                                drainStartStream(
                                        client,
                                        frame,
                                        new ByteArrayOutputStream(),
                                        new ByteArrayOutputStream()));
        assertEquals(SandboxErrorCode.WORKSPACE_START_ERROR, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("boom"));
    }

    // 非对象 error 载荷非空时也可判真。
    @Test
    void endStreamResponseWithStringErrorThrows() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        byte[] frame = endStreamFrame("{\"error\":\"boom\"}");

        SandboxException.SandboxRuntimeException ex =
                assertThrows(
                        SandboxException.SandboxRuntimeException.class,
                        () ->
                                drainStartStream(
                                        client,
                                        frame,
                                        new ByteArrayOutputStream(),
                                        new ByteArrayOutputStream()));
        assertEquals(SandboxErrorCode.WORKSPACE_START_ERROR, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("boom"));
    }

    // 损坏的结束尾帧不可信，不像可跳过的数据帧，
    // 应以 IOException 传播，而不是静默吞掉。
    @Test
    void corruptEndStreamPayloadThrowsIOException() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        byte[] frame = endStreamFrame("not-json{{{");

        assertThrows(
                IOException.class,
                () ->
                        drainStartStream(
                                client,
                                frame,
                                new ByteArrayOutputStream(),
                                new ByteArrayOutputStream()));
    }

    // Python 中 0、false、空字符串等假值 error 载荷
    // 正常结束流，不抛错，对应 connectrpc 的 if error: raise。
    @Test
    void endStreamResponseWithFalsyErrorBreaksCleanly() throws Exception {
        for (String payload :
                new String[] {"{\"error\":0}", "{\"error\":false}", "{\"error\":\"\"}"}) {
            E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
            byte[] frame = endStreamFrame(payload);

            IOException ex =
                    assertThrows(
                            IOException.class,
                            () ->
                                    drainStartStream(
                                            client,
                                            frame,
                                            new ByteArrayOutputStream(),
                                            new ByteArrayOutputStream()),
                            "payload: " + payload);
            // 先正常结束流，再报告缺少退出事件，绝不能变为
            // SandboxRuntimeException 或 NumberFormatException。
            assertTrue(ex.getMessage().contains("before receiving a process exit code"));
        }
    }

    // Connect 协议：成功 EndStreamResponse 为 "{}"，无错误和元数据。
    // Python SDK：end_stream 无 error 时返回，停止迭代。
    @Test
    void endStreamResponseWithoutErrorBreaksCleanly() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        byte[] frame =
                concatFrames(
                        connectFrame(responseJson(base64("hello"), null, null)),
                        connectFrame(endEventJson(0, null)),
                        endStreamFrame("{}"));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = drainStartStream(client, frame, stdout, stderr);

        assertEquals(0, exit);
        assertEquals("hello", stdout.toString(StandardCharsets.UTF_8));
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));
    }

    // EndEvent.exit_code 是 proto3 隐式存在性的标量；
    // EndEvent 消息本身存在时，省略值为 0。
    @Test
    void emptyEndEventReadsZeroJson() throws Exception {
        for (String json :
                new String[] {
                    "{\"event\":{\"end\":{}}}", "{\"event\":{\"end\":{\"error\":\"\"}}}"
                }) {
            E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
            byte[] frame = concatFrames(connectFrame(json), endStreamFrame("{}"));
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            int exit = drainStartStream(client, frame, stdout, stderr);

            assertEquals(0, exit);
            assertEquals("", stdout.toString(StandardCharsets.UTF_8));
            assertEquals("", stderr.toString(StandardCharsets.UTF_8));
        }
    }

    // 对应 PROTO 传输：exit_code=0 与无字段 EndEvent 字节相同，
    // 因为 proto3 省略默认标量，二者无法区分；官方 Python SDK
    // 直接读取 end.exit_code，也因此返回 0。
    @Test
    void emptyEndEventReadsZeroProto() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.PROTO));
        DynamicMessage endResp = endEventResponse(client, null, null);
        byte[] frame = concatFrames(connectFrame(endResp.toByteArray()), endStreamFrame("{}"));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = drainStartStream(client, frame, stdout, stderr);

        assertEquals(0, exit);
        assertEquals("", stdout.toString(StandardCharsets.UTF_8));
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));
    }

    @Test
    void exitCodeNonZero() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        byte[] frame = concatFrames(connectFrame(endEventJson(42, null)), endStreamFrame("{}"));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = drainStartStream(client, frame, stdout, stderr);

        assertEquals(42, exit);
        assertEquals("", stdout.toString(StandardCharsets.UTF_8));
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));
    }

    // cmd.Wait() 返回 ExitError 时填充 EndEvent.error，
    // 对应非零退出码或信号；Go 处理器设置 Error = errMsg。
    // https://github.com/e2b-dev/infra/blob/main/packages/envd/internal/services/process/handler/handler.go
    // Python SDK 通过 CommandResult(error=event.event.end.error) 保留错误
    // https://github.com/e2b-dev/e2b/blob/main/packages/python-sdk/e2b/sandbox_sync/commands/command_handle.py#L115
    // 模拟真实 envd 观察到的信号终止结构：
    // exit_code=-1 且 EndEvent.error="signal: killed"。
    @Test
    void endEventErrorWrittenToStderr() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        byte[] frame =
                concatFrames(
                        connectFrame(endEventJson(-1, "signal: killed")), endStreamFrame("{}"));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = drainStartStream(client, frame, stdout, stderr);

        assertEquals(-1, exit);
        assertEquals("", stdout.toString(StandardCharsets.UTF_8));
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("signal: killed"));
    }

    // 对应 Python/JS SDK wait()：exit_code 决定命令状态，
    // 即使省略退出码默认 0，EndEvent.error 仍作为诊断 stderr 保留。
    @Test
    void endEventErrorWithDefaultZeroKeepsSuccessAndStderr() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        byte[] frame =
                concatFrames(
                        connectFrame("{\"event\":{\"end\":{\"error\":\"wait failed\"}}}"),
                        endStreamFrame("{}"));
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exit = drainStartStream(client, frame, new ByteArrayOutputStream(), stderr);

        assertEquals(0, exit);
        assertEquals("wait failed", stderr.toString(StandardCharsets.UTF_8));
    }

    // EndEvent.error 与此前没有尾换行的 stderr 分开，
    // 避免 "partial" 与 "exit status 1" 粘连。
    @Test
    void endEventErrorSeparatedFromStderr() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        byte[] frame =
                concatFrames(
                        connectFrame(responseJson(null, base64("partial"), null)),
                        connectFrame(endEventJson(1, "exit status 1")),
                        endStreamFrame("{}"));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = drainStartStream(client, frame, stdout, stderr);

        assertEquals(1, exit);
        assertEquals("partial\nexit status 1", stderr.toString(StandardCharsets.UTF_8));
    }

    // protojson 也接受原始 proto 字段名：
    // {"end":{"exit_code":42}} 与 {"end":{"exitCode":42}} 解码相同。
    @Test
    void exitCodeSnakeCaseFieldName() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        byte[] frame =
                concatFrames(
                        connectFrame("{\"event\":{\"end\":{\"exit_code\":42}}}"),
                        endStreamFrame("{}"));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = drainStartStream(client, frame, stdout, stderr);

        assertEquals(42, exit);
    }

    // Connect 协议：flags 位 0 表示压缩，本客户端不支持。
    // https://connectrpc.com/docs/protocol/#streaming-rpcs
    @Test
    void compressedFrameThrowsIOException() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        byte[] frame = envelope(0x01, "{}".getBytes(StandardCharsets.UTF_8));

        IOException ex =
                assertThrows(
                        IOException.class,
                        () ->
                                drainStartStream(
                                        client,
                                        frame,
                                        new ByteArrayOutputStream(),
                                        new ByteArrayOutputStream()));
        assertTrue(ex.getMessage().contains("Compressed"));
    }

    // Connect 协议：flags 位 2～7 保留，未理解时应跳过。
    // https://connectrpc.com/docs/protocol/#streaming-rpcs
    @Test
    void reservedFlagsFrameIsSkipped() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        byte[] frame =
                concatFrames(
                        envelope(0x04, "ignored".getBytes(StandardCharsets.UTF_8)),
                        connectFrame(endEventJson(3, null)),
                        endStreamFrame("{}"));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = drainStartStream(client, frame, stdout, stderr);

        assertEquals(3, exit);
    }

    // ---- PROTO 编解码测试 ----

    // 仅数据帧没有退出码，应拒绝该流，对应 PROTO 版本的
    // jsonCodecRejectsEofBeforeEnd。
    @Test
    void protoCodecDrainStartStreamRejectsMissingEnd() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.PROTO));
        DynamicMessage dataResp = dataResponse(client, "hello\n", null);
        byte[] frame = concatFrames(connectFrame(dataResp.toByteArray()), endStreamFrame("{}"));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        IOException ex =
                assertThrows(
                        IOException.class, () -> drainStartStream(client, frame, stdout, stderr));
        assertTrue(ex.getMessage().contains("before receiving a process exit code"));
        assertEquals("hello\n", stdout.toString(StandardCharsets.UTF_8));
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));
    }

    @Test
    void protoCodecDrainStartStreamWithEndEvent() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.PROTO));
        DynamicMessage dataResp = dataResponse(client, null, "err\n");
        DynamicMessage endResp = endEventResponse(client, 42, "oops");
        byte[] frame =
                concatFrames(
                        connectFrame(dataResp.toByteArray()),
                        connectFrame(endResp.toByteArray()),
                        endStreamFrame("{}"));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = drainStartStream(client, frame, stdout, stderr);

        assertEquals(42, exit);
        assertEquals("", stdout.toString(StandardCharsets.UTF_8));
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("oops"));
    }

    @Test
    void protoCodecDrainStartStreamWithEndOnly() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.PROTO));
        DynamicMessage endResp = endEventResponse(client, 7, null);
        byte[] frame = concatFrames(connectFrame(endResp.toByteArray()), endStreamFrame("{}"));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = drainStartStream(client, frame, stdout, stderr);

        assertEquals(7, exit);
        assertEquals("", stdout.toString(StandardCharsets.UTF_8));
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));
    }

    // ---- drainStartStream 边界情况 ----

    @Test
    void illegalFrameLengthThrowsIOException() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        byte[] frame = new byte[5];
        frame[0] = 0x00;
        ByteBuffer.wrap(frame, 1, 4).order(ByteOrder.BIG_ENDIAN).putInt(64 * 1024 * 1024 + 1);

        IOException ex =
                assertThrows(
                        IOException.class,
                        () ->
                                drainStartStream(
                                        client,
                                        frame,
                                        new ByteArrayOutputStream(),
                                        new ByteArrayOutputStream()));
        assertTrue(ex.getMessage().contains("Invalid connect frame length"));
    }

    // Connect Message-Length 无符号值 0x80000005 超出有符号 int 范围，
    // 读为负数后必须拒绝；旧 & 0x7FFFFFFF 掩码会将其变为 5，导致流错位。
    @Test
    void highBitFrameLengthThrowsIOException() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        byte[] frame = new byte[5];
        frame[0] = 0x00;
        ByteBuffer.wrap(frame, 1, 4).order(ByteOrder.BIG_ENDIAN).putInt(0x80000005);

        IOException ex =
                assertThrows(
                        IOException.class,
                        () ->
                                drainStartStream(
                                        client,
                                        frame,
                                        new ByteArrayOutputStream(),
                                        new ByteArrayOutputStream()));
        assertTrue(ex.getMessage().contains("Invalid connect frame length"));
    }

    // ---- parseJsonStartResponse 边界情况 ----

    @Test
    void jsonCodecRejectsInvalidExplicitExitCode() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        for (String value :
                new String[] {"\"abc\"", "2147483648", "-2147483649", "1.5", "true", "[]"}) {
            String json = "{\"event\":{\"end\":{\"exitCode\":" + value + "}}}";
            assertThrows(
                    IOException.class,
                    () -> client.parseStartResponseFrame(json.getBytes(StandardCharsets.UTF_8)),
                    "exitCode: " + value);

            byte[] frame = concatFrames(connectFrame(json), endStreamFrame("{}"));
            IOException ex =
                    assertThrows(
                            IOException.class,
                            () ->
                                    drainStartStream(
                                            client,
                                            frame,
                                            new ByteArrayOutputStream(),
                                            new ByteArrayOutputStream()),
                            "exitCode: " + value);
            assertTrue(ex.getMessage().contains("Invalid EndEvent exit_code"));
        }
    }

    // ProtoJSON 接受 JSON 数字、十进制字符串及小数部分为零的 int32；
    // 官方 Python/JS SDK 也通过 protobuf 运行时共享此解码行为。
    @Test
    void jsonCodecAcceptsProtoJsonInt32ExitCodeForms() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        for (String value : new String[] {"42", "42.0", "\"42\"", "-7", "0"}) {
            String json = "{\"event\":{\"end\":{\"exitCode\":" + value + "}}}";
            byte[] frame = concatFrames(connectFrame(json), endStreamFrame("{}"));
            int exit =
                    drainStartStream(
                            client,
                            frame,
                            new ByteArrayOutputStream(),
                            new ByteArrayOutputStream());
            assertEquals(
                    new java.math.BigDecimal(value.replace("\"", "")).intValueExact(),
                    exit,
                    "exitCode: " + value);
        }
    }

    @Test
    void jsonCodecRejectsNonObjectEndEvent() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        for (String value : new String[] {"\"bad\"", "42", "true", "[]", "1.5"}) {
            String json = "{\"event\":{\"end\":" + value + "}}";
            assertThrows(
                    IOException.class,
                    () -> client.parseStartResponseFrame(json.getBytes(StandardCharsets.UTF_8)),
                    "end: " + value);

            byte[] frame = concatFrames(connectFrame(json), endStreamFrame("{}"));
            IOException ex =
                    assertThrows(
                            IOException.class,
                            () ->
                                    drainStartStream(
                                            client,
                                            frame,
                                            new ByteArrayOutputStream(),
                                            new ByteArrayOutputStream()),
                            "end: " + value);
            assertTrue(ex.getMessage().contains("Invalid EndEvent"));
        }
    }

    @Test
    void jsonCodecNonTextualErrorIsIgnored() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        String json = "{\"event\":{\"end\":{\"exitCode\":1,\"error\":123}}}";
        byte[] frame = concatFrames(connectFrame(json), endStreamFrame("{}"));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = drainStartStream(client, frame, stdout, stderr);

        assertEquals(1, exit);
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));
    }

    // 解析失败现在中止流而非跳过，因此无效 EndEvent 后
    // 即使跟着有效事件，也不得报告成功。
    @Test
    void jsonCodecMalformedFrameAbortsStream() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        byte[] frame =
                concatFrames(
                        connectFrame("{\"event\":{\"end\":{\"exitCode\":1.5}}}"),
                        connectFrame("{\"event\":{\"end\":{\"exitCode\":0}}}"),
                        endStreamFrame("{}"));

        assertThrows(
                IOException.class,
                () ->
                        drainStartStream(
                                client,
                                frame,
                                new ByteArrayOutputStream(),
                                new ByteArrayOutputStream()));
    }

    private static DynamicMessage dataResponse(
            E2bEnvdProcessClient client, String stdout, String stderr) {
        Descriptors.FileDescriptor fd = client.fileDescriptor();
        Descriptors.Descriptor processEventDesc = fd.findMessageTypeByName("ProcessEvent");
        Descriptors.Descriptor dataDesc = processEventDesc.findNestedTypeByName("DataEvent");

        DynamicMessage data =
                DynamicMessage.newBuilder(dataDesc)
                        .setField(
                                stdout != null
                                        ? dataDesc.findFieldByName("stdout")
                                        : dataDesc.findFieldByName("stderr"),
                                ByteString.copyFromUtf8(stdout != null ? stdout : stderr))
                        .build();
        DynamicMessage event =
                DynamicMessage.newBuilder(processEventDesc)
                        .setField(processEventDesc.findFieldByName("data"), data)
                        .build();
        Descriptors.Descriptor startResponseDesc = fd.findMessageTypeByName("StartResponse");
        return DynamicMessage.newBuilder(startResponseDesc)
                .setField(startResponseDesc.findFieldByName("event"), event)
                .build();
    }

    private static DynamicMessage endEventResponse(
            E2bEnvdProcessClient client, Integer exitCode, String error) {
        Descriptors.FileDescriptor fd = client.fileDescriptor();
        Descriptors.Descriptor processEventDesc = fd.findMessageTypeByName("ProcessEvent");
        Descriptors.Descriptor endDesc = processEventDesc.findNestedTypeByName("EndEvent");

        DynamicMessage.Builder endBuilder = DynamicMessage.newBuilder(endDesc);
        if (exitCode != null) {
            endBuilder.setField(endDesc.findFieldByName("exit_code"), exitCode);
        }
        if (error != null) {
            endBuilder.setField(endDesc.findFieldByName("error"), error);
        }
        DynamicMessage end = endBuilder.build();

        DynamicMessage event =
                DynamicMessage.newBuilder(processEventDesc)
                        .setField(processEventDesc.findFieldByName("end"), end)
                        .build();
        Descriptors.Descriptor startResponseDesc = fd.findMessageTypeByName("StartResponse");
        return DynamicMessage.newBuilder(startResponseDesc)
                .setField(startResponseDesc.findFieldByName("event"), event)
                .build();
    }

    // ---- 文件系统 REST API 测试 ----

    @Test
    void uploadFileViaRestApiSendsMultipartPost() throws Exception {
        OkHttpClient mockHttp = mock(OkHttpClient.class);
        Call mockCall = mock(Call.class);
        when(mockHttp.newCall(any())).thenReturn(mockCall);
        Response okResponse =
                new Response.Builder()
                        .code(200)
                        .message("OK")
                        .body(ResponseBody.create("", MediaType.get("application/json")))
                        .request(new Request.Builder().url("https://sandbox.e2b.app").build())
                        .protocol(Protocol.HTTP_1_1)
                        .build();
        when(mockCall.execute()).thenReturn(okResponse);

        E2bSandboxClientOptions opt = options(E2bCodec.JSON);
        opt.setRunUser(null);
        opt.setHttpClient(mockHttp);
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(opt);

        E2bSandboxState state = mock(E2bSandboxState.class);
        when(state.getSandboxDomain()).thenReturn("e2b.app");
        when(state.getSandboxId()).thenReturn("test-id");

        client.uploadFile(state, "/tmp/test.txt", "data".getBytes());

        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(mockHttp).newCall(requestCaptor.capture());
        Request sent = requestCaptor.getValue();
        assertEquals("POST", sent.method());
        assertTrue(sent.body() instanceof MultipartBody);
        assertEquals(1, ((MultipartBody) sent.body()).size());
        String url = sent.url().toString();
        assertTrue(url.startsWith("https://49983-test-id.e2b.app/files?path="));
        assertTrue(url.contains("path=%2Ftmp%2Ftest.txt"));
        assertTrue(url.contains("username=user"));
        verify(mockCall).execute();
    }

    @Test
    void uploadFileViaRestApiThrowsOnHttpError() throws Exception {
        OkHttpClient mockHttp = mock(OkHttpClient.class);
        Call mockCall = mock(Call.class);
        when(mockHttp.newCall(any())).thenReturn(mockCall);
        Response okResponse =
                new Response.Builder()
                        .code(500)
                        .message("Internal Server Error")
                        .body(ResponseBody.create("server error", MediaType.get("text/plain")))
                        .request(new Request.Builder().url("https://sandbox.e2b.app").build())
                        .protocol(Protocol.HTTP_1_1)
                        .build();
        when(mockCall.execute()).thenReturn(okResponse);

        E2bSandboxClientOptions opt = options(E2bCodec.JSON);
        opt.setHttpClient(mockHttp);
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(opt);

        E2bSandboxState state = mock(E2bSandboxState.class);
        when(state.getSandboxDomain()).thenReturn("e2b.app");
        when(state.getSandboxId()).thenReturn("test-id");

        SandboxException.SandboxRuntimeException ex =
                assertThrows(
                        SandboxException.SandboxRuntimeException.class,
                        () -> client.uploadFile(state, "/tmp/test.txt", "data".getBytes()));
        assertEquals(SandboxErrorCode.WORKSPACE_ARCHIVE_WRITE_ERROR, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("500"));
    }

    @Test
    void uploadFileWithDirectoryPathThrowsIllegalArgument() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(options(E2bCodec.JSON));
        E2bSandboxState state = mock(E2bSandboxState.class);

        assertThrows(
                IllegalArgumentException.class,
                () -> client.uploadFile(state, "/home/", "data".getBytes()));
    }

    @Test
    void downloadFileViaRestApiReturnsBytes() throws Exception {
        OkHttpClient mockHttp = mock(OkHttpClient.class);
        Call mockCall = mock(Call.class);
        when(mockHttp.newCall(any())).thenReturn(mockCall);
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);
        Response okResponse =
                new Response.Builder()
                        .code(200)
                        .message("OK")
                        .body(
                                ResponseBody.create(
                                        content, MediaType.get("application/octet-stream")))
                        .request(new Request.Builder().url("https://sandbox.e2b.app").build())
                        .protocol(Protocol.HTTP_1_1)
                        .build();
        when(mockCall.execute()).thenReturn(okResponse);

        E2bSandboxClientOptions opt = options(E2bCodec.JSON);
        opt.setHttpClient(mockHttp);
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(opt);

        E2bSandboxState state = mock(E2bSandboxState.class);
        when(state.getSandboxDomain()).thenReturn("e2b.app");
        when(state.getSandboxId()).thenReturn("test-id");

        byte[] result = client.downloadFile(state, "/tmp/test.txt");

        assertArrayEquals(content, result);
        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(mockHttp).newCall(requestCaptor.capture());
        String url = requestCaptor.getValue().url().toString();
        assertTrue(url.startsWith("https://49983-test-id.e2b.app/files?path="));
        assertTrue(url.contains("path=%2Ftmp%2Ftest.txt"));
        assertTrue(url.contains("username=user"));
        verify(mockCall).execute();
    }

    @Test
    void downloadFileViaRestApiThrowsOnHttpError() throws Exception {
        OkHttpClient mockHttp = mock(OkHttpClient.class);
        Call mockCall = mock(Call.class);
        when(mockHttp.newCall(any())).thenReturn(mockCall);
        Response okResponse =
                new Response.Builder()
                        .code(404)
                        .message("Not Found")
                        .body(ResponseBody.create("not found", MediaType.get("text/plain")))
                        .request(new Request.Builder().url("https://sandbox.e2b.app").build())
                        .protocol(Protocol.HTTP_1_1)
                        .build();
        when(mockCall.execute()).thenReturn(okResponse);

        E2bSandboxClientOptions opt = options(E2bCodec.JSON);
        opt.setHttpClient(mockHttp);
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(opt);

        E2bSandboxState state = mock(E2bSandboxState.class);
        when(state.getSandboxDomain()).thenReturn("e2b.app");
        when(state.getSandboxId()).thenReturn("test-id");

        SandboxException.SandboxRuntimeException ex =
                assertThrows(
                        SandboxException.SandboxRuntimeException.class,
                        () -> client.downloadFile(state, "/tmp/nonexistent.txt"));
        assertEquals(SandboxErrorCode.WORKSPACE_ARCHIVE_READ_ERROR, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("404"));
    }

    private static int drainStartStream(
            E2bEnvdProcessClient client,
            byte[] connectFrame,
            ByteArrayOutputStream stdout,
            ByteArrayOutputStream stderr)
            throws Exception {
        Method method =
                E2bEnvdProcessClient.class.getDeclaredMethod(
                        "drainStartStream",
                        InputStream.class,
                        ByteArrayOutputStream.class,
                        ByteArrayOutputStream.class);
        method.setAccessible(true);
        try {
            return (int)
                    method.invoke(client, new ByteArrayInputStream(connectFrame), stdout, stderr);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }

    private static byte[] connectFrame(String json) {
        return E2bEnvdProcessClient.encodeUnaryEnvelope(json.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] envelope(int flags, byte[] payload) {
        byte[] out = new byte[5 + payload.length];
        out[0] = (byte) flags;
        ByteBuffer.wrap(out, 1, 4).order(ByteOrder.BIG_ENDIAN).putInt(payload.length);
        System.arraycopy(payload, 0, out, 5, payload.length);
        return out;
    }

    private static byte[] connectFrame(byte[] payload) {
        return envelope(0x00, payload);
    }

    private static byte[] endStreamFrame(String json) {
        return envelope(0x02, json.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] concatFrames(byte[]... frames) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] frame : frames) {
            out.writeBytes(frame);
        }
        return out.toByteArray();
    }

    private static byte[] connectFrames(String... jsons) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String json : jsons) {
            byte[] frame = connectFrame(json);
            out.writeBytes(frame);
        }
        return out.toByteArray();
    }

    private static String responseJson(String stdout, String stderr, Integer exitCode) {
        StringBuilder json = new StringBuilder("{\"event\":{");
        boolean needComma = false;
        if (stdout != null || stderr != null) {
            json.append("\"data\":{");
            boolean needDataComma = false;
            if (stdout != null) {
                json.append("\"stdout\":\"").append(stdout).append("\"");
                needDataComma = true;
            }
            if (stderr != null) {
                if (needDataComma) {
                    json.append(',');
                }
                json.append("\"stderr\":\"").append(stderr).append("\"");
            }
            json.append('}');
            needComma = true;
        }
        if (exitCode != null) {
            if (needComma) {
                json.append(',');
            }
            json.append("\"end\":{\"exitCode\":").append(exitCode).append('}');
        }
        json.append("}}");
        return json.toString();
    }

    private static String endEventJson(Integer exitCode, String error) {
        StringBuilder json = new StringBuilder("{\"event\":{\"end\":{");
        boolean needComma = false;
        if (exitCode != null) {
            json.append("\"exitCode\":").append(exitCode);
            needComma = true;
        }
        if (error != null) {
            if (needComma) {
                json.append(',');
            }
            json.append("\"error\":").append(jsonString(error));
        }
        json.append("}}}");
        return json.toString();
    }

    private static String jsonString(String value) {
        return "\""
                + value.replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r")
                        .replace("\t", "\\t")
                + "\"";
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static E2bSandboxClientOptions options(E2bCodec codec) {
        E2bSandboxClientOptions options = new E2bSandboxClientOptions();
        options.setCodec(codec);
        return options;
    }

    @Test
    void requestCarriesConnectTimeoutMsHeader() throws Exception {
        AtomicReference<String> header = new AtomicReference<>();
        Interceptor capture =
                chain -> {
                    header.set(chain.request().header("Connect-Timeout-Ms"));
                    throw new SocketTimeoutException("timeout");
                };
        E2bEnvdProcessClient client = clientWithInterceptor(capture);

        assertThrows(
                SandboxException.ExecTimeoutException.class,
                () -> client.runShell(state(), "/workspace", "sleep 1000", 3));
        assertEquals("3000", header.get());
    }

    @Test
    void clientBackupToleratesServerKillMargin() throws Exception {
        // timeout=1，停顿 1.5 秒后返回正常退出帧；若无客户端
        // 余量，callTimeout 会先触发，有余量时该停顿
        // 调用正常完成。
        byte[] exitFrame = connectFrame(responseJson(null, null, 0));
        Interceptor stalling =
                chain -> {
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new java.io.InterruptedIOException("interrupted stall");
                    }
                    return cannedResponse(chain, exitFrame);
                };
        E2bSandboxClientOptions opt = options(E2bCodec.JSON);
        opt.setHttpClient(new OkHttpClient.Builder().addInterceptor(stalling).build());
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(opt);

        client.runShell(state(), "/workspace", "echo hi", 1);
    }

    @Test
    void interruptionIsRethrownNotWrappedAsTimeout() throws Exception {
        Interceptor interrupting =
                chain -> {
                    Thread.currentThread().interrupt();
                    throw new SocketTimeoutException("read timed out");
                };
        E2bEnvdProcessClient client = clientWithInterceptor(interrupting);
        try {
            SocketTimeoutException thrown =
                    assertThrows(
                            SocketTimeoutException.class,
                            () -> client.runShell(state(), "/workspace", "sleep 1000", 3));
            assertEquals("read timed out", thrown.getMessage());
            assertTrue(Thread.currentThread().isInterrupted(), "interrupt bit must be restored");
        } finally {
            // 不要将线程中断标记泄漏到其他测试。
            Thread.interrupted();
        }
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    void zeroTimeoutSendsNoDeadlineHeaderAndIsNotATimeout() throws Exception {
        AtomicReference<String> header = new AtomicReference<>();
        Interceptor capture =
                chain -> {
                    header.set(chain.request().header("Connect-Timeout-Ms"));
                    throw new SocketTimeoutException("must surface as-is");
                };
        E2bEnvdProcessClient client = clientWithInterceptor(capture);

        SocketTimeoutException e =
                assertThrows(
                        SocketTimeoutException.class,
                        () -> client.runShell(state(), "/workspace", "sleep 120", 0));

        // 没有截止时间时，传输超时不是执行超时。
        assertEquals("must surface as-is", e.getMessage());
        // Connect-Timeout-Ms: 0 意味着截止时间已过，因此必须省略。
        assertNull(header.get(), "timeout=0 means no deadline: header must be omitted");
    }

    @Test
    void negativeTimeoutFailsFastWithoutRequest() throws Exception {
        AtomicReference<String> header = new AtomicReference<>();
        Interceptor capture =
                chain -> {
                    header.set(chain.request().header("Connect-Timeout-Ms"));
                    throw new SocketTimeoutException("must not be called");
                };
        E2bEnvdProcessClient client = clientWithInterceptor(capture);

        IllegalArgumentException negative =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> client.runShell(state(), "/workspace", "echo hi", -5));
        assertTrue(negative.getMessage().contains("-5"));
        assertNull(header.get(), "no HTTP request must be issued for invalid timeout");
    }

    @Test
    void jsonDeadlineExceededEndFrameMapsToExecTimeout() throws Exception {
        byte[] payload =
                ("{\"error\":{\"code\":\"deadline_exceeded\","
                                + "\"message\":\"context deadline exceeded\"}}")
                        .getBytes(StandardCharsets.UTF_8);
        E2bEnvdProcessClient client =
                clientWithBody(options(E2bCodec.JSON), endStreamFrame(payload));

        assertThrows(
                SandboxException.ExecTimeoutException.class,
                () -> client.runShell(state(), "/workspace", "sleep 1000", 30));
    }

    @Test
    void timeoutMessageCarriesNoCommandText() throws Exception {
        byte[] payload =
                ("{\"error\":{\"code\":\"deadline_exceeded\","
                                + "\"message\":\"context deadline exceeded\"}}")
                        .getBytes(StandardCharsets.UTF_8);
        E2bEnvdProcessClient client =
                clientWithBody(options(E2bCodec.JSON), endStreamFrame(payload));

        SandboxException.ExecTimeoutException e =
                assertThrows(
                        SandboxException.ExecTimeoutException.class,
                        () ->
                                client.runShell(
                                        state(),
                                        "/workspace",
                                        "curl -H 'Authorization: Bearer sk-secret' example.test",
                                        30));
        assertEquals("Command timed out after 30s", e.getMessage());
        assertFalse(
                e.getMessage().contains("sk-secret"),
                "command text must not leak into the message");
    }

    @Test
    void jsonNonTimeoutErrorFrameKeepsStreamError() throws Exception {
        byte[] payload =
                "{\"error\":{\"code\":\"unavailable\",\"message\":\"sandbox gone\"}}"
                        .getBytes(StandardCharsets.UTF_8);
        E2bEnvdProcessClient client =
                clientWithBody(options(E2bCodec.JSON), endStreamFrame(payload));

        SandboxException.SandboxRuntimeException e =
                assertThrows(
                        SandboxException.SandboxRuntimeException.class,
                        () -> client.runShell(state(), "/workspace", "sleep 1000", 30));
        assertTrue(e.getMessage().contains("unavailable"), "code must survive: " + e.getMessage());
    }

    @Test
    void protoCodecStillDecodesJsonEndStreamFrame() throws Exception {
        // 从真实 envd 0.6.10 捕获：即使 Content-Type 为
        // application/connect+proto，结束载荷仍是此精确
        // 76 字节 JSON；connect-go 的 MarshalEndStream 固定调用 json.Marshal。
        // 防止重新引入按编解码器分派的 protobuf 结束帧解码。
        String live =
                "{\"error\":{\"code\":\"deadline_exceeded\","
                        + "\"message\":\"context deadline exceeded\"}}";
        assertEquals(76, live.getBytes(StandardCharsets.UTF_8).length);
        E2bEnvdProcessClient client =
                clientWithBody(
                        options(E2bCodec.PROTO),
                        endStreamFrame(live.getBytes(StandardCharsets.UTF_8)));

        assertThrows(
                SandboxException.ExecTimeoutException.class,
                () -> client.runShell(state(), "/workspace", "sleep 1000", 30));
    }

    @Test
    void cleanEndFrameWithoutExitStaysStreamError() throws Exception {
        E2bEnvdProcessClient client =
                clientWithBody(
                        options(E2bCodec.JSON),
                        endStreamFrame("{}".getBytes(StandardCharsets.UTF_8)));

        IOException e =
                assertThrows(
                        IOException.class,
                        () -> client.runShell(state(), "/workspace", "sleep 1000", 30));
        assertTrue(
                e.getMessage().contains("before receiving a process exit code"),
                "clean end without exit keeps #2828 semantics: " + e.getMessage());
    }

    @Test
    void alreadyCancelledCallFailsFastWithoutBlocking() throws Exception {
        AtomicReference<String> header = new AtomicReference<>();
        Interceptor capture =
                chain -> {
                    header.set(chain.request().header("Connect-Timeout-Ms"));
                    throw new SocketTimeoutException("must not be called");
                };
        E2bEnvdProcessClient client = clientWithInterceptor(capture);
        Thread.currentThread().interrupt();
        try {
            long start = System.nanoTime();
            InterruptedIOException e =
                    assertThrows(
                            InterruptedIOException.class,
                            () -> client.runShell(state(), "/workspace", "echo hi", 30));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(elapsedMs < 5000, "must fail fast, took " + elapsedMs + "ms");
            assertTrue(Thread.currentThread().isInterrupted(), "interrupt bit must be restored");
            assertTrue(
                    e.getMessage().contains("cancelled before start"),
                    "must not look like a timeout: " + e.getMessage());
        } finally {
            Thread.interrupted();
        }
        assertNull(header.get(), "no HTTP request must be issued for a cancelled call");
    }

    @Test
    void malformedEndFrameFallsThroughToStreamError() throws Exception {
        E2bEnvdProcessClient client =
                clientWithBody(
                        options(E2bCodec.JSON),
                        endStreamFrame("%%%not-json%%%".getBytes(StandardCharsets.UTF_8)));

        // 损坏的结束信封必须明确失败，因为流尾帧
        // 不可信；解析错误应传播，而非降级为
        // 缺失退出码错误。
        assertThrows(
                IOException.class, () -> client.runShell(state(), "/workspace", "sleep 1000", 30));
    }

    @Test
    void emptyEndFrameStaysStreamError() throws Exception {
        E2bEnvdProcessClient client =
                clientWithBody(options(E2bCodec.PROTO), endStreamFrame(new byte[0]));

        IOException e =
                assertThrows(
                        IOException.class,
                        () -> client.runShell(state(), "/workspace", "sleep 1000", 30));
        assertTrue(
                e.getMessage().contains("before receiving a process exit code"),
                "clean end without exit keeps #2828 semantics: " + e.getMessage());
    }

    @Test
    void prematureEndAfterDeadlineMapsToExecTimeout() {
        E2bEnvdProcessClient.MissingExitCodeException e =
                new E2bEnvdProcessClient.MissingExitCodeException();

        Exception mapped =
                E2bEnvdProcessClient.mapPrematureEnd(
                        e, "sleep 1000", 5, System.nanoTime() - 6_000_000_000L);

        assertInstanceOf(SandboxException.ExecTimeoutException.class, mapped);
    }

    @Test
    void prematureEndBeforeDeadlineStaysStreamError() {
        E2bEnvdProcessClient.MissingExitCodeException e =
                new E2bEnvdProcessClient.MissingExitCodeException();

        assertSame(e, E2bEnvdProcessClient.mapPrematureEnd(e, "sleep 1000", 30, System.nanoTime()));
    }

    @Test
    void prematureEndWithoutDeadlineStaysStreamError() {
        E2bEnvdProcessClient.MissingExitCodeException e =
                new E2bEnvdProcessClient.MissingExitCodeException();

        // 相同耗时在正超时时映射为 ExecTimeoutException；
        // 无截止时间时不能变为 "timed out after 0s"。
        assertSame(
                e,
                E2bEnvdProcessClient.mapPrematureEnd(
                        e, "sleep 1000", 0, System.nanoTime() - 6_000_000_000L));
    }

    private static E2bEnvdProcessClient clientWithInterceptor(Interceptor interceptor)
            throws Exception {
        E2bSandboxClientOptions opt = options(E2bCodec.PROTO);
        opt.setHttpClient(new OkHttpClient.Builder().addInterceptor(interceptor).build());
        return new E2bEnvdProcessClient(opt);
    }

    private static E2bEnvdProcessClient clientWithBody(E2bSandboxClientOptions opt, byte[] body)
            throws Exception {
        Interceptor canned = chain -> cannedResponse(chain, body);
        opt.setHttpClient(new OkHttpClient.Builder().addInterceptor(canned).build());
        return new E2bEnvdProcessClient(opt);
    }

    private static Response cannedResponse(Interceptor.Chain chain, byte[] body) {
        return new Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(body, null))
                .build();
    }

    private static byte[] endStreamFrame(byte[] payload) {
        byte[] out = new byte[5 + payload.length];
        out[0] = 0x02;
        ByteBuffer.wrap(out, 1, 4).order(ByteOrder.BIG_ENDIAN).putInt(payload.length);
        System.arraycopy(payload, 0, out, 5, payload.length);
        return out;
    }

    private static E2bSandboxState state() {
        E2bSandboxState state = new E2bSandboxState();
        state.setSandboxId("test-sandbox");
        return state;
    }
}
