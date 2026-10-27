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
package io.agentscope.extensions.model.dashscope.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DashScope 公钥响应 DTO。
 *
 * <p>表示公钥 API 的响应结构。
 *
 * <p>JSON 示例：
 * <pre>{@code
 * {
 *   "request_id": "xxx-xxx-xxx",
 *   "data": {
 *     "public_key": "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAnojrB579xgPQN5f46SvoRAiQBPWBaPzWh7hp51fWI+OsQk7KqH0qMcw8i0eK5rfOvJIPujOQgnes1ph9/gKAst9NzXVIl9JJYUSPtzTvOabhp4yvS3KBf9g3xHYVjYgW33SOY74Ue/tgbCXn717rV6gXb4sVvq9XK/1BrDcGbEOQEZEgBTFkm/g3lpWLQtACwwqHffoA9eQtkkz15ZFKosAgbR8LedfIvxAl2zk15REzxXiRcFgc9/tLF0U1t2Sxt9FkQefxYwn6EZawTsRJvf4kqF3MaPdTcDbOp0iSNvCl2qzPSf/F+Oll2CUM1tFAEu81oa4l0WaDR3UtvqOtyQIDAQAB",
 *     "public_key_id": "1"
 *   }
 * }
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class DashScopePublicKeyResponse {

    /**
     * 唯一请求标识。
     */
    @JsonProperty("request_id")
    private String requestId;

    /**
     * 公钥数据。
     */
    @JsonProperty("data")
    private PublicKeyData data;

    /**
     * 错误代码（请求失败时）。
     */
    @JsonProperty("code")
    private String code;

    /**
     * 错误消息（请求失败时）。
     */
    @JsonProperty("message")
    private String message;

    public DashScopePublicKeyResponse() {}

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public PublicKeyData getData() {
        return data;
    }

    public void setData(PublicKeyData data) {
        this.data = data;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    /**
     * 检查此响应是否表示错误。
     *
     * @return 包含错误代码时为 true
     */
    public boolean isError() {
        return code != null && !code.isEmpty();
    }

    /**
     * 包含公钥 ID 与公钥值的数据。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PublicKeyData {

        /**
         * RSA 公钥值（Base64 编码）。
         */
        @JsonProperty("public_key")
        private String publicKey;

        /**
         * RSA 公钥 ID。
         */
        @JsonProperty("public_key_id")
        private String publicKeyId;

        public PublicKeyData() {}

        public String getPublicKey() {
            return publicKey;
        }

        public void setPublicKey(String publicKey) {
            this.publicKey = publicKey;
        }

        public String getPublicKeyId() {
            return publicKeyId;
        }

        public void setPublicKeyId(String publicKeyId) {
            this.publicKeyId = publicKeyId;
        }
    }
}
