package com.auvdidao.a12teachingagent.domain;

import com.auvdidao.a12teachingagent.domain.common.TeachingIntentStatus;
import com.auvdidao.a12teachingagent.domain.generation.TeachingIntent;
import com.auvdidao.a12teachingagent.domain.generation.repository.TeachingIntentRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class TeachingIntentRepositoryTest {

    @Autowired
    private TeachingIntentRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void persistsLongAiGeneratedInteractionDetails() {
        String interactionDetails = "Prediction, observation, discussion, and feedback. ".repeat(8);
        assertThat(interactionDetails.length()).isGreaterThan(255);

        TeachingIntent intent = new TeachingIntent();
        intent.setProjectId(71L);
        intent.setInteractionMode(interactionDetails);
        intent.setTeachingFormat(interactionDetails);
        intent.setStatus(TeachingIntentStatus.DRAFT);

        TeachingIntent saved = repository.saveAndFlush(intent);
        entityManager.clear();

        TeachingIntent reloaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getInteractionMode()).isEqualTo(interactionDetails);
        assertThat(reloaded.getTeachingFormat()).isEqualTo(interactionDetails);
    }
}
