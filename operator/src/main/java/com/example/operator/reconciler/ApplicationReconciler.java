package com.example.operator.reconciler;

import com.example.operator.crd.ApplicationResource;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.*;
import io.javaoperatorsdk.operator.processing.event.source.EventSource;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ControllerConfiguration
public class ApplicationReconciler implements Reconciler<ApplicationResource> {

    private final KubernetesClient client;

    public ApplicationReconciler(KubernetesClient client) {
        this.client = client;
    }

    @Override
    public UpdateControl<ApplicationResource> reconcile(ApplicationResource resource, Context<ApplicationResource> context) throws Exception {
        String name = resource.getMetadata().getName();
        String namespace = resource.getMetadata().getNamespace();
        System.out.printf("Reconciling ApplicationResource: %s/%s%n", namespace, name);

        String image = resource.getSpec().getApplicationServerImage() + ":" + resource.getSpec().getApplicationServerVersion();
        System.out.printf("Application image: %s%n", image);

        Deployment deployment = createDeployment(resource, image);

        client.apps().deployments().inNamespace(namespace).resource(deployment).serverSideApply();

        return UpdateControl.noUpdate();
    }

    @Override
    public List<EventSource<?, ApplicationResource>> prepareEventSources(EventSourceContext<ApplicationResource> context) {
        return Reconciler.super.prepareEventSources(context);
    }

    @Override
    public ErrorStatusUpdateControl<ApplicationResource> updateErrorStatus(ApplicationResource resource, Context<ApplicationResource> context, Exception e) {
        return Reconciler.super.updateErrorStatus(resource, context, e);
    }

    private Deployment createDeployment(ApplicationResource resource, String image) {

        String name = resource.getMetadata().getName();

        return new DeploymentBuilder().withNewMetadata().withName(name).endMetadata().withNewSpec().withReplicas(1).withNewSelector().addToMatchLabels("app", name).endSelector().withNewTemplate().withNewMetadata().addToLabels("app", name).endMetadata().withNewSpec().addNewContainer().withName("app").withImage(image).endContainer().endSpec().endTemplate().endSpec().build();
    }
}
