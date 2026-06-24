package com.example.operator.reconciler;

import com.example.operator.crd.app.ApplicationResource;
import com.example.operator.crd.app.ApplicationSpec;
import com.example.operator.crd.config.ConfigurationResource;
import com.example.operator.crd.infra.InfrastructureResource;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@ControllerConfiguration
public class ApplicationReconciler implements Reconciler<ApplicationResource> {

    private final KubernetesClient client;
    private static final String DEFAULT_IMAGE = "ghcr.io/tsukoyachi/operator-playground-app";

    public ApplicationReconciler(KubernetesClient client) {
        this.client = client;
    }

    @Override
    public UpdateControl<ApplicationResource> reconcile(
            ApplicationResource app,
            Context<ApplicationResource> context) {

        var spec = app.getSpec();

        // Load dependent CRs
        ConfigurationResource config = loadConfig(spec.configurationRef());
        InfrastructureResource infra = loadInfra(spec.infrastructureRef());

        // Validate
        // todo: Add sub cr validation before going here
        if (config == null || infra == null) {
            app.getStatus().setState("ERROR");
            app.getStatus().setMessage("Missing config or infra");
            return UpdateControl.patchStatus(app);
        }

        // Build desired state
        String image = buildImage(spec);

        reconcileConfigMap(app, config);
        reconcileSecret(app, infra);
        reconcilePVC(app, infra);
        reconcileDeployment(app, config, infra, image);
        reconcileService(app);
        reconcileIngress(app, infra);

        // Status update
        app.getStatus().setState("READY");

        return UpdateControl.patchStatus(app);
    }

    String buildImage(ApplicationSpec spec) {
        String image = Optional.ofNullable(spec.image())
                .filter(s -> !s.isBlank())
                .orElse(DEFAULT_IMAGE);

        String version = Optional.ofNullable(spec.version())
                .filter(s -> !s.isEmpty())
                .orElse("latest");

        return "%s:%s".formatted(image, version);
    }

    ConfigurationResource loadConfig(String name) {
        return client.resources(ConfigurationResource.class)
                .inNamespace("default")
                .withName(name)
                .get();
    }

    InfrastructureResource loadInfra(String name) {
        return client.resources(InfrastructureResource.class)
                .inNamespace("default")
                .withName(name)
                .get();
    }

    void reconcileConfigMap(ApplicationResource app, ConfigurationResource config) {

        var name = app.getMetadata().getName();
        var ns = app.getMetadata().getNamespace();

        String yaml = buildApplicationYaml(app, config);

        var configMap = new io.fabric8.kubernetes.api.model.ConfigMapBuilder()
                .withNewMetadata()
                .withName(name + "-config")
                .withNamespace(ns)
                .endMetadata()
                .addToData("application.yml", yaml)
                .build();

        client.configMaps()
                .inNamespace(ns)
                .resource(configMap).serverSideApply();
    }

    String buildApplicationYaml(
            ApplicationResource app,
            ConfigurationResource config) {

        var appName = app.getMetadata().getName();

        var spec = config.getSpec();

        return """
                spring:
                  application:
                    name: %s
                
                app:
                  message-file: %s
                  secret-name: %s
                
                logging:
                  level:
                    root: INFO
                """.formatted(
                appName,
                safe(spec.messageFile()),
                safe(spec.secretName())
        );
    }

    void reconcileSecret(ApplicationResource app, InfrastructureResource infra) {

        var ns = app.getMetadata().getNamespace();

        var secret = buildSecret(app, infra);

        client.secrets()
                .inNamespace(ns)
                .resource(secret).serverSideApply();
    }

    Secret buildSecret(
            ApplicationResource app,
            InfrastructureResource infra) {

        var name = infra.getSpec().secretName();
        var appName = app.getMetadata().getName();

        var data = new java.util.HashMap<String, String>();

        // POC hardcoded values
        data.put("DB_USER", "demo-user");
        data.put("DB_PASSWORD", "demo-password");
        data.put("API_KEY", "demo-api-key");
        data.put("APP_NAME", appName);

        return new io.fabric8.kubernetes.api.model.SecretBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(app.getMetadata().getNamespace())
                .endMetadata()
                .withStringData(data)
                .build();
    }

    void reconcilePVC(ApplicationResource app, InfrastructureResource infra) {

        var ns = app.getMetadata().getNamespace();
        var name = app.getMetadata().getName();

        var storageSize = infra.getSpec().storageSize();
        if (storageSize == null || storageSize.isBlank()) {
            storageSize = "100Mi";
        }

        var storageClass = infra.getSpec().storageClassName();

        var pvc = buildPVC(app, storageSize, storageClass);

        client.persistentVolumeClaims()
                .inNamespace(ns)
                .resource(pvc).serverSideApply();
    }

    PersistentVolumeClaim buildPVC(
            ApplicationResource app,
            String storageSize,
            String storageClassName) {

        var name = app.getMetadata().getName();

        return new PersistentVolumeClaimBuilder()
                .withNewMetadata()
                .withName(name + "-data")
                .withNamespace(app.getMetadata().getNamespace())
                .endMetadata()
                .withNewSpec()
                .withStorageClassName(storageClassName)
                .addToAccessModes("ReadWriteOnce")
                .withResources(new VolumeResourceRequirementsBuilder()
                        .addToRequests("storage",
                                new Quantity(storageSize))
                        .build())
                .endSpec()
                .build();
    }

    void reconcileDeployment(
            ApplicationResource app,
            ConfigurationResource config,
            InfrastructureResource infra,
            String image) {

        var name = app.getMetadata().getName();
        var ns = app.getMetadata().getNamespace();

        int replicas = infra.getSpec().replicas() != null
                ? infra.getSpec().replicas()
                : 1;

        var deployment = buildDeployment(
                name,
                ns,
                replicas,
                image,
                infra
        );

        client.apps()
                .deployments()
                .inNamespace(ns)
                .resource(deployment).serverSideApply();
    }

    Deployment buildDeployment(
            String name,
            String namespace,
            int replicas,
            String image,
            InfrastructureResource infra) {

        return new DeploymentBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(namespace)
                .endMetadata()
                .withNewSpec()
                .withReplicas(replicas)
                .withNewSelector()
                .addToMatchLabels("app", name)
                .endSelector()
                .withNewTemplate()
                .withNewMetadata()
                .addToLabels("app", name)
                .endMetadata()
                .withNewSpec()

                // ===== CONTAINER =====
                .addNewContainer()
                .withName("app")
                .withImage(image)
                .withImagePullPolicy("IfNotPresent")
                .withPorts(
                        new ContainerPortBuilder()
                                .withContainerPort(8080)
                                .build()
                )

                // STARTUP
                .withStartupProbe(
                        new ProbeBuilder()
                                .withNewHttpGet()
                                .withPath("/actuator/health/liveness")
                                .withPort(new IntOrString(8080))
                                .endHttpGet()
                                .withFailureThreshold(30)
                                .withPeriodSeconds(5)
                                .build()
                )

                // READINESS
                .withReadinessProbe(
                        new ProbeBuilder()
                                .withNewHttpGet()
                                .withPath("/actuator/health/readiness")
                                .withPort(new IntOrString(8080))
                                .endHttpGet()
                                .withInitialDelaySeconds(5)
                                .withPeriodSeconds(10)
                                .build()
                )

                // LIVENESS
                .withLivenessProbe(
                        new ProbeBuilder()
                                .withNewHttpGet()
                                .withPath("/actuator/health/liveness")
                                .withPort(new IntOrString(8080))
                                .endHttpGet()
                                .withInitialDelaySeconds(10)
                                .withPeriodSeconds(10)
                                .build()
                )

                // ===== ENV =====
                .addNewEnv()
                .withName("APP_NAME")
                .withValue(name)
                .endEnv()

                // ===== CONFIG MOUNT =====
                .addNewVolumeMount()
                .withName("config")
                .withMountPath("/config")
                .endVolumeMount()

                // ===== PVC MOUNT =====
                .addNewVolumeMount()
                .withName("data")
                .withMountPath("/data")
                .endVolumeMount()

                .endContainer()

                // ===== VOLUMES =====

                .addNewVolume()
                .withName("config")
                .withNewConfigMap()
                .withName(name + "-config")
                .endConfigMap()
                .endVolume()

                .addNewVolume()
                .withName("data")
                .withNewPersistentVolumeClaim()
                .withClaimName(name + "-data")
                .endPersistentVolumeClaim()
                .endVolume()

                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
    }

    void reconcileService(ApplicationResource app) {

        var name = app.getMetadata().getName();
        var ns = app.getMetadata().getNamespace();

        var service = buildService(name, ns);

        client.services()
                .inNamespace(ns)
                .resource(service).serverSideApply();
    }

    Service buildService(String name, String namespace) {

        return new io.fabric8.kubernetes.api.model.ServiceBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(namespace)
                .endMetadata()
                .withNewSpec()
                .withType("ClusterIP")

                .addToSelector("app", name)

                .addNewPort()
                .withName("http")
                .withPort(80) // service port
                .withTargetPort(new IntOrString(8080))
                .endPort()

                .endSpec()
                .build();
    }

    void reconcileIngress(ApplicationResource app, InfrastructureResource infra) {

        var name = app.getMetadata().getName();
        var ns = app.getMetadata().getNamespace();

        var host = infra.getSpec().ingressHost();

        if (host == null || host.isBlank()) {
            app.getStatus().setState("DEGRADED");
            app.getStatus().setMessage("Missing ingressHost in infra spec");
            return;
        }

        var ingress = buildIngress(name, ns, host);

        client.network()
                .v1()
                .ingresses()
                .inNamespace(ns)
                .resource(ingress).serverSideApply();
    }

    Ingress buildIngress(
            String name,
            String namespace,
            String host) {

        return new IngressBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(namespace)
                .endMetadata()
                .withNewSpec()
                .addNewRule()
                .withHost(host)
                .withNewHttp()
                .addNewPath()
                .withPath("/")
                .withPathType("Prefix")
                .withNewBackend()
                .withNewService()
                .withName(name)
                .withNewPort()
                .withNumber(80)
                .endPort()
                .endService()
                .endBackend()
                .endPath()
                .endHttp()
                .endRule()
                .endSpec()
                .build();
    }

    String safe(String value) {
        return Optional.ofNullable(value).orElse("");
    }
}
