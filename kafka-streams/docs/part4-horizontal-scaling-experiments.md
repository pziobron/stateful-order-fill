# Part 4 - Horizontal Scaling and Partitions

## Test Scenarios

The following scenarios were executed to evaluate:

* workload distribution across Kafka Streams instances,
* partition ownership correctness,
* horizontal scalability under increasing load,
* impact of partition count versus application instance count.

---

## Environment Preparation

Execute once before running all scenarios.

### Start Kafka

```bash
kubectl apply -f kafka-streams/k8s/kafka/
```

### Build Application Image

```bash
docker build -t order-state-processor:latest \
  -f kafka-streams/Dockerfile .
```

### Build Integration Test Image

```bash
docker build -t order-state-processor-test:latest \
  -f kafka-streams/Dockerfile.test .
```

---

## Scenario 1

### Configuration

| Parameter  | Value |
| ---------- | ----- |
| Partitions | 3     |
| Pods       | 4     |
| Iterations | 1,000 |

Expected behavior:

* one pod remains idle,
* each hierarchy processed by a single pod.

### Deploy Application

```bash
helm install order-processor kafka-streams/k8s/helm-chart \
  --set kafka.bootstrapServers=kafka-broker:9092 \
  --set replicas=4
```

### Execute Test

```bash
kubectl delete job integration-test --ignore-not-found=true

kubectl apply \
  -f kafka-streams/k8s/tests/integration-test-job-1000.yaml

kubectl logs -f job/integration-test
```

---

## Scenario 2

### Configuration

| Parameter  | Value  |
| ---------- | ------ |
| Partitions | 3      |
| Pods       | 4      |
| Iterations | 10,000 |

### Redeploy Application

```bash
helm uninstall order-processor --ignore-not-found

helm install order-processor kafka-streams/k8s/helm-chart \
  --set kafka.bootstrapServers=kafka-broker:9092 \
  --set replicas=4
```

### Execute Test

```bash
kubectl delete job integration-test --ignore-not-found=true

kubectl apply \
  -f kafka-streams/k8s/tests/integration-test-job-10000.yaml

kubectl logs -f job/integration-test
```

---

## Scenario 3

### Configuration

| Parameter  | Value  |
| ---------- | ------ |
| Partitions | 6      |
| Pods       | 3      |
| Iterations | 10,000 |

### Increase Topic Partitions

```bash
kubectl exec -it kafka-broker-0 -- \
/opt/kafka/bin/kafka-topics.sh \
--bootstrap-server localhost:9092 \
--alter \
--topic org.example.order.executions \
--partitions 6

kubectl exec -it kafka-broker-0 -- \
/opt/kafka/bin/kafka-topics.sh \
--bootstrap-server localhost:9092 \
--alter \
--topic org.example.order.verification \
--partitions 6
```

### Recreate Internal Kafka Streams Topics

```bash
kubectl exec -it kafka-broker-0 -- \
/opt/kafka/bin/kafka-topics.sh \
--bootstrap-server localhost:9092 \
--delete \
--topic order-state-processor-order-state-store-repartition

kubectl exec -it kafka-broker-0 -- \
/opt/kafka/bin/kafka-topics.sh \
--bootstrap-server localhost:9092 \
--delete \
--topic order-state-processor-order-state-store-changelog
```

### Deploy Application

```bash
helm uninstall order-processor --ignore-not-found

helm install order-processor kafka-streams/k8s/helm-chart \
  --set kafka.bootstrapServers=kafka-broker:9092 \
  --set replicas=3
```

### Execute Test

```bash
kubectl delete job integration-test --ignore-not-found=true

kubectl apply \
  -f kafka-streams/k8s/tests/integration-test-job-10000.yaml

kubectl logs -f job/integration-test
```

---

## Scenario 4

### Configuration

| Parameter  | Value  |
| ---------- | ------ |
| Partitions | 6      |
| Pods       | 6      |
| Iterations | 10,000 |

### Deploy Application

```bash
helm uninstall order-processor --ignore-not-found

helm install order-processor kafka-streams/k8s/helm-chart \
  --set kafka.bootstrapServers=kafka-broker:9092 \
  --set replicas=6
```

### Execute Test

```bash
kubectl delete job integration-test --ignore-not-found=true

kubectl apply \
  -f kafka-streams/k8s/tests/integration-test-job-10000.yaml

kubectl logs -f job/integration-test
```

---

## Scenario 5

### Configuration

| Parameter  | Value   |
| ---------- | ------- |
| Partitions | 6       |
| Pods       | 3       |
| Iterations | 100,000 |

### Deploy Application

```bash
helm uninstall order-processor --ignore-not-found

helm install order-processor kafka-streams/k8s/helm-chart \
  --set kafka.bootstrapServers=kafka-broker:9092 \
  --set replicas=3
```

### Execute Test

```bash
kubectl delete job integration-test --ignore-not-found=true

kubectl apply \
  -f kafka-streams/k8s/tests/integration-test-job-100000.yaml

kubectl logs -f job/integration-test
```

---

## Scenario 6

### Configuration

| Parameter  | Value   |
| ---------- | ------- |
| Partitions | 6       |
| Pods       | 6       |
| Iterations | 100,000 |

### Deploy Application

```bash
helm uninstall order-processor --ignore-not-found

helm install order-processor kafka-streams/k8s/helm-chart \
  --set kafka.bootstrapServers=kafka-broker:9092 \
  --set replicas=6
```

### Execute Test

```bash
kubectl delete job integration-test --ignore-not-found=true

kubectl apply \
  -f kafka-streams/k8s/tests/integration-test-job-100000.yaml

kubectl logs -f job/integration-test
```

---

### Results

| Scenario | Partitions | Pods | Iterations | Messages | Hierarchies | End-to-End (ms) | Processing Window (ms) | End-to-End Msg/s | Processing Msg/s |
| -------- | ---------: | ---: | ---------: | -------: | ----------: | --------------: | ---------------------: | ---------------: | ---------------: |
| 1        |          3 |    4 |      1,000 |    5,000 |       1,000 |           7,069 |                    112 |           707.31 |        44,642.86 |
| 2        |          3 |    4 |     10,000 |   50,000 |      10,000 |           8,876 |                    771 |         5,633.17 |        64,850.84 |
| 3        |          6 |    3 |     10,000 |   50,000 |      10,000 |          27,379 |                    715 |         1,826.22 |        69,930.07 |
| 4        |          6 |    6 |     10,000 |   50,000 |      10,000 |          27,697 |                    502 |         1,805.25 |        99,601.59 |
| 5        |          6 |    3 |    100,000 |  500,000 |     100,000 |          26,201 |                 17,697 |        19,083.24 |        28,253.38 |
| 6        |          6 |    6 |    100,000 |  500,000 |     100,000 |          31,699 |                 15,367 |        15,773.37 |        32,537.26 |

```
```
