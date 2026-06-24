package com.example.operator.crd.infra;

public record InfrastructureSpec(
        Integer replicas,
        String ingressHost,
        String storageSize,
        String storageClassName,
        String secretName
) {}