# Part 6 — Apache Flink 10K scenario

Follow [shared steps 1–4](benchmark-common.md) with `BENCH_IMPL=flink`.
Build before deploying; repeat the fresh cycle three times.

## Build and configure

The Dockerfile expects the Flink module as its build context:

```bash
./gradlew :flink:shadowJar
docker build -t order-lifecycle-flink:latest -f flink/Dockerfile flink/
kubectl apply -f flink/k8s/flink-configmap.yaml
kubectl patch configmap order-lifecycle-flink-config --type=merge \
  -p '{"data":{"KAFKA_BOOTSTRAP_SERVERS":"kafka-broker:29092","KAFKA_COMMIT_OFFSETS_ON_CHECKPOINT":"true"}}'
```

Enable Kafka offset commits for observable post-checkpoint lag. This overrides the
manifest default of false; it does not enable checkpointing itself. Preserve the
effective ConfigMap with the results. Disable only per-event business logging:

```bash
kubectl get configmap order-lifecycle-flink-config -o json \
  > "$BENCH_DIR/flink-config.json"
python3 - "$BENCH_DIR/flink-config.json" <<'PY'
import json
import sys
from pathlib import Path
p = Path(sys.argv[1])
config = json.loads(p.read_text())
config['data']['log4j-console.properties'] += '''
logger.lifecycleService.name = org.example.order.lifecycle.service
logger.lifecycleService.level = WARN
'''
p.write_text(json.dumps(config))
PY
kubectl apply -f "$BENCH_DIR/flink-config.json"
```

Working state remains HashMapStateBackend on heap; filesystem checkpointing is
configured at `/flink-checkpoints`, with a 10-second interval. The hostPath is
shared only on this single-node local cluster. Old checkpoint directories may
remain on disk, but this procedure submits a new job without restoring any old
state. Record disk space; identify checkpoint evidence using the new Job ID.

## Deploy and submit exactly one job

```bash
kubectl apply -f flink/k8s/flink-jobmanager.yaml
kubectl apply -f flink/k8s/flink-taskmanager.yaml
kubectl rollout status deployment/flink-jobmanager --timeout=180s
kubectl rollout status deployment/flink-taskmanager --timeout=180s
kubectl apply -f flink/k8s/flink-job-submitter.yaml
kubectl logs -f job/flink-job-submitter -c job-submitter --pod-running-timeout=120s
kubectl wait --for=condition=complete job/flink-job-submitter --timeout=180s
kubectl logs job/flink-job-submitter > "$BENCH_DIR/flink-submission.log"
```

The submitter's success means submission, not job readiness. In another terminal:

```bash
kubectl port-forward service/flink-jobmanager 8081:8081
```

Keep that terminal running. The remaining commands require `curl` and `jq`:

```bash
curl -fsS localhost:8081/overview | tee "$BENCH_DIR/flink-overview.json"
curl -fsS localhost:8081/jobs/overview | tee "$BENCH_DIR/flink-jobs.json"
export BENCH_FLINK_JOB_ID=$(jq -r '.jobs[] | select(.state=="RUNNING") | .jid' "$BENCH_DIR/flink-jobs.json")
curl -fsS "localhost:8081/jobs/$BENCH_FLINK_JOB_ID" | tee "$BENCH_DIR/flink-job-before-warmup.json"
```

Require exactly one RUNNING job, four registered TaskManagers/four slots, and all
job subtasks RUNNING at parallelism four. Check the submitter log's effective
configuration (broker, topics, parallelism, checkpoint interval, offset commits).
Wait for the Kafka source partition assignments and a successful initial
checkpoint before publishing warm-up input. Flink source uses explicit split
assignment; the number of Kafka consumer-group members is not a readiness test.

## Warm-up, measurement, acceptance

Execute shared step 5, checks below, shared step 6, then checks again. After each
stage, allow the next full checkpoint to finish. Re-fetch until its completion
is newer than the end of that workload; inspect the checkpoint trigger/completion
timestamps rather than relying on a fixed sleep.

```bash
curl -fsS "localhost:8081/jobs/$BENCH_FLINK_JOB_ID/checkpoints" \
  | tee "$BENCH_DIR/flink-checkpoints.json"
curl -fsS "localhost:8081/jobs/$BENCH_FLINK_JOB_ID/exceptions" \
  | tee "$BENCH_DIR/flink-exceptions.json"
kubectl exec kafka-broker-0 -- /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:29092 --describe --group order-lifecycle-flink-kubernetes
kubectl exec deployment/flink-jobmanager -- \
  ls -lR "/flink-checkpoints/$BENCH_FLINK_JOB_ID"
kubectl get pods -l app=order-lifecycle-flink
```

Use stage-specific filenames to retain checkpoint evidence from both stages.
Require zero lag after checkpoint offset commits, successful post-workload
checkpoint, no failed checkpoints, no task recovery/restarts and no OOM or
uncaught exceptions. Check `.counts.failed`, `.latest.completed`, the job state
and exception history. Output receipt alone is insufficient for acceptance.
If the checkpoint path differs, inspect `/flink-checkpoints` and associate files
with this Job ID; old files from another job do not count.

```bash
for bench_pod in $(kubectl get pods -l app=order-lifecycle-flink --field-selector=status.phase=Running -o name); do
  kubectl logs "$bench_pod" --all-containers > "$BENCH_DIR/${bench_pod#pod/}.log"
  kubectl exec "$bench_pod" -- java -version 2> "$BENCH_DIR/${bench_pod#pod/}-java.txt"
done
```

For pods with init containers, specify `-c jobmanager`, `-c taskmanager`, or
`-c job-submitter` when `kubectl exec` requires a container name. Finish with
shared step 7. Stop the port-forward when done.

Limits: four TaskManagers (1 CPU / 1280Mi each), one JobManager (1 CPU / 1280Mi):
5 CPU / 6.25Gi total, excluding shared Kafka and generator. This is the existing
baseline budget, not equal to the 4 CPU / 4Gi Streams/DB limits. A resource-matched
experiment requires explicit alternative allocations and separate measurements.
