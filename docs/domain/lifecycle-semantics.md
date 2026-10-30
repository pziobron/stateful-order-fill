# Lifecycle semantics

The shared processor supports a root and its direct children. Root completeness
means that fills across the hierarchy reach the root's positive
expected quantity. The processor checks this aggregate quantity rather than
checking each child's completeness separately.

## Hierarchy quantity assumptions

The benchmark happy path assumes that:

- the parent's expected quantity equals the sum of all children's expected quantities;
- fills belong only to children, with no direct fills on the parent;
- fill quantities are positive and no child is overfilled.

Under these assumptions, reaching the parent's expected quantity implies that
every child has received its full expected quantity. A separate check of every
child's completeness is therefore unnecessary for this workload.

These are input assumptions, not invariants enforced by the processor. Outside
this contract, an overfilled child or direct parent fills can compensate for an
underfilled child in the aggregate. The processor also does not wait for every
child's order definition: a fill received before that definition contributes to
the parent even though the child's expected quantity is not yet known locally.
Quantity completeness therefore does not establish that every order definition
has been received or that the lifecycle is final.

## Reports and delivery order

Only order definitions (`O`) and fills (`F`) are supported. Cancellation (`C`)
and other report types are rejected without applying lifecycle changes. Cancellation
cutoffs, amendments and execution corrections are outside this article's scope.

Each node retains every unique received fill. A child counts its own fills; the
root counts its direct fills and those of all its children. Business timestamps
are retained as facts but do not filter quantities. A node is FILLED when its
quantity reaches its positive expected quantity, and NOT_FILLED otherwise.
The filledQuantityMap contains the same direct fill identities as fills.

Fill identity is scoped to a node. Repeating the same fill ID and payload is a
no-op for fills and quantities. A different payload for that ID raises an
IllegalArgumentException. This is conflict detection, not support for execution
corrections. Lifecycle processing timestamps can still change on duplicate input.

## Scope and completion signals

Order definitions may arrive after fills. The POC assumes one
immutable order definition (type O) per order ID. Re-delivery of that same
definition is allowed; changing quantity or metadata through subsequent O
reports is outside the supported input contract.

Definition immutability is not enforced by input validation. OrderStateUpdater overwrites
the definition on every O report without comparing txnTime or a source version.
For example, delivering quantity 150 at business time 10:05 followed by quantity
100 at business time 10:00 leaves expectedQuantity at 100. That can change the
derived status and trigger a completion signal. This is arrival-order behavior,
not support for order amendments.

Supporting amendments would require a source version or an explicitly defined
timestamp ordering and tie-break rule, plus a policy for changes after a
completion signal has already been published. It is deliberately deferred to
keep all processor variants on the same simple domain contract. Arbitrary
hierarchy depth and execution corrections are also outside this change.

completedAt remains the processing-time timestamp of the first observed full
quantity. Additional fills and late definitions continue updating state without
changing this timestamp or emitting another logical completion. It is not an
event-time finality guarantee. Kafka Streams keeps a changelog-backed
completion-emitted-store; Flink keeps managed completion-emitted state; PostgreSQL
keeps completed_at and one outbox entry per hierarchy.

Kafka Streams checks KTable updates forwarded downstream; its existing cache may
coalesce intermediate updates. Flink checks after each input event. Their exact
emission timing is therefore not identical. At-least-once recovery can still
produce duplicate verification records; the flags do not guarantee exactly-once
delivery. The benchmark happy path uses only O/F reports without conflicting
duplicates. Historical benchmark results predate the Kafka Streams emission flag
and must not be presented as measurements of the updated topology.

When adopting the new Kafka Streams store, existing lifecycle state does not
provide a historical emission registry. Previously completed keys may emit again
on their next eligible update. Use fresh benchmark state, or define a migration
for an existing deployment rather than assuming old emissions are remembered.

Existing stored snapshots are not migrated by this change. Old snapshots may
already have discarded fills or contain duplicate fill history; rebuild state
from the input when validating these semantics.

## State retention

The current POC does not expire or delete completed hierarchy state. Completion
does not establish event-time finality: late definitions and additional fills
can still update business state, and retained fill identities support duplicate detection. Publishing a
verification record therefore does not trigger state deletion.

Keeping state indefinitely is an intentional simplification for finite benchmark
workloads, not a retention policy for a continuously running service. Growing
state increases storage or memory consumption and can increase checkpoint and
recovery costs. Each independent benchmark cycle starts with fresh processor
state; no expiration runs during measurement.

A future retention policy could retain completed hierarchies for a period such
as 7 or 30 days. These are examples, not configured guarantees. The policy must
define when the retention clock starts or resets, how long late events and
replays are supported, and what happens to hierarchies that never complete.
It must also define how events arriving after deletion are handled so they do
not silently recreate a hierarchy and produce another completion signal.
A smaller retained completion registry is one possible safeguard.

Operational state and diagnostic history serve different purposes. OrderState is
a current aggregate, not a complete audit trail of input reports or their arrival
order. Incident investigation may require separately retained input events,
Kafka topic/partition/offset metadata, processor version, and optional snapshots.
Any archival and input retention policy must be coordinated with state cleanup;
none is introduced by this document.
