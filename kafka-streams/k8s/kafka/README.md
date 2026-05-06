# Kafka on Kubernetes

This directory contains Kubernetes manifests to deploy Apache Kafka (KRaft mode) matching your docker-compose configuration.

## Components

- **StatefulSet** (`kafka-statefulset.yaml`): Kafka broker with persistent storage
- **Services** (`kafka-service.yaml`): Headless service for internal communication + NodePort for external access
- **Job** (`kafka-topic-job.yaml`): Creates the topic after Kafka is ready

## Deployment

```bash
kubectl apply -f k8s/kafka/
```

## Access Kafka

- **From inside cluster**: `kafka-broker:29092`
- **From outside (via NodePort)**: `<node-ip>:30092`
- **Port-forward for local access**: `kubectl port-forward svc/kafka-broker 9092:9092`

## Update Application Values

Edit `k8s/helm-chart/values.yaml` to point to the Kafka service:

```yaml
kafka:
  bootstrapServers: kafka-broker:29092
```
