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
package io.agentscope.extensions.sandbox.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.extensions.sandbox.kubernetes.client.crd.SandboxClaim;
import io.fabric8.kubernetes.api.model.FieldsV1;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ManagedFieldsEntry;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 保护 CRD watch 路径免受 fabric8/Jackson 序列化回归：{@code AbstractWatchManager.eventReceived} 经 {@code KubernetesSerialization.convertValue} 将无类型 {@code GenericKubernetesResource} 转为目标自定义资源。fabric8 6.13.x 配合 Jackson >= 2.19 曾在 {@code metadata.managedFields[].fieldsV1} 触发 {@code keySerializer is
 * null} 空指针；对应 fabric8 #7036，在 7.3.0 修复。
 */
class KubernetesSerializationCompatTest {

    @Test
    void convertValueWithManagedFieldsFieldsV1ShouldNotThrow() {
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            GenericKubernetesResource gkr = new GenericKubernetesResource();
            ObjectMeta metadata = new ObjectMeta();
            metadata.setName("sandboxclaim-test");

            ManagedFieldsEntry managedFieldsEntry = new ManagedFieldsEntry();
            FieldsV1 fieldsV1 = new FieldsV1();
            Map<String, Object> additional = new LinkedHashMap<>();
            additional.put("f:metadata", Map.of("f:name", Map.of()));
            fieldsV1.setAdditionalProperties(additional);
            managedFieldsEntry.setFieldsV1(fieldsV1);
            metadata.setManagedFields(List.of(managedFieldsEntry));
            gkr.setMetadata(metadata);

            SandboxClaim claim =
                    client.getKubernetesSerialization().convertValue(gkr, SandboxClaim.class);
            assertEquals("sandboxclaim-test", claim.getMetadata().getName());
        }
    }
}
