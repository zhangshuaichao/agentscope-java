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
package io.agentscope.builder.web.managed.service;

import io.agentscope.builder.web.managed.ResourceShareDto;
import io.agentscope.builder.web.managed.VaultCredentialDto;
import io.agentscope.builder.web.managed.VaultDto;
import io.agentscope.builder.web.persistence.jpa.VaultCredentialEntity;
import io.agentscope.builder.web.persistence.jpa.VaultCredentialEntityRepository;
import io.agentscope.builder.web.persistence.jpa.VaultEntity;
import io.agentscope.builder.web.persistence.jpa.VaultEntityRepository;
import io.agentscope.builder.web.share.AgentAclService.Tier;
import io.agentscope.builder.web.share.ResourceAccessService;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 凭据密钥库的增删改查，秘密值加密存储。 */
@Service
@Transactional
public class VaultService {

    /** {@link ResourceAccessService} 共享授权使用的资源类型键。 */
    public static final String RESOURCE_TYPE = "vault";

    /** 创建密钥库的请求体。 */
    public record CreateVaultRequest(String displayName, Map<String, Object> metadata) {}

    /** 添加凭据的请求体。 */
    public record AddCredentialRequest(String type, String label, String target, String secret) {}

    private final VaultEntityRepository vaultRepository;
    private final VaultCredentialEntityRepository credentialRepository;
    private final VaultCrypto vaultCrypto;
    private final ManagedJsonHelper jsonHelper;
    private final ResourceAccessService resourceAccessService;

    public VaultService(
            VaultEntityRepository vaultRepository,
            VaultCredentialEntityRepository credentialRepository,
            VaultCrypto vaultCrypto,
            ManagedJsonHelper jsonHelper,
            ResourceAccessService resourceAccessService) {
        this.vaultRepository = vaultRepository;
        this.credentialRepository = credentialRepository;
        this.vaultCrypto = vaultCrypto;
        this.jsonHelper = jsonHelper;
        this.resourceAccessService = resourceAccessService;
    }

    /** 列出用户拥有的密钥库。 */
    @Transactional(readOnly = true)
    public List<VaultDto> list(String ownerId) {
        return vaultRepository.findByOwnerIdOrderByCreatedAtAsc(ownerId).stream()
                .map(this::toVaultDto)
                .toList();
    }

    /** 调用方拥有或获共享授权（至少 RUN）的单个密钥库。 */
    @Transactional(readOnly = true)
    public VaultDto get(String ownerId, String vaultId) {
        return toVaultDto(requireAccess(ownerId, vaultId, Tier.RUN));
    }

    /** 创建密钥库。 */
    public VaultDto create(String ownerId, CreateVaultRequest request) {
        if (request.displayName() == null || request.displayName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "displayName is required");
        }
        long now = System.currentTimeMillis();
        VaultEntity entity = new VaultEntity();
        entity.setVaultId(ManagedJsonHelper.randomId("vlt_"));
        entity.setOwnerId(ownerId);
        entity.setDisplayName(request.displayName());
        entity.setMetadataJson(jsonHelper.writeJson(request.metadata()));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return toVaultDto(vaultRepository.save(entity));
    }

    /** 删除密钥库及所有凭据。 */
    public void delete(String ownerId, String vaultId) {
        requireAccess(ownerId, vaultId, Tier.EDIT);
        credentialRepository.deleteByVaultId(vaultId);
        vaultRepository.findByVaultId(vaultId).ifPresent(vaultRepository::delete);
    }

    /** 列出密钥库的凭据元数据（不含明文）。 */
    @Transactional(readOnly = true)
    public List<VaultCredentialDto> listCredentials(String ownerId, String vaultId) {
        requireAccess(ownerId, vaultId, Tier.RUN);
        return credentialRepository.findByVaultIdOrderByCreatedAtAsc(vaultId).stream()
                .map(this::toCredentialDto)
                .toList();
    }

    /** 向密钥库添加加密凭据。 */
    public VaultCredentialDto addCredential(
            String ownerId, String vaultId, AddCredentialRequest request) {
        requireAccess(ownerId, vaultId, Tier.EDIT);
        if (request.type() == null || request.type().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type is required");
        }
        if (request.secret() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "secret is required");
        }
        long now = System.currentTimeMillis();
        VaultCredentialEntity entity = new VaultCredentialEntity();
        entity.setCredentialId(ManagedJsonHelper.randomId("cred_"));
        entity.setVaultId(vaultId);
        entity.setCredentialType(request.type());
        entity.setLabel(request.label());
        entity.setTarget(request.target());
        entity.setCiphertext(vaultCrypto.encrypt(request.secret()));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        VaultCredentialDto dto = toCredentialDto(credentialRepository.save(entity));
        touchVault(vaultId, now);
        return dto;
    }

    /** 从密钥库删除凭据。 */
    public void deleteCredential(String ownerId, String vaultId, String credentialId) {
        requireAccess(ownerId, vaultId, Tier.EDIT);
        VaultCredentialEntity credential =
                credentialRepository
                        .findByCredentialId(credentialId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Credential not found: " + credentialId));
        if (!vaultId.equals(credential.getVaultId())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Credential not found in vault: " + credentialId);
        }
        credentialRepository.delete(credential);
        touchVault(vaultId, System.currentTimeMillis());
    }

    /** 返回解密后的秘密 JSON，供内部 MCP 注入使用。 */
    @Transactional(readOnly = true)
    public String getDecryptedSecret(String ownerId, String credentialId) {
        VaultCredentialEntity credential =
                credentialRepository
                        .findByCredentialId(credentialId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Credential not found: " + credentialId));
        requireAccess(ownerId, credential.getVaultId(), Tier.RUN);
        return vaultCrypto.decrypt(credential.getCiphertext());
    }

    /** 列出密钥库上的共享授权。仅限所有者/EDIT 级别。 */
    @Transactional(readOnly = true)
    public List<ResourceShareDto> listShares(String ownerId, String vaultId) {
        requireAccess(ownerId, vaultId, Tier.EDIT);
        return resourceAccessService.listShares(RESOURCE_TYPE, vaultId);
    }

    /** 将密钥库共享给用户或整个工作区。仅限所有者/EDIT 级别。 */
    public ResourceShareDto share(
            String ownerId, String vaultId, String granteeType, String granteeId, Tier tier) {
        VaultEntity vault = requireAccess(ownerId, vaultId, Tier.EDIT);
        return resourceAccessService.share(
                RESOURCE_TYPE, vaultId, vault.getOwnerId(), granteeType, granteeId, tier, ownerId);
    }

    /** 撤销密钥库上的共享授权。仅限所有者/EDIT 级别。 */
    public void unshare(String ownerId, String vaultId, String shareId) {
        requireAccess(ownerId, vaultId, Tier.EDIT);
        resourceAccessService.unshare(RESOURCE_TYPE, vaultId, shareId);
    }

    /** 解析密钥库并验证 {@code callerId} 至少具有 {@code required} 权限。 */
    private VaultEntity requireAccess(String callerId, String vaultId, Tier required) {
        VaultEntity vault =
                vaultRepository
                        .findByVaultId(vaultId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Vault not found: " + vaultId));
        resourceAccessService.require(
                callerId, vault.getOwnerId(), RESOURCE_TYPE, vaultId, required);
        return vault;
    }

    private void touchVault(String vaultId, long updatedAt) {
        vaultRepository
                .findByVaultId(vaultId)
                .ifPresent(
                        v -> {
                            v.setUpdatedAt(updatedAt);
                            vaultRepository.save(v);
                        });
    }

    private VaultDto toVaultDto(VaultEntity entity) {
        return new VaultDto(
                entity.getVaultId(),
                entity.getOwnerId(),
                entity.getDisplayName(),
                jsonHelper.readMap(entity.getMetadataJson()),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private VaultCredentialDto toCredentialDto(VaultCredentialEntity entity) {
        return new VaultCredentialDto(
                entity.getCredentialId(),
                entity.getCredentialType(),
                entity.getLabel(),
                entity.getTarget(),
                entity.getCreatedAt());
    }
}
