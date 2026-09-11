package com.auvdidao.a12teachingagent.ai.connection;

import com.auvdidao.a12teachingagent.ai.transport.OpenAiCompatibleTransport;
import com.auvdidao.a12teachingagent.ai.transport.TransportFailureException;
import com.auvdidao.a12teachingagent.common.exception.BadRequestException;
import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import com.auvdidao.a12teachingagent.common.exception.ResourceNotFoundException;
import com.auvdidao.a12teachingagent.domain.common.UserRole;
import com.auvdidao.a12teachingagent.security.AuthenticatedUser;
import com.auvdidao.a12teachingagent.security.CurrentUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;

import static com.auvdidao.a12teachingagent.ai.connection.ModelConnectionDtos.*;

@Service
public class ModelConnectionService {
    private final ModelConnectionRepository repository;
    private final com.auvdidao.a12teachingagent.ai.credential.AiCredentialCryptoService cryptoService;
    private final CurrentUserService currentUserService;
    private final OpenAiCompatibleTransport transport;

    public ModelConnectionService(ModelConnectionRepository repository,
                                  com.auvdidao.a12teachingagent.ai.credential.AiCredentialCryptoService cryptoService,
                                  CurrentUserService currentUserService,
                                  OpenAiCompatibleTransport transport) {
        this.repository = repository;
        this.cryptoService = cryptoService;
        this.currentUserService = currentUserService;
        this.transport = transport;
    }

    @Transactional(readOnly = true)
    public List<ConnectionView> list() {
        return repository.findAllByOwnerUserIdOrderByCreatedAtAscIdAsc(requireManageableUser().userId())
                .stream().map(this::view).toList();
    }

    @Transactional
    public ConnectionView create(CreateRequest request) {
        AuthenticatedUser user = requireManageableUser();
        validateRequest(request.name(), request.protocol(), request.baseUrl(), request.apiKey(), request.modelId());
        if (repository.existsByOwnerUserIdAndNameIgnoreCase(user.userId(), request.name().strip())) {
            throw new ConflictException("MODEL_CONNECTION_NAME_EXISTS");
        }
        ModelConnectionEntity entity = new ModelConnectionEntity();
        entity.setOwnerUserId(user.userId());
        entity.setName(request.name().strip());
        entity.setProtocol(request.protocol());
        entity.setBaseUrl(OpenAiCompatibleTransport.normalizeBaseUrl(request.baseUrl()));
        entity.setModelId(request.modelId().strip());
        entity.setEncryptedApiKey(cryptoService.encrypt(request.apiKey().strip()));
        entity.setKeyHint(cryptoService.hint(request.apiKey().strip()));
        entity.setEnabled(true);
        entity.setVerificationStatus(ModelConnectionVerificationStatus.UNVERIFIED);
        return view(repository.save(entity));
    }

    @Transactional
    public ConnectionView update(Long id, UpdateRequest request) {
        ModelConnectionEntity entity = owned(id);
        validateRequest(request.name(), request.protocol(), request.baseUrl(),
                StringUtils.hasText(request.apiKey()) ? request.apiKey() : "retained-key", request.modelId());
        if (!entity.getName().equalsIgnoreCase(request.name().strip())
                && repository.existsByOwnerUserIdAndNameIgnoreCase(entity.getOwnerUserId(), request.name().strip())) {
            throw new ConflictException("MODEL_CONNECTION_NAME_EXISTS");
        }
        boolean endpointChanged = !entity.getBaseUrl().equals(OpenAiCompatibleTransport.normalizeBaseUrl(request.baseUrl()))
                || !entity.getModelId().equals(request.modelId().strip())
                || entity.getProtocol() != request.protocol();
        entity.setName(request.name().strip());
        entity.setProtocol(request.protocol());
        entity.setBaseUrl(OpenAiCompatibleTransport.normalizeBaseUrl(request.baseUrl()));
        entity.setModelId(request.modelId().strip());
        if (StringUtils.hasText(request.apiKey())) {
            entity.setEncryptedApiKey(cryptoService.encrypt(request.apiKey().strip()));
            entity.setKeyHint(cryptoService.hint(request.apiKey().strip()));
        }
        if (endpointChanged || StringUtils.hasText(request.apiKey())) {
            entity.setVerificationStatus(ModelConnectionVerificationStatus.UNVERIFIED);
            entity.setLastVerifiedAt(null);
        }
        return view(repository.save(entity));
    }

    @Transactional
    public void delete(Long id) { repository.delete(owned(id)); }

    @Transactional
    public ConnectionView setEnabled(Long id, boolean enabled) {
        ModelConnectionEntity entity = owned(id);
        entity.setEnabled(enabled);
        return view(repository.save(entity));
    }

    @Transactional
    public VerificationView verify(Long id) {
        ModelConnectionEntity entity = owned(id);
        try {
            ResolvedModelConnection resolved = resolveEntity(entity, false);
            OpenAiCompatibleTransport.TransportResponse response = transport.verify(resolved);
            entity.setVerificationStatus(ModelConnectionVerificationStatus.VERIFIED);
            entity.setLastVerifiedAt(LocalDateTime.now());
            repository.save(entity);
            return new VerificationView(entity.getId(), entity.getVerificationStatus(), "VERIFIED",
                    response.httpStatus(), host(entity.getBaseUrl()), entity.getModelId(), entity.getLastVerifiedAt());
        } catch (TransportFailureException failure) {
            entity.setVerificationStatus(ModelConnectionVerificationStatus.INVALID);
            entity.setLastVerifiedAt(LocalDateTime.now());
            repository.save(entity);
            return new VerificationView(entity.getId(), entity.getVerificationStatus(), failure.safeCode(),
                    failure.statusCode(), host(entity.getBaseUrl()), entity.getModelId(), entity.getLastVerifiedAt());
        } catch (RuntimeException failure) {
            entity.setVerificationStatus(ModelConnectionVerificationStatus.INVALID);
            entity.setLastVerifiedAt(LocalDateTime.now());
            repository.save(entity);
            return new VerificationView(entity.getId(), entity.getVerificationStatus(), "VERIFICATION_FAILED",
                    0, host(entity.getBaseUrl()), entity.getModelId(), entity.getLastVerifiedAt());
        }
    }

    @Transactional
    public ResolvedModelConnection resolveForExecution(Long ownerUserId, Long connectionId) {
        if (ownerUserId == null || connectionId == null) throw new BadRequestException("MODEL_CONNECTION_REQUIRED");
        ModelConnectionEntity entity = repository.findByIdAndOwnerUserId(connectionId, ownerUserId)
                .orElseThrow(() -> new ResourceNotFoundException("MODEL_CONNECTION_NOT_FOUND"));
        return resolveEntity(entity, true);
    }

    @Transactional
    public void markUsed(Long ownerUserId, Long connectionId) {
        repository.findByIdAndOwnerUserId(connectionId, ownerUserId).ifPresent(entity -> {
            entity.setLastUsedAt(LocalDateTime.now());
            repository.save(entity);
        });
    }

    private ResolvedModelConnection resolveEntity(ModelConnectionEntity entity, boolean requireVerified) {
        if (!entity.isEnabled()) throw new ConflictException("MODEL_CONNECTION_DISABLED");
        if (requireVerified && entity.getVerificationStatus() != ModelConnectionVerificationStatus.VERIFIED) {
            throw new ConflictException("MODEL_CONNECTION_NOT_VERIFIED");
        }
        try {
            return new ResolvedModelConnection(entity.getId(), entity.getOwnerUserId(), entity.getProtocol(),
                    entity.getBaseUrl(), entity.getModelId(), cryptoService.decrypt(entity.getEncryptedApiKey()));
        } catch (RuntimeException exception) {
            throw new ConflictException("MODEL_CONNECTION_CREDENTIAL_UNAVAILABLE");
        }
    }

    private ModelConnectionEntity owned(Long id) {
        if (id == null) throw new ResourceNotFoundException("MODEL_CONNECTION_NOT_FOUND");
        return repository.findByIdAndOwnerUserId(id, requireManageableUser().userId())
                .orElseThrow(() -> new ResourceNotFoundException("MODEL_CONNECTION_NOT_FOUND"));
    }

    private void validateRequest(String name, ModelConnectionProtocol protocol, String baseUrl, String apiKey, String modelId) {
        if (!StringUtils.hasText(name) || name.length() > 120 || protocol != ModelConnectionProtocol.OPENAI_COMPATIBLE
                || !StringUtils.hasText(apiKey) || apiKey.length() > 512 || !StringUtils.hasText(modelId) || modelId.length() > 128) {
            throw new BadRequestException("MODEL_CONNECTION_INVALID");
        }
        try { OpenAiCompatibleTransport.validateBaseUrl(baseUrl); }
        catch (RuntimeException exception) { throw new BadRequestException("MODEL_CONNECTION_BASE_URL_INVALID"); }
    }

    private ConnectionView view(ModelConnectionEntity entity) {
        return new ConnectionView(entity.getId(), entity.getName(), entity.getProtocol(), entity.getBaseUrl(), entity.getModelId(),
                entity.getKeyHint(), entity.isEnabled(), entity.getVerificationStatus(), entity.getLastVerifiedAt(),
                entity.getLastUsedAt(), entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private AuthenticatedUser requireManageableUser() { return currentUserService.requireRole(UserRole.TEACHER, UserRole.LEADER); }

    private static String host(String baseUrl) {
        try { return URI.create(baseUrl).getHost(); } catch (RuntimeException exception) { return "invalid"; }
    }
}
