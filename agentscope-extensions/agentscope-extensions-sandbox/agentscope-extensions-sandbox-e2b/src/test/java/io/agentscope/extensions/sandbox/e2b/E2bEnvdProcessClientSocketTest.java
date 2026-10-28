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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.SandboxException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 基于真实 HTTP 栈的套接字级超时覆盖。
 *
 * <p>应用层拦截器抛错会在 OkHttp 超时机制介入前短路，因此 {@code E2bEnvdProcessClientTest} 无法验证 {@code readTimeout(0)} 或客户端备用计时。{@code envdHost} 固定 envd 地址，通过 URL 重写拦截器指向本地服务，同时保留真实网络栈，让超时仍由 OkHttp 执行。
 */
class E2bEnvdProcessClientSocketTest {

    // 故意大于 BASE_READ_TIMEOUT_SECONDS；逐调用客户端若继承
    // 基础读取超时，会约在 1 秒时失败，使下方耗时
    // 断言失败，而不是静默通过。
    private static final int TIMEOUT_SECONDS = 3;
    private static final int BASE_READ_TIMEOUT_SECONDS = 1;
    // 足够短以捕获 timeout=0 路径继承 callTimeout 的错误，又足够长，
    // 不与已覆盖它的有界情况竞争。
    private static final int BASE_CALL_TIMEOUT_SECONDS = 2;

    private MockWebServer server;
    private E2bEnvdProcessClient client;

    @BeforeEach
    void startServer() throws Exception {
        server = new MockWebServer();
        server.start();

        // 只重写目标地址，仍经过真实网络栈，
        // callTimeout 和 readTimeout 与访问 envd 时行为相同。
        Interceptor redirectToLocalServer =
                chain -> {
                    Request original = chain.request();
                    HttpUrl rewritten =
                            original.url()
                                    .newBuilder()
                                    .scheme("http")
                                    .host(server.getHostName())
                                    .port(server.getPort())
                                    .build();
                    return chain.proceed(original.newBuilder().url(rewritten).build());
                };

        OkHttpClient http =
                new OkHttpClient.Builder()
                        .addInterceptor(redirectToLocalServer)
                        // 故意远小于 T；若逐调用客户端继承基础
                        // 读取超时，空闲套接字会约在 1 秒时抛 SocketTimeoutException，
                        // 早于 T 或客户端备用超时。
                        .readTimeout(BASE_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        // 同时配置基础调用超时；若不显式 callTimeout(0)，
                        // timeout=0 调用会继承它，不再无限等待。
                        .callTimeout(BASE_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .build();

        E2bSandboxClientOptions opt = new E2bSandboxClientOptions();
        opt.setCodec(E2bCodec.PROTO);
        opt.setHttpClient(http);
        client = new E2bEnvdProcessClient(opt);
    }

    @AfterEach
    void stopServer() throws Exception {
        server.shutdown();
    }

    @Test
    void idleSocketIsNotKilledByInheritedReadTimeout() throws Exception {
        // 开始事件到达后，套接字静默超过 T。readTimeout(0) 让调用
        // 跨过该间隔，由 T 加 5 秒余量的客户端备用超时结束；
        // 若继承基础读取超时，则约 1 秒即抛 SocketTimeoutException。
        server.enqueue(stalledResponse(startFrame(4242)));

        long start = System.nanoTime();
        SandboxException.ExecTimeoutException e =
                assertThrows(
                        SandboxException.ExecTimeoutException.class,
                        () -> client.runShell(state(), "/workspace", "echo hi", TIMEOUT_SECONDS));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertEquals("Command timed out after " + TIMEOUT_SECONDS + "s", e.getMessage());
        // 存活超过 T 说明只有 T 加余量的备用超时能结束调用，
        // 空闲套接字未被继承的读取超时终止。
        // BASE_READ_TIMEOUT_SECONDS 远小于 T，撤回 readTimeout(0) 会使测试失败。
        assertTrue(
                elapsedMs >= TIMEOUT_SECONDS * 1000L,
                "call must outlive T, got " + elapsedMs + "ms");
    }

    @Test
    void clientBackupFailsafeEndsHungSocket() throws Exception {
        // 服务端接受请求但始终不响应；客户端备用超时
        // 仍必须在 T 加 5 秒内给出类型正确的有界失败。
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        long start = System.nanoTime();
        assertThrows(
                SandboxException.ExecTimeoutException.class,
                () -> client.runShell(state(), "/workspace", "sleep 1000", TIMEOUT_SECONDS));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(elapsedMs >= TIMEOUT_SECONDS * 1000L, "returned before T: " + elapsedMs + "ms");
        assertTrue(elapsedMs < 30_000, "client backup did not bound the call: " + elapsedMs + "ms");
    }

    @Test
    void zeroTimeoutHasNoClientDeadline() throws Exception {
        // 基础客户端读取超时为 1 秒、调用超时为 2 秒；timeout=0 必须
        // 覆盖两者，服务端延迟 3 秒发送正文后仍应成功。
        server.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/connect+proto")
                        .setBody(new Buffer().write(dataFrame(protoExitEvent(0))))
                        .setBodyDelay(3, TimeUnit.SECONDS));

        long start = System.nanoTime();
        ExecResult r = client.runShell(state(), "/workspace", "echo hi", 0);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertEquals(0, r.exitCode());
        assertTrue(
                elapsedMs >= 2500, "call must outlive the base timeouts, got " + elapsedMs + "ms");
    }

    @Test
    void normalStreamOverRealSocketSucceeds() throws Exception {
        server.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/connect+proto")
                        .setBody(new Buffer().write(dataFrame(protoExitEvent(0)))));

        ExecResult r = client.runShell(state(), "/workspace", "echo hi", 30);

        assertEquals(0, r.exitCode());
        assertEquals("", r.stdout());
    }

    private static MockResponse stalledResponse(byte[] partialBody) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/connect+proto")
                .setBody(new Buffer().write(partialBody))
                .setSocketPolicy(SocketPolicy.NO_RESPONSE);
    }

    private static byte[] startFrame(int pid) {
        return dataFrame(
                ("{\"event\":{\"start\":{\"pid\":" + pid + "}}}").getBytes(StandardCharsets.UTF_8));
    }

    /** 携带结束事件的 PROTO 编码 {@code StartResponse}，由客户端自身描述符构建，而不是手写传输字节。 */
    private byte[] protoExitEvent(int exitCode) {
        Descriptors.FileDescriptor fd = client.fileDescriptor();
        Descriptors.Descriptor startResponseDesc = fd.findMessageTypeByName("StartResponse");
        Descriptors.Descriptor processEventDesc = fd.findMessageTypeByName("ProcessEvent");
        Descriptors.Descriptor endDesc = processEventDesc.findNestedTypeByName("EndEvent");

        DynamicMessage end =
                DynamicMessage.newBuilder(endDesc)
                        .setField(endDesc.findFieldByName("exit_code"), exitCode)
                        .build();
        DynamicMessage event =
                DynamicMessage.newBuilder(processEventDesc)
                        .setField(processEventDesc.findFieldByName("end"), end)
                        .build();
        return DynamicMessage.newBuilder(startResponseDesc)
                .setField(startResponseDesc.findFieldByName("event"), event)
                .build()
                .toByteArray();
    }

    private static byte[] dataFrame(byte[] payload) {
        return frame(0x00, payload);
    }

    private static byte[] frame(int flags, byte[] payload) {
        byte[] out = new byte[5 + payload.length];
        out[0] = (byte) flags;
        out[1] = (byte) (payload.length >>> 24);
        out[2] = (byte) (payload.length >>> 16);
        out[3] = (byte) (payload.length >>> 8);
        out[4] = (byte) payload.length;
        System.arraycopy(payload, 0, out, 5, payload.length);
        return out;
    }

    private static E2bSandboxState state() {
        E2bSandboxState state = new E2bSandboxState();
        state.setSandboxId("socket-test-sandbox");
        return state;
    }
}
