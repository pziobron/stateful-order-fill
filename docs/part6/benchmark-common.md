# Part 6 — Shared Kubernetes benchmark procedure

Run all commands from the repository root, in the same Bash session. These
instructions target the single-node Docker Desktop Kubernetes cluster and the
`default` namespace. Check the context before continuing; use a dedicated local
benchmark cluster. Stop host `bootRun`/Flink jobs and competing Compose processors.
Do not run the three implementations simultaneously.

## Host and broker preflight

The current broker and topic-creation tooling are pinned to Kafka 4.1.2. This
patch release includes the fix for [KAFKA-19888](https://downloads.apache.org/kafka/4.1.2/RELEASE_NOTES.html),
whose coordinator histogram failure was observed during a rejected DB 100K run
on Kafka 4.1.1. Preserve historical image/version metadata with old results;
record 4.1.2 for new runs. Rerun Streams and Flink with the same broker patch
when publishing a new same-environment comparison.

Start the Mac fully awake, plugged into power, and with the lid open. In a
separate terminal, keep this running for the entire cycle, including setup:

```bash
caffeinate -dims
```

Check the assertions in the benchmark terminal:

```bash
pmset -g assertions
```

Require the sleep-prevention assertions to be active. Caffeinate does not make
closing the lid, explicit sleep, or pausing Docker Desktop safe for a benchmark.
The failed DB warm-up overlapped recorded Mac sleep periods; increasing the DB
transaction timeout would hide the symptom rather than produce valid timing.
After any sleep/VM pause, reject the affected cycle and start fresh. Stop
caffeinate with Ctrl+C when the benchmark finishes.

A Running pod does not prove its listener is consuming. Confirm four active DB
consumer-group members before warm-up and after each workload, and inspect
application and broker logs during long runs. Reject and stop a run immediately
if a listener logs an error, stops, or loses membership. Final counts and lag
alone are insufficient: surviving consumers can finish another listener's work.
Retain the failure evidence rather than reporting that duration as clean
throughput. Keep the fail-closed listener and existing DB timeouts enabled.

## 1. Select a cycle and preserve its evidence

Example for the first DB cycle; change the implementation and cycle number for
subsequent runs. Use an absolute output directory outside the repository.

```bash
kubectl config current-context
kubectl get nodes
export BENCH_IMPL=db-centric
export BENCH_CYCLE=1
export BENCH_DIR="$HOME/benchmark-results/part6/${BENCH_IMPL}-10k-cycle-${BENCH_CYCLE}"
mkdir -p "$BENCH_DIR"
git rev-parse HEAD > "$BENCH_DIR/revision.txt"
git diff > "$BENCH_DIR/working-tree.patch"
kubectl version -o yaml > "$BENCH_DIR/kubernetes-version.yaml"
kubectl get nodes -o yaml > "$BENCH_DIR/nodes.yaml"
kubectl get storageclass -o yaml > "$BENCH_DIR/storageclasses.yaml"
docker version > "$BENCH_DIR/docker-version.txt"
```

Record Docker Desktop CPU/memory allocation, host load, and JVM versions too.
One cycle means fresh state → 1K warm-up → one 10K measurement. Run three fresh
cycles per implementation, alternating implementation order, and calculate each
metric's median independently. The first single cycle is an exploratory result.

## 2. Build the shared generator

Build once per source revision, before any timed run:

```bash
docker build -t order-state-processor-test:latest -f kafka-streams/Dockerfile.test .
```

The test uses `testParentOrderFill`, not `testSimpleOrderFill`. Each iteration is
one hierarchy and five input messages. The 10K job emits 50K inputs and expects
10K logical completions. The verifier timeout is 3600 seconds for >=100K iterations,
300 seconds for 10K–99,999 iterations, and 60 seconds for smaller runs. A timeout invalidates the run; it is not a
throughput result. If the baseline needs a longer timeout, rebuild the generator
before starting a new cycle and record the change.

These instructions use locally built images. With kind/minikube, load all images
into the selected cluster before deploying. Rebuild after source changes; do not
reuse a running pod with an old image. Save pod `imageID` values after deployment.

## 3. Reset only this benchmark's resources

This deletes the benchmark Kafka messages, consumer offsets, changelogs, and DB
contents. Preserve previous results first. It intentionally names the two PVCs;
never replace this with `kubectl delete pvc --all`.

```bash
kubectl delete job integration-test flink-job-submitter --ignore-not-found=true
helm uninstall order-processor --ignore-not-found
kubectl delete deployment db-centric flink-taskmanager flink-jobmanager --ignore-not-found=true
kubectl delete -f db-centric/k8s/postgres.yaml --ignore-not-found=true
kubectl delete -f kafka-streams/k8s/kafka/ --ignore-not-found=true
for bench_app in db-centric order-processor order-lifecycle-flink db-centric-postgres kafka-broker; do
  if [ -n "$(kubectl get pods -l "app=$bench_app" -o name)" ]; then
    kubectl wait --for=delete pod -l "app=$bench_app" --timeout=120s || break
  fi
done
kubectl get pods
kubectl delete pvc data-db-centric-postgres-0 kafka-data-kafka-broker-0 --ignore-not-found=true
```

If a wait times out, stop here and investigate. All selected pods must be gone
before deleting PVCs or recreating storage. Flink hostPath
checkpoint files survive deployment deletion; the Flink scenario explains how
to distinguish the new job's files. No previous checkpoint/savepoint is restored.

## 4. Recreate Kafka and verify empty topics

```bash
kubectl apply -f kafka-streams/k8s/kafka/
kubectl rollout status statefulset/kafka-broker --timeout=180s
kubectl wait --for=condition=complete job/kafka-topic-creator --timeout=180s
kubectl exec kafka-broker-0 -- /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:29092 --describe --topic org.example.order.executions
kubectl exec kafka-broker-0 -- /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:29092 --describe --topic org.example.order.verification
kubectl exec kafka-broker-0 -- /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:29092 --topic 'org.example.order.*'
```

Both topics must have six partitions, each with end offset zero. Deploy only the
implementation selected in its scenario, then return here for steps 5–7.

## 5. Run the 1K warm-up

```bash
kubectl apply -f kafka-streams/k8s/tests/integration-test-job-1000.yaml
kubectl wait --for=condition=Initialized pod -l job-name=integration-test --timeout=120s
kubectl logs -f job/integration-test -c test-runner --pod-running-timeout=120s
kubectl wait --for=condition=complete job/integration-test --timeout=180s
kubectl logs job/integration-test -c test-runner > "$BENCH_DIR/warmup-1k.log"
```

Require `BUILD SUCCESSFUL` and a `SCENARIO_RESULT` with messages=5000,
verificationRecords=hierarchyKeys=1000, processors=4. If waiting or logging fails,
inspect `kubectl describe job integration-test`, pod events, and
`kubectl logs job/integration-test -c wait-for-kafka`; do not advance on failure.
Run the implementation-specific acceptance checks after warm-up. Require zero
lag and, for DB, zero pending outbox. Keep all warm-up state for the measurement.

## 6. Run the 10K measurement

```bash
kubectl delete job integration-test
kubectl apply -f kafka-streams/k8s/tests/integration-test-job-10000.yaml
kubectl wait --for=condition=Initialized pod -l job-name=integration-test --timeout=120s
kubectl logs -f job/integration-test -c test-runner --pod-running-timeout=120s
kubectl wait --for=condition=complete job/integration-test --timeout=360s
kubectl logs job/integration-test -c test-runner > "$BENCH_DIR/measurement-10k.log"
```

Require `BUILD SUCCESSFUL` and messages=50000,
verificationRecords=hierarchyKeys=10000, processors=4. Save the full log, including
processor distribution, not just the final throughput. Do not reset anything
between warm-up and measurement. A Job marked Failed must be investigated rather
than retried against the same state as a new independent cycle.

## 7. Accept and archive

Run the implementation-specific checks again. Save the primary end-to-end
metrics; the measured time excludes subsequent acceptance checks. Outputs from
Flink need not already belong to a completed checkpoint, and DB processing-window
timestamps exclude final commit/outbox transport. See the overview for boundaries.

```bash
kubectl get pods -o wide > "$BENCH_DIR/pods.txt"
kubectl get pods -o yaml > "$BENCH_DIR/pods.yaml"
kubectl get deploy,statefulset,job -o yaml > "$BENCH_DIR/workloads.yaml"
kubectl get configmap -o yaml > "$BENCH_DIR/configmaps.yaml"
kubectl get pvc -o yaml > "$BENCH_DIR/pvcs.yaml"
kubectl get events --sort-by=.metadata.creationTimestamp > "$BENCH_DIR/events.txt"
grep SCENARIO_RESULT "$BENCH_DIR/measurement-10k.log"
```

Capture resource samples in another terminal during the run if metrics-server is
available (`kubectl top pods`); record when unavailable. Do not archive Secrets.
Reject runs with missing completions, processing failures, OOM, restarts, recovery,
or measurement-time rebalances. Keep effective startup configuration and JVM
versions from every participating pod. Consumer assignments must be stable before
warm-up; lack of errors at WARN alone is not proof that no rebalance occurred.

For a 100K experiment, start a completely new cycle and substitute
`integration-test-job-100000.yaml` in step 6, with expected 500000 inputs and
100000 completions. Do not run 10K first. The 100K verifier permits 3600 seconds
to accommodate the DB baseline; increase the external Job wait in step 6 to
3900 seconds. This timeout is only an upper bound, not part of the measured
completion time. Record whether results are one exploratory run or medians of
three independent cycles.
