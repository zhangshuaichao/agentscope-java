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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.SandboxErrorCode;
import io.agentscope.harness.agent.sandbox.SandboxException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 精简 envd 客户端：通过 Connect 服务端流执行命令，通过 Filesystem REST API 传输文件。
 *
 * <p>【学习提示】客户端超时只说明本地等待结束，不能自行证明远程进程已经停止。这里同时设置服务端执行期限和稍晚的客户端兜底，并区分外部中断、执行超时与传输故障；结果不确定时不可因重试而重复执行有副作用命令。timeout=0 明确无截止时间，不是立即超时。
 */
final class E2bEnvdProcessClient {

    private static final Logger log = LoggerFactory.getLogger(E2bEnvdProcessClient.class);

    private static final MediaType CONNECT_JSON = MediaType.get("application/connect+json");
    private static final MediaType CONNECT_PROTO = MediaType.get("application/connect+proto");
    private static final MediaType APPLICATION_OCTET_STREAM =
            MediaType.get("application/octet-stream");
    private static final int ENVD_PORT = 49983;
    private static final int OUTPUT_TRUNCATE_BYTES = 512 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final OkHttpClient http;
    private final Descriptors.Descriptor startRequestDesc;
    private final Descriptors.Descriptor startResponseDesc;
    private final Descriptors.Descriptor processEventDesc;
    private final E2bSandboxClientOptions opt;

    E2bEnvdProcessClient(E2bSandboxClientOptions opt) throws Exception {
        this.opt = Objects.requireNonNull(opt, "opt");
        if (opt.getHttpClient() != null) {
            this.http = opt.getHttpClient();
        } else {
            this.http =
                    new OkHttpClient.Builder()
                            .connectTimeout(opt.getConnectTimeoutSeconds(), TimeUnit.SECONDS)
                            .readTimeout(opt.getReadTimeoutSeconds(), TimeUnit.SECONDS)
                            .build();
        }
        Descriptors.FileDescriptor fileDescriptor;
        try (InputStream in =
                E2bEnvdProcessClient.class.getResourceAsStream("/e2b-process-fdp.pb")) {
            if (in == null) {
                throw new IOException("Missing classpath resource /e2b-process-fdp.pb");
            }
            com.google.protobuf.DescriptorProtos.FileDescriptorProto fdp =
                    com.google.protobuf.DescriptorProtos.FileDescriptorProto.parseFrom(
                            in.readAllBytes());
            fileDescriptor =
                    com.google.protobuf.Descriptors.FileDescriptor.buildFrom(
                            fdp, new com.google.protobuf.Descriptors.FileDescriptor[0]);
        }
        this.startRequestDesc = fileDescriptor.findMessageTypeByName("StartRequest");
        this.startResponseDesc = fileDescriptor.findMessageTypeByName("StartResponse");
        this.processEventDesc = fileDescriptor.findMessageTypeByName("ProcessEvent");
    }

    ExecResult runShell(E2bSandboxState state, String cwd, String shellCommand, int timeoutSeconds)
            throws Exception {
        ShellCapture cap = runShellCapture(state, cwd, shellCommand, timeoutSeconds);
        String outStr = truncateUtf8(cap.stdout.toByteArray());
        String errStr = truncateUtf8(cap.stderr.toByteArray());
        boolean truncated =
                cap.stdout.size() >= OUTPUT_TRUNCATE_BYTES
                        || cap.stderr.size() >= OUTPUT_TRUNCATE_BYTES;
        ExecResult r = new ExecResult(cap.exitCode, outStr, errStr, truncated);
        if (!r.ok()) {
            throw new SandboxException.ExecException(cap.exitCode, outStr, errStr);
        }
        return r;
    }

    private ShellCapture runShellCapture(
            E2bSandboxState state, String cwd, String shellCommand, int timeoutSeconds)
            throws Exception {
        // 0 表示“无截止时间”，与官方 E2B SDK 一致（Python 将 0/None 映射为
        // 无截止时间），也与使用 0 执行 `sleep 120` 并阻塞直到被终止的合并集成测试
        // 一致。若像 daytona/agentrun 一样钳制为 1 秒，就会把该契约
        // 变成莫名其妙的 1 秒超时。负值仍视为调用方错误。
        if (timeoutSeconds < 0) {
            throw new IllegalArgumentException(
                    "[e2b] timeoutSeconds must be zero or positive, got "
                            + timeoutSeconds
                            + ". This is a caller bug, not a sandbox failure;"
                            + " check the timeout argument.");
        }
        boolean bounded = timeoutSeconds > 0;
        // 保存中断位快照：调用前残留的中断位既不能
        // 把真正的执行超时误判为下方的取消，也不能让
        // 已经取消的调用方阻塞到完整超时结束。
        boolean preInterrupted = Thread.interrupted();
        if (preInterrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("exec cancelled before start");
        }
        // 禁用空闲读取超时：它按字节间隔触发，会先于
        // callTimeout 触发（两者都表现为 InterruptedIOException），从而把
        // 短暂空闲停顿误报为整个执行超时（#2974）。总时长语义仅由
        // callTimeout 管理；若未请求截止时间，则不设总时长限制。
        OkHttpClient.Builder callClientBuilder = http.newBuilder().readTimeout(0, TimeUnit.SECONDS);
        if (bounded) {
            // 服务端截止时间保持精确，以便及时终止；客户端后备
            // 延后 CLIENT_TIMEOUT_SLACK_MILLIS 执行，仅作为异常服务端或挂起流的
            // 兜底，正常路径中不应抢先触发。
            callClientBuilder.callTimeout(
                    timeoutSeconds * 1000L + CLIENT_TIMEOUT_SLACK_MILLIS, TimeUnit.MILLISECONDS);
        } else {
            // 显式设为无界：否则注入客户端继承的 callTimeout
            // 仍会限制请求了无截止时间的调用。此设置也覆盖
            // 调用方在该客户端配置的全局 callTimeout。
            callClientBuilder.callTimeout(0, TimeUnit.MILLISECONDS);
        }
        OkHttpClient callClient = callClientBuilder.build();
        String host = envdHost(state);
        String url = host + "/process.Process/Start";
        byte[] envelope = encodeStartRequestEnvelope(shellCommand, cwd);
        Request.Builder requestBuilder = buildEnvdRequest(url, envelope, connectMediaType(), state);
        if (bounded) {
            // 请求 envd 在期限到达时终止远程进程；否则
            // 客户端超时仅丢弃 HTTP 流，会泄漏进程。
            // 值为 0 时省略：Connect-Timeout-Ms: 0 表示“截止时间已过”，
            // 不是“无截止时间”。
            requestBuilder.addHeader("Connect-Timeout-Ms", String.valueOf(timeoutSeconds * 1000L));
        }
        Request req = requestBuilder.build();

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit;
        long startNanos = System.nanoTime();
        try (Response res = callClient.newCall(req).execute()) {
            if (!res.isSuccessful()) {
                String err = Objects.requireNonNull(res.body(), "envd response body").string();
                throw new SandboxException.SandboxRuntimeException(
                        SandboxErrorCode.WORKSPACE_START_ERROR,
                        "envd Start failed HTTP " + res.code() + ": " + err);
            }
            try (InputStream in =
                    Objects.requireNonNull(res.body(), "envd response body").byteStream()) {
                exit = drainStartStream(in, stdout, stderr);
            }
        } catch (InterruptedIOException e) {
            // 外部取消也会在这里表现出来；不要误报为超时，
            // 也不要吞掉中断位。仅计入本次调用期间设置的位，
            // 此前残留的位已在上方清除。
            if (Thread.currentThread().isInterrupted()) {
                throw e;
            }
            if (!bounded) {
                // 没有请求截止时间，因此这是传输故障（例如
                // 连接超时），绝不是命令执行超时。
                throw e;
            }
            throw new SandboxException.ExecTimeoutException(shellCommand, timeoutSeconds);
        } catch (ConnectStreamException e) {
            if (bounded && CONNECT_DEADLINE_EXCEEDED.equals(e.code())) {
                logPartialStreams(
                        "timed out (server deadline_exceeded)",
                        timeoutSeconds,
                        startNanos,
                        stdout,
                        stderr);
                throw new SandboxException.ExecTimeoutException(shellCommand, timeoutSeconds);
            }
            throw new IOException(
                    "envd process stream error [" + e.code() + "]: " + e.getMessage(), e);
        } catch (MissingExitCodeException e) {
            logPartialStreams(
                    "ended without exit code", timeoutSeconds, startNanos, stdout, stderr);
            throw mapPrematureEnd(e, shellCommand, timeoutSeconds, startNanos);
        }
        return new ShellCapture(exit, stdout, stderr);
    }

    private record ShellCapture(
            int exitCode, ByteArrayOutputStream stdout, ByteArrayOutputStream stderr) {}

    /**
     * 服务端命令执行期限到达时 envd 返回的 Connect 错误码。
     */
    private static final String CONNECT_DEADLINE_EXCEEDED = "deadline_exceeded";

    /**
     * 服务端截止时间与客户端后备超时之间的故障分级阈值。
     * T 内：服务端按时终止，客户端通过 {@code deadline_exceeded} 观察到结果，不消耗余量。T+余量内：工程容差用于吸收连接/设置抖动（冷握手、弱网络），使终止仍可在重试与运行进程重叠前发生。超过 T+余量：视为网络层故障（请求未到达或服务端挂起），客户端后备触发以保证本地返回。5 秒覆盖未极端恶化的连接设置分布；更大值将接近智能体重试间隔，而无额外收益。
     */
    private static final long CLIENT_TIMEOUT_SLACK_MILLIS = 5000;

    /**
     * 携带 Connect 错误的流结束帧（例如服务端超时终止）。
     */
    static final class ConnectStreamException extends IOException {
        private final String code;

        ConnectStreamException(String code, String message) {
            super(message);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    /**
     * 流结束但没有进程退出码（#2828）。
     */
    static final class MissingExitCodeException extends IOException {
        MissingExitCodeException() {
            super("envd process stream ended before receiving a process exit code");
        }
    }

    private static long elapsedSeconds(long startNanos) {
        return TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startNanos);
    }

    static Exception mapPrematureEnd(
            MissingExitCodeException e, String shellCommand, int timeoutSeconds, long startNanos) {
        // 无截止时间（0）时，无论已运行多久，提前结束都不是超时。
        if (timeoutSeconds > 0 && elapsedSeconds(startNanos) >= timeoutSeconds) {
            return new SandboxException.ExecTimeoutException(shellCommand, timeoutSeconds);
        }
        return e;
    }

    private static void logPartialStreams(
            String why,
            int timeoutSeconds,
            long startNanos,
            ByteArrayOutputStream stdout,
            ByteArrayOutputStream stderr) {
        if (!log.isDebugEnabled()) {
            return;
        }
        log.debug(
                "[e2b] exec {} after {}s with {};"
                        + " partial stdout tail: {}; partial stderr tail: {}",
                why,
                elapsedSeconds(startNanos),
                timeoutSeconds > 0 ? timeoutSeconds + "s timeout" : "no deadline",
                tailUtf8(stdout),
                tailUtf8(stderr));
    }

    private static String tailUtf8(ByteArrayOutputStream out) {
        byte[] b = out.toByteArray();
        int n = Math.min(b.length, 512);
        return new String(b, b.length - n, n, StandardCharsets.UTF_8);
    }

    private int drainStartStream(
            InputStream in, ByteArrayOutputStream stdout, ByteArrayOutputStream stderr)
            throws IOException {
        Integer exit = null;
        Descriptors.FieldDescriptor srEventF = startResponseDesc.findFieldByName("event");
        Descriptors.FieldDescriptor peDataF = processEventDesc.findFieldByName("data");
        Descriptors.FieldDescriptor peEndF = processEventDesc.findFieldByName("end");
        while (true) {
            int flags = in.read();
            if (flags == -1) {
                break;
            }
            byte[] lenB = in.readNBytes(4);
            if (lenB.length < 4) {
                break;
            }
            // Connect 协议：Message-Length 是 4 字节无符号大端整数
            // https://connectrpc.com/docs/protocol/#streaming-rpcs
            int len = ByteBuffer.wrap(lenB).order(ByteOrder.BIG_ENDIAN).getInt();
            if (len < 0 || len > 64 * 1024 * 1024) {
                throw new IOException("Invalid connect frame length: " + len);
            }
            byte[] data = in.readNBytes(len);
            if (data.length < len) {
                break;
            }
            // Connect 协议：flags 的第 0 位表示压缩。先于 end_stream 检查，以便
            // 将组合信封（例如 0x03）作为压缩内容拒绝，而非
            // 把压缩字节交给 EndStreamMessage JSON 解析器。
            // https://connectrpc.com/docs/protocol/#streaming-rpcs
            if ((flags & 0x01) != 0) {
                throw new IOException("Compressed connect frames not supported");
            }
            // Connect 协议：flags 的第 1 位为 end_stream，最终信封携带 EndStreamMessage
            // JSON 载荷
            // https://connectrpc.com/docs/protocol/#streaming-rpcs
            if ((flags & 0x02) != 0) {
                EndStreamMessage endMsg = parseEndStreamResponse(data);
                if (endMsg.hasError()) {
                    log.debug(
                            "drainStartStream: endStream error code={} msg={}",
                            endMsg.code(),
                            endMsg.message());
                    // 服务端执行超时终止在此表现为
                    // deadline_exceeded；以类型化错误向上传播，使调用方映射为
                    // 执行超时而非通用流故障（#2974）。
                    if (CONNECT_DEADLINE_EXCEEDED.equals(endMsg.code())) {
                        throw new ConnectStreamException(endMsg.code(), endMsg.message());
                    }
                    throw new SandboxException.SandboxRuntimeException(
                            SandboxErrorCode.WORKSPACE_START_ERROR,
                            "Process start failed: " + endMsg.description());
                }
                log.debug("drainStartStream: endStream ok");
                break;
            }
            // Connect 协议：flags 的第 2–7 位预留给未来扩展
            // https://connectrpc.com/docs/protocol/#streaming-rpcs
            if ((flags & 0xFC) != 0) {
                log.debug(
                        "drainStartStream: skipping reserved flags=0x{}",
                        Integer.toHexString(flags));
                continue;
            }
            DynamicMessage sr = parseStartResponseFrame(data);
            if (!sr.hasField(srEventF)) {
                log.debug("drainStartStream: frame with no event");
                continue;
            }
            DynamicMessage pe = (DynamicMessage) sr.getField(srEventF);
            if (pe.hasField(peDataF)) {
                DynamicMessage de = (DynamicMessage) pe.getField(peDataF);
                appendDataStream(de, "stdout", stdout);
                appendDataStream(de, "stderr", stderr);
            }
            if (pe.hasField(peEndF)) {
                DynamicMessage end = (DynamicMessage) pe.getField(peEndF);
                // 注意：此处不检查空值。在线协议中，exit_code=0 的 EndEvent
                // 与无字段的 EndEvent 逐位相同（proto3
                // 省略默认标量），因此无法区分；官方
                // Python SDK 也因此直接读取 end.exit_code。JSON
                // 路径已在 parseJsonStartResponse 中提前过滤真正空的结束事件。
                Descriptors.Descriptor endDesc = end.getDescriptorForType();
                // Proto3：标量 sint32 默认为 0，值为 0 时在线协议中省略。
                // 字段缺失时 getField() 返回 proto3 默认值 0，hasField() 返回 false。
                // https://protobuf.dev/programming-guides/proto3/#default
                Descriptors.FieldDescriptor ecF = endDesc.findFieldByName("exit_code");
                if (ecF != null) {
                    exit = ((Number) end.getField(ecF)).intValue();
                }
                // EndEvent.error：cmd.Wait() 返回 ExitError 时填充
                // （非零退出码或信号）。Go 处理器赋值：ProcessEvent_EndEvent.Error =
                // errMsg
                // https://github.com/e2b-dev/infra/blob/main/packages/envd/internal/services/process/handler/handler.go
                // Python SDK 读取：error=event.event.end.error
                // https://github.com/e2b-dev/e2b/blob/main/packages/python-sdk/e2b/sandbox_sync/commands/command_handle.py#L115
                Descriptors.FieldDescriptor errF = endDesc.findFieldByName("error");
                if (errF != null && end.hasField(errF)) {
                    writeStderrLine(
                            stderr,
                            String.valueOf(end.getField(errF)).getBytes(StandardCharsets.UTF_8));
                }
            }
        }
        if (exit == null) {
            throw new MissingExitCodeException();
        }
        return exit;
    }

    private static void writeStderrLine(ByteArrayOutputStream stderr, byte[] text) {
        // 将 EndEvent.error 与前面可能不带换行的 stderr 输出
        // 在显示上分开（"partial" + "exit status 1" 会难以阅读）。
        byte[] buffered = stderr.toByteArray();
        if (buffered.length > 0 && buffered[buffered.length - 1] != '\n') {
            stderr.write('\n');
        }
        stderr.writeBytes(text);
    }

    private static void appendDataStream(
            DynamicMessage dataEv, String field, ByteArrayOutputStream out) throws IOException {
        Descriptors.FieldDescriptor fieldDescriptor =
                dataEv.getDescriptorForType().findFieldByName(field);
        if (fieldDescriptor != null) {
            Object value = dataEv.getField(fieldDescriptor);
            if (value instanceof ByteString) {
                ((ByteString) value).writeTo(out);
            }
        }
    }

    // Connect 协议 EndStreamMessage 的 JSON：{"error":{"code":"...","message":"..."}}
    // 是否存在错误遵循官方 SDK 的真值规则：假值 error
    // 表示成功，真值表示失败。
    // https://connectrpc.com/docs/protocol/#error-end-stream
    // https://github.com/e2b-dev/e2b/blob/main/packages/python-sdk/e2b_connect/client.py#L230-L238
    private static EndStreamMessage parseEndStreamResponse(byte[] data) throws IOException {
        JsonNode root = JSON.readTree(data);
        if (root == null || root.isNull()) {
            return new EndStreamMessage(false, null, null);
        }
        JsonNode errorNode = root.path("error");
        // 与官方 connectrpc ConnectEnvelopeReader.handle_end_message 对齐：
        //   error = end_stream_message.get("error")
        //   if error: raise
        // Python 真值规则：缺失/null/空对象/空字符串/0/false 表示成功；
        // 其他值（非空对象、非空字符串等）表示失败。
        if (isFalsy(errorNode)) {
            return new EndStreamMessage(false, null, null);
        }
        String code = errorNode.path("code").asText(null);
        String message = errorNode.path("message").asText(null);
        if (code == null && message == null) {
            message = errorNode.isTextual() ? errorNode.textValue() : errorNode.toString();
        }
        return new EndStreamMessage(true, code, message);
    }

    private static boolean isFalsy(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return true;
        }
        if (node.isObject() || node.isArray()) {
            return node.isEmpty();
        }
        if (node.isTextual()) {
            return node.textValue().isEmpty();
        }
        if (node.isNumber()) {
            // asDouble()：无穷仍为无穷（真值）；仅在编解码器产生 NaN 时将其转为 0.0，
            // 而 decimalValue() 会抛出 NumberFormatException。
            return node.asDouble() == 0.0;
        }
        return !node.asBoolean(true);
    }

    private record EndStreamMessage(boolean errorPresent, String code, String message) {
        boolean hasError() {
            return errorPresent;
        }

        // 两侧均可能缺失（Connect Error 允许只有 code 或只有 message）；
        // 绝不在用户可见消息中输出字面值 "null"。
        String description() {
            if (code != null && message != null) {
                return code + ": " + message;
            }
            return message != null ? message : code;
        }
    }

    DynamicMessage buildStartRequest(String shellCommand, String cwd) {
        Descriptors.Descriptor pcDesc =
                startRequestDesc.getFile().findMessageTypeByName("ProcessConfig");
        DynamicMessage.Builder pcb = DynamicMessage.newBuilder(pcDesc);
        pcb.setField(pcDesc.findFieldByName("cmd"), "/bin/bash");
        pcb.addRepeatedField(pcDesc.findFieldByName("args"), "-l");
        pcb.addRepeatedField(pcDesc.findFieldByName("args"), "-c");
        pcb.addRepeatedField(pcDesc.findFieldByName("args"), shellCommand);
        if (cwd != null && !cwd.isBlank()) {
            pcb.setField(pcDesc.findFieldByName("cwd"), cwd);
        }
        DynamicMessage.Builder sb = DynamicMessage.newBuilder(startRequestDesc);
        sb.setField(startRequestDesc.findFieldByName("process"), pcb.build());
        sb.setField(startRequestDesc.findFieldByName("stdin"), false);
        return sb.build();
    }

    byte[] encodeStartRequestEnvelope(String shellCommand, String cwd) throws IOException {
        DynamicMessage startReq = buildStartRequest(shellCommand, cwd);
        byte[] payload =
                codec() == E2bCodec.JSON
                        ? encodeJsonStartRequest(startReq)
                        : startReq.toByteArray();
        return encodeUnaryEnvelope(payload);
    }

    DynamicMessage parseStartResponseFrame(byte[] data) throws IOException {
        if (codec() == E2bCodec.JSON) {
            return parseJsonStartResponse(data);
        }
        try {
            return DynamicMessage.parseFrom(startResponseDesc, data);
        } catch (InvalidProtocolBufferException e) {
            throw new IOException("Failed to decode connect+proto frame", e);
        }
    }

    MediaType connectMediaType() {
        return codec() == E2bCodec.JSON ? CONNECT_JSON : CONNECT_PROTO;
    }

    Descriptors.FileDescriptor fileDescriptor() {
        return startRequestDesc.getFile();
    }

    static byte[] encodeUnaryEnvelope(byte[] msg) {
        byte[] out = new byte[5 + msg.length];
        out[0] = 0x00;
        ByteBuffer.wrap(out, 1, 4).order(ByteOrder.BIG_ENDIAN).putInt(msg.length);
        System.arraycopy(msg, 0, out, 5, msg.length);
        return out;
    }

    // ---- 文件系统辅助方法（REST API）----

    /**
     * 通过 E2B Filesystem REST API 上传文件到沙箱。自动创建父目录并覆盖现有文件。
     *
     * @param state 沙箱状态
     * @param remotePath 沙箱内绝对路径
     * @param data 文件内容
     */
    void uploadFile(E2bSandboxState state, String remotePath, byte[] data) throws Exception {
        String filename = filenameFromPath(remotePath);
        if (filename.isEmpty()) {
            throw new IllegalArgumentException("remotePath must be a file path: " + remotePath);
        }
        String host = envdHost(state);
        String url = host + filesUrl(remotePath);

        RequestBody fileBody = RequestBody.create(data, APPLICATION_OCTET_STREAM);
        RequestBody multipart =
                new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("file", filename, fileBody)
                        .build();
        Request req = baseRequest(url, state).post(multipart).build();

        try (Response res = http.newCall(req).execute()) {
            if (!res.isSuccessful()) {
                throw fileTransferError(
                        res, SandboxErrorCode.WORKSPACE_ARCHIVE_WRITE_ERROR, "upload");
            }
        }
    }

    /**
     * 通过 E2B Filesystem REST API 从沙箱下载文件。
     *
     * @param state 沙箱状态
     * @param remotePath 沙箱内绝对路径
     * @return 文件内容
     */
    byte[] downloadFile(E2bSandboxState state, String remotePath) throws Exception {
        String host = envdHost(state);
        String url = host + filesUrl(remotePath);
        Request req = baseRequest(url, state).get().build();
        try (Response res = http.newCall(req).execute()) {
            if (!res.isSuccessful()) {
                throw fileTransferError(
                        res, SandboxErrorCode.WORKSPACE_ARCHIVE_READ_ERROR, "download");
            }
            return res.body() != null ? res.body().bytes() : new byte[0];
        }
    }

    private static String filenameFromPath(String path) {
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    private String filesUrl(String remotePath) {
        return "/files?path="
                + URLEncoder.encode(remotePath, StandardCharsets.UTF_8)
                + "&username="
                + URLEncoder.encode(effectiveRunUser(), StandardCharsets.UTF_8);
    }

    private static SandboxException.SandboxRuntimeException fileTransferError(
            Response res, SandboxErrorCode errorCode, String operation) throws IOException {
        String err = res.body() != null ? res.body().string() : "";
        return new SandboxException.SandboxRuntimeException(
                errorCode, "envd " + operation + " file failed HTTP " + res.code() + ": " + err);
    }

    private Request.Builder buildEnvdRequest(
            String url, byte[] body, MediaType mediaType, E2bSandboxState state) {
        return baseRequest(url, state)
                .post(RequestBody.create(body, mediaType))
                .addHeader("Connect-Protocol-Version", "1");
    }

    private Request.Builder baseRequest(String url, E2bSandboxState state) {
        Request.Builder rb =
                new Request.Builder()
                        .url(url)
                        .addHeader("User-Agent", "agentscope-java-e2b")
                        .addHeader("E2b-Sandbox-Id", state.getSandboxId())
                        .addHeader("E2b-Sandbox-Port", Integer.toString(ENVD_PORT))
                        .addHeader("Authorization", basicAuthUser());
        if (state.getEnvdAccessToken() != null && !state.getEnvdAccessToken().isBlank()) {
            rb.addHeader("X-Access-Token", state.getEnvdAccessToken());
        }
        return rb;
    }

    private E2bCodec codec() {
        return opt.getCodec() != null ? opt.getCodec() : E2bCodec.PROTO;
    }

    private byte[] encodeJsonStartRequest(DynamicMessage msg) throws IOException {
        Descriptors.FieldDescriptor processField = startRequestDesc.findFieldByName("process");
        Descriptors.FieldDescriptor stdinField = startRequestDesc.findFieldByName("stdin");
        DynamicMessage process = (DynamicMessage) msg.getField(processField);
        Descriptors.Descriptor processDesc = process.getDescriptorForType();

        ObjectNode root = JSON.createObjectNode();
        ObjectNode processNode = root.putObject("process");
        processNode.put(
                "cmd", stringField(process, processDesc.findFieldByName("cmd"), "/bin/bash"));
        ArrayNode argsNode = processNode.putArray("args");
        Descriptors.FieldDescriptor argsField = processDesc.findFieldByName("args");
        @SuppressWarnings("unchecked")
        java.util.List<Object> args = (java.util.List<Object>) process.getField(argsField);
        for (Object arg : args) {
            argsNode.add(String.valueOf(arg));
        }
        Descriptors.FieldDescriptor cwdField = processDesc.findFieldByName("cwd");
        if (process.hasField(cwdField)) {
            processNode.put("cwd", String.valueOf(process.getField(cwdField)));
        }
        root.put(
                "stdin", msg.hasField(stdinField) && Boolean.TRUE.equals(msg.getField(stdinField)));
        return JSON.writeValueAsBytes(root);
    }

    private DynamicMessage parseJsonStartResponse(byte[] data) throws IOException {
        JsonNode root = JSON.readTree(data);
        if (root == null || root.isNull()) {
            return DynamicMessage.newBuilder(startResponseDesc).build();
        }
        DynamicMessage.Builder response = DynamicMessage.newBuilder(startResponseDesc);
        JsonNode eventNode = root.path("event");
        if (eventNode.isMissingNode() || eventNode.isNull()) {
            return response.build();
        }
        DynamicMessage.Builder event = DynamicMessage.newBuilder(processEventDesc);

        JsonNode dataNode = eventNode.path("data");
        if (!dataNode.isMissingNode() && !dataNode.isNull()) {
            Descriptors.Descriptor dataDesc = processEventDesc.findNestedTypeByName("DataEvent");
            DynamicMessage.Builder dataBuilder = DynamicMessage.newBuilder(dataDesc);
            setBytesIfPresent(dataBuilder, dataNode, "stdout", dataDesc.findFieldByName("stdout"));
            setBytesIfPresent(dataBuilder, dataNode, "stderr", dataDesc.findFieldByName("stderr"));
            if (!dataBuilder.getAllFields().isEmpty()) {
                event.setField(processEventDesc.findFieldByName("data"), dataBuilder.build());
            }
        }

        JsonNode endNode = eventNode.path("end");
        if (!endNode.isMissingNode() && !endNode.isNull()) {
            if (!endNode.isObject()) {
                throw new IOException("Invalid EndEvent: " + endNode);
            }
            Descriptors.Descriptor endDesc = processEventDesc.findNestedTypeByName("EndEvent");
            DynamicMessage.Builder endBuilder = DynamicMessage.newBuilder(endDesc);
            Descriptors.FieldDescriptor exitCodeField = endDesc.findFieldByName("exit_code");
            // protojson 同时接受驼峰 jsonName（"exitCode"）和
            // 原始 proto 字段名（"exit_code"）；优先采用 jsonName，
            // 回退到原名。（error/event/end 等单词字段
            // 两种形式相同，无需回退。）
            JsonNode exitCodeNode = endNode.path("exitCode");
            if (exitCodeNode.isMissingNode() || exitCodeNode.isNull()) {
                exitCodeNode = endNode.path("exit_code");
            }
            if (!exitCodeNode.isMissingNode() && !exitCodeNode.isNull()) {
                endBuilder.setField(exitCodeField, parseExitCode(exitCodeNode));
            }
            Descriptors.FieldDescriptor errorField = endDesc.findFieldByName("error");
            // EndEvent.error：cmd.Wait() 返回 ExitError 时填充
            // （非零退出码或信号）。Go 处理器赋值：Error = errMsg。
            // https://github.com/e2b-dev/infra/blob/main/packages/envd/internal/services/process/handler/handler.go
            // Python SDK 读取：error=event.event.end.error
            // https://github.com/e2b-dev/e2b/blob/main/packages/python-sdk/e2b/sandbox_sync/commands/command_handle.py#L115
            JsonNode errorNode = endNode.path("error");
            if (errorNode.isTextual()) {
                endBuilder.setField(errorField, errorNode.textValue());
            }
            // exit_code 是隐式存在的 proto3 标量；存在 EndEvent 时，
            // 省略值表示默认值 0。即使消息没有字段也应保留。
            event.setField(processEventDesc.findFieldByName("end"), endBuilder.build());
        }

        if (!event.getAllFields().isEmpty()) {
            response.setField(startResponseDesc.findFieldByName("event"), event.build());
        }
        return response.build();
    }

    // EndEvent.exit_code 是 int32。官方 Python/JS SDK 使用的 ProtoJSON 接受
    // JSON 数字或十进制字符串，要求小数部分为零，并拒绝
    // 超出 int32 范围的值。https://protobuf.dev/programming-guides/json/
    private static int parseExitCode(JsonNode node) throws IOException {
        String text = node.isTextual() ? node.textValue() : node.isNumber() ? node.asText() : null;
        if (text == null) {
            throw new IOException("Invalid EndEvent exit_code: " + node);
        }
        try {
            return new BigDecimal(text).intValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IOException("Invalid EndEvent exit_code: " + node);
        }
    }

    private static void setBytesIfPresent(
            DynamicMessage.Builder builder,
            JsonNode parent,
            String jsonField,
            Descriptors.FieldDescriptor field) {
        JsonNode valueNode = parent.path(jsonField);
        if (valueNode.isTextual()) {
            try {
                builder.setField(
                        field,
                        ByteString.copyFrom(Base64.getDecoder().decode(valueNode.textValue())));
            } catch (IllegalArgumentException ignored) {
                // 服务端格式错误的 base64 不应中止流。
            }
        }
    }

    private static String stringField(
            DynamicMessage message, Descriptors.FieldDescriptor field, String defaultValue) {
        if (field == null || !message.hasField(field)) {
            return defaultValue;
        }
        return String.valueOf(message.getField(field));
    }

    private String envdHost(E2bSandboxState state) {
        String override = opt.getEnvdSandboxUrl();
        if (override != null && !override.isBlank()) {
            return E2bPlatformHttp.trimSlash(override.trim());
        }
        String id = state.getSandboxId();
        String dom =
                state.getSandboxDomain() != null && !state.getSandboxDomain().isBlank()
                        ? state.getSandboxDomain()
                        : "e2b.app";
        return "https://" + ENVD_PORT + "-" + id + "." + dom;
    }

    private String basicAuthUser() {
        String u = effectiveRunUser();
        String token =
                Base64.getEncoder().encodeToString((u + ":").getBytes(StandardCharsets.UTF_8));
        return "Basic " + token;
    }

    private String effectiveRunUser() {
        String u = opt.getRunUser();
        return u != null ? u : "user";
    }

    private static String truncateUtf8(byte[] b) {
        int n = Math.min(b.length, OUTPUT_TRUNCATE_BYTES);
        return new String(b, 0, n, StandardCharsets.UTF_8);
    }
}
