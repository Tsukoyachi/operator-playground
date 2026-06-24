package com.example.operator.crd.app;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Version;

@Group("com.example.operator")
@Version("v1")
public class ApplicationResource extends CustomResource<ApplicationSpec, ApplicationStatus> implements Namespaced {
}
