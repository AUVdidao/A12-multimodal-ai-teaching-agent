package com.auvdidao.a12teachingagent.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VectorCodecTest {

    private final VectorCodec codec = new VectorCodec(new ObjectMapper());

    @Test
    void roundTripsVectorAsJsonNumericArray() {
        List<Double> vector = List.of(0.12, -0.35, 1.0);

        String json = codec.encode(vector);

        assertThat(json).isEqualTo("[0.12,-0.35,1.0]");
        assertThat(codec.decode(json)).containsExactlyElementsOf(vector);
    }

    @Test
    void rejectsInvalidJsonAndNonArrayValues() {
        assertThatThrownBy(() -> codec.decode("not-json"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode("{\"value\":1}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode("[1] trailing"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNaNInfinityAndEmptyVectors() {
        assertThatThrownBy(() -> codec.encode(List.of(Double.NaN)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.encode(List.of(Double.POSITIVE_INFINITY)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode("[1e999]"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.encode(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
