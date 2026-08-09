package com.auvdidao.a12teachingagent.generation;

import com.auvdidao.a12teachingagent.common.api.ApiResponse;
import com.auvdidao.a12teachingagent.domain.common.ArtifactType;
import com.auvdidao.a12teachingagent.generation.dto.GenerationDtos.ArtifactGenerationRequest;
import com.auvdidao.a12teachingagent.generation.dto.GenerationDtos.ArtifactResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArtifactControllerContractTest {

    @Mock
    private GenerationService generationService;

    @Test
    void genericEndpointAlwaysReturnsArtifactArrayAndForwardsExplicitTypes() {
        when(generationService.generateArtifacts(eq(1L), org.mockito.ArgumentMatchers.any(ArtifactGenerationRequest.class)))
                .thenReturn(List.of());
        ArtifactController controller = new ArtifactController(generationService);

        ApiResponse<List<ArtifactResponse>> response = controller.generate(
                1L,
                new ArtifactGenerationRequest(7L, List.of(ArtifactType.DOCX, ArtifactType.INTERACTION))
        );

        assertThat(response.data()).isEmpty();
        ArgumentCaptor<ArtifactGenerationRequest> request = ArgumentCaptor.forClass(ArtifactGenerationRequest.class);
        verify(generationService).generateArtifacts(eq(1L), request.capture());
        assertThat(request.getValue().artifactTypes()).containsExactly(ArtifactType.DOCX, ArtifactType.INTERACTION);
    }
}
