package com.auvdidao.a12teachingagent.knowledge;

import java.util.List;

public record DenseSearchQuery(
        Long projectId,
        String provider,
        String model,
        List<Double> queryVector,
        Integer limit
) {
}
