package com.auvdidao.a12teachingagent.ai.credential;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.auvdidao.a12teachingagent.security.AuthenticatedUser;
import com.auvdidao.a12teachingagent.security.CurrentUserService;
import com.auvdidao.a12teachingagent.common.exception.BadRequestException;
import com.auvdidao.a12teachingagent.domain.common.UserRole;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class AiApiCredentialService {

    private static final String PROVIDER = "KIMI";

    private final AiApiCredentialRepository repository;
    private final AiCredentialCryptoService cryptoService;
    private final CurrentUserService currentUserService;

    public AiApiCredentialService(
            AiApiCredentialRepository repository,
            AiCredentialCryptoService cryptoService,
            CurrentUserService currentUserService
    ) {
        this.repository = repository;
        this.cryptoService = cryptoService;
        this.currentUserService = currentUserService;
    }

    @Transactional(readOnly = true)
    public CredentialsView view() {
        AuthenticatedUser user = requireManageableUser();
        List<AiApiCredentialEntity> stored = repository.findAllByOwnerUserIdAndProviderOrderByKeySlotAsc(user.userId(), PROVIDER);
        List<CredentialView> views = new ArrayList<>();
        for (int slot = 1; slot <= 3; slot++) {
            int keySlot = slot;
            AiApiCredentialEntity entity = stored.stream()
                    .filter(item -> item.getKeySlot() == keySlot)
                    .findFirst()
                    .orElse(null);
            views.add(entity == null
                    ? new CredentialView(slot, false, false, null, null)
                    : new CredentialView(slot, true, entity.isActive(), entity.getKeyHint(), entity.getUpdatedAt()));
        }
        return new CredentialsView(PROVIDER, views);
    }

    @Transactional
    public CredentialsView save(SaveCredentialsRequest request) {
        AuthenticatedUser user = requireManageableUser();
        if (request.keys() == null || request.keys().size() != 3 || request.activeSlot() < 1 || request.activeSlot() > 3) {
            throw new BadRequestException("Exactly three credential slots and an active slot from 1 to 3 are required");
        }

        List<String> normalizedKeys = request.keys().stream()
                .map(value -> value == null ? "" : value.strip())
                .toList();
        if (normalizedKeys.stream().anyMatch(value -> !StringUtils.hasText(value))
                || normalizedKeys.size() != new HashSet<>(normalizedKeys).size()) {
            throw new BadRequestException("All three Kimi credentials are required and must be different");
        }

        List<AiApiCredentialEntity> entities = new ArrayList<>();
        for (int index = 0; index < request.keys().size(); index++) {
            int slot = index + 1;
            String normalized = normalizedKeys.get(index);
            AiApiCredentialEntity entity = repository.findByOwnerUserIdAndProviderAndKeySlot(user.userId(), PROVIDER, slot)
                    .orElseGet(() -> newEntity(user.userId(), slot));
            entity.setEncryptedValue(cryptoService.encrypt(normalized));
            entity.setKeyHint(cryptoService.hint(normalized));
            entities.add(entity);
        }

        repository.saveAll(entities);
        List<AiApiCredentialEntity> locked = repository.lockAllByOwnerUserIdAndProvider(user.userId(), PROVIDER);
        locked.forEach(entity -> {
            entity.setActive(entity.getKeySlot() == request.activeSlot());
        });
        repository.saveAll(locked);
        return view();
    }

    @Transactional
    public String activeApiKey() {
        return currentUserService.currentUser().map(user -> activeApiKey(user.userId())).orElse("");
    }

    @Transactional
    public String activeApiKey(Long userId) {
        return repository.findByOwnerUserIdAndProviderAndActiveTrue(userId, PROVIDER)
                .map(entity -> {
                    entity.setLastUsedAt(LocalDateTime.now());
                    String value = cryptoService.decrypt(entity.getEncryptedValue());
                    repository.save(entity);
                    return value;
                })
                .orElse("");
    }

    @Transactional(readOnly = true)
    public boolean hasActiveCredential() {
        return currentUserService.currentUser()
                .flatMap(user -> repository.findByOwnerUserIdAndProviderAndActiveTrue(user.userId(), PROVIDER))
                .isPresent();
    }

    private AuthenticatedUser requireManageableUser() {
        return currentUserService.requireRole(UserRole.TEACHER, UserRole.LEADER);
    }

    private AiApiCredentialEntity newEntity(Long userId, int slot) {
        AiApiCredentialEntity entity = new AiApiCredentialEntity();
        entity.setOwnerUserId(userId);
        entity.setKeySlot(slot);
        entity.setProvider(PROVIDER);
        entity.setKeyVersion(1);
        entity.setActive(false);
        return entity;
    }

    public record CredentialView(int slot, boolean configured, boolean active, String maskedKey, LocalDateTime updatedAt) {
    }

    public record CredentialsView(String provider, List<CredentialView> credentials) {
    }

    public record SaveCredentialsRequest(
            @NotNull @Size(min = 3, max = 3) List<String> keys,
            @Min(1) @Max(3) int activeSlot
    ) {
    }
}
