package com.example.operator.crd.app;


import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
public class ApplicationStatus {
    private String state;
    private String message;

    private Instant lastReconcileTime;
}
