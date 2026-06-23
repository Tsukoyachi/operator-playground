# Operator Playground App

This is a simple Spring Boot application used as a sandbox for experimenting with Kubernetes Operators and Crossplane-based abstractions.

## Purpose

The goal of this application is to provide a minimal workload that can be deployed in both local and Kubernetes environments in order to test:

- Kubernetes Deployments
- ConfigMaps mounted as files
- Secrets injected as environment variables
- Basic configuration overrides via Spring Boot properties

It serves as a target application for an operator that translates a simplified CRD into standard Kubernetes resources.

## Behavior

The application exposes a single REST endpoint:

```GET /api/info```

It returns information based on external configuration:

- A message loaded from a file (path configured via `app.message-file`)
- A secret injected via environment variable (env var name configured via `app.secret-name`)

## Configuration

The application supports external configuration via Spring Boot properties.

### Property

```
app.message-file : Defines the path to a file that will be read at runtime.
app.secret-name : Defines the env var name that will be used at runtime.
```

### Local usage

Already given in the application-local.yaml file (use profile local to use it)

```
app.message-file=../local/tmp.txt
app.secret-name=PATH
```

### Docker usage

Build the image first : ```docker build -t operator-playground-app:local app``
Run the container : 

```shell
docker run -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=local
  -v $(pwd)/app/local:/app/local \
  operator-playground-app:local
```

It'll print out the path env var of the dockerfile and the content of your message-file to test out what it is locally, this dockerfile will be reused in the cluster.

### Kubernetes usage

Put whatever you want in the cluster, the goal is to inject whatever we want but dynamically using crd but the file must be mounted on the pod and the env var does need to exist.

```
app.message-file=YOUR-FILE-PATH
app.secret-name=YOUR-SECRET-NAME
```

## Endpoints

### Health

Basic spring actuator endpoints for readiness/liveness probes.

```
/actuator/health
/actuator/readiness
```

### Info

```/api/info```

Returns:

- message from file
- secret from environment variable

