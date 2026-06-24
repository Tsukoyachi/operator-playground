package com.example.operator.crd.app;

public record ApplicationSpec(
        String image,
        String version,
        String configurationRef,
        String infrastructureRef
) {
}
