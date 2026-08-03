package com.auvdidao.a12teachingagent.ai.credential;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiApiCredentialRepository extends JpaRepository<AiApiCredentialEntity, Long> {
    List<AiApiCredentialEntity> findAllByOwnerUserIdAndProviderOrderByKeySlotAsc(Long ownerUserId, String provider);

    Optional<AiApiCredentialEntity> findByOwnerUserIdAndProviderAndKeySlot(Long ownerUserId, String provider, int keySlot);

    Optional<AiApiCredentialEntity> findByOwnerUserIdAndProviderAndActiveTrue(Long ownerUserId, String provider);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AiApiCredentialEntity c where c.ownerUserId = :ownerUserId and c.provider = :provider order by c.keySlot")
    List<AiApiCredentialEntity> lockAllByOwnerUserIdAndProvider(@Param("ownerUserId") Long ownerUserId,
                                                                  @Param("provider") String provider);

    List<AiApiCredentialEntity> findAllByOwnerUserIdOrderByKeySlotAsc(Long ownerUserId);

    Optional<AiApiCredentialEntity> findByOwnerUserIdAndKeySlot(Long ownerUserId, int keySlot);

    Optional<AiApiCredentialEntity> findByOwnerUserIdAndActiveTrue(Long ownerUserId);
}
