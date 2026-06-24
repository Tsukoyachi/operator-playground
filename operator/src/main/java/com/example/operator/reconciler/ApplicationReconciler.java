package com.example.operator.reconciler;

import com.example.operator.crd.app.ApplicationResource;
import com.example.operator.crd.app.ApplicationSpec;
import com.example.operator.crd.app.ApplicationStatus;
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
        var status = getOrCreate(app);

        boolean ok = true;

        var metadata = app.getMetadata();

        if (metadata == null || metadata.getNamespace() == null || metadata.getNamespace().isBlank()) {
            status.setState("ERROR");
            status.setMessage("Missing metadata or namespace");
            return UpdateControl.patchStatus(app);
        }

        String ns = metadata.getNamespace();

        // Load dependent CRs
        ConfigurationResource config = loadConfig(spec.configurationRef(), ns);
        InfrastructureResource infra = loadInfra(spec.infrastructureRef(), ns);

        // Validate
        // todo: Add sub cr validation before going here
        if (config == null || infra == null) {
            status.setState("ERROR");
            status.setMessage("Missing config or infra");
            return UpdateControl.patchStatus(app);
        }

        // Build desired state
        String image = buildImage(spec);

        ok &= reconcileConfigMap(app, config, status);
        ok &= reconcileSecret(app, infra, status);
        ok &= reconcilePVC(app, infra, status);
        ok &= reconcileDeployment(app, config, infra, image, status);
        ok &= reconcileService(app, status);
        ok &= reconcileIngress(app, infra, status);

        // Status update
        if (!ok) {
            if (status.getState() == null || status.getState().equals("READY")) {
                status.setState("DEGRADED");
            }

            if (status.getMessage() == null) {
                status.setMessage("One or more resources failed to reconcile");
            }

            return UpdateControl.patchStatus(app);
        }


        status.setState("READY");
        status.setMessage("Reconciled successfully");

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

    ConfigurationResource loadConfig(String name, String namespace) {
        return client.resources(ConfigurationResource.class)
                .inNamespace(Optional.ofNullable(namespace).orElse("default"))
                .withName(name)
                .get();
    }

    InfrastructureResource loadInfra(String name, String namespace) {
        return client.resources(InfrastructureResource.class)
                .inNamespace(Optional.ofNullable(namespace).orElse("default"))
                .withName(name)
                .get();
    }

    boolean reconcileConfigMap(ApplicationResource app, ConfigurationResource config, ApplicationStatus status) {

        var name = app.getMetadata().getName();
        var ns = app.getMetadata().getNamespace();

        try {
            String yaml = buildApplicationYaml(app, config);

            var configMap = new ConfigMapBuilder()
                    .withNewMetadata()
                    .withName(name + "-config")
                    .withNamespace(ns)
                    .endMetadata()
                    .addToData("application.yml", yaml)
                    .build();

            client.configMaps()
                    .inNamespace(ns)
                    .resource(configMap)
                    .serverSideApply();

            return true;

        } catch (Exception e) {
            status.setState("ERROR");
            status.setMessage("ConfigMap reconcile failed: " + e.getMessage());
            return false;
        }
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

    boolean reconcileSecret(ApplicationResource app, InfrastructureResource infra, ApplicationStatus status) {

        try {
            var ns = app.getMetadata().getNamespace();
            var secret = buildSecret(app, infra);

            client.secrets()
                    .inNamespace(ns)
                    .resource(secret)
                    .serverSideApply();

            return true;

        } catch (Exception e) {
            status.setState("ERROR");
            status.setMessage("Secret reconcile failed: " + e.getMessage());
            return false;
        }
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

    boolean reconcilePVC(ApplicationResource app, InfrastructureResource infra, ApplicationStatus status) {

        try {
            var ns = app.getMetadata().getNamespace();
            var storageSize = infra.getSpec().storageSize();

            if (storageSize == null || storageSize.isBlank()) {
                storageSize = "100Mi";
            }

            var pvc = buildPVC(app, storageSize, infra.getSpec().storageClassName());

            client.persistentVolumeClaims()
                    .inNamespace(ns)
                    .resource(pvc)
                    .serverSideApply();

            return true;

        } catch (Exception e) {
            status.setState("ERROR");
            status.setMessage("PVC reconcile failed: " + e.getMessage());
            return false;
        }
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

    boolean reconcileDeployment(
            ApplicationResource app,
            ConfigurationResource config,
            InfrastructureResource infra,
            String image,
            ApplicationStatus status) {

        try {
            var name = app.getMetadata().getName();
            var ns = app.getMetadata().getNamespace();

            int replicas = infra.getSpec().replicas() != null
                    ? infra.getSpec().replicas()
                    : 1;

            var deployment = buildDeployment(name, ns, replicas, image, infra);

            client.apps()
                    .deployments()
                    .inNamespace(ns)
                    .resource(deployment)
                    .serverSideApply();

            return true;

        } catch (Exception e) {
            status.setState("ERROR");
            status.setMessage("Deployment reconcile failed: " + e.getMessage());
            return false;
        }
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

    boolean reconcileService(ApplicationResource app, ApplicationStatus status) {

        try {
            var name = app.getMetadata().getName();
            var ns = app.getMetadata().getNamespace();

            var service = buildService(name, ns);

            client.services()
                    .inNamespace(ns)
                    .resource(service)
                    .serverSideApply();

            return true;

        } catch (Exception e) {
            status.setState("ERROR");
            status.setMessage("Service reconcile failed: " + e.getMessage());
            return false;
        }
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

    boolean reconcileIngress(ApplicationResource app, InfrastructureResource infra, ApplicationStatus status) {

        var name = app.getMetadata().getName();
        var ns = app.getMetadata().getNamespace();

        var host = infra.getSpec().ingressHost();

        if (host == null || host.isBlank()) {
            status.setState("DEGRADED");
            status.setMessage("Missing ingressHost in infra spec");
            return false;
        }

        var ingress = buildIngress(name, ns, host);

        client.network()
                .v1()
                .ingresses()
                .inNamespace(ns)
                .resource(ingress).serverSideApply();

        return true;
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

    public static ApplicationStatus getOrCreate(ApplicationResource app) {
        ApplicationStatus status = app.getStatus();
        if (status == null) {
            status = new ApplicationStatus();
            app.setStatus(status);
        }
        return status;
    }
}
