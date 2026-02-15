# Failure Semantics in Concurrent Systems

## Context
In this project, `AsyncProcessor` fans out requests to multiple microservices concurrently and then aggregates results.  
Because calls run in parallel, failures are nondeterministic in timing and location. The system therefore needs an explicit policy for what to do when one or more services fail.

## Policy Differences

| Policy | Behavior on Failure | Aggregate Result | Exception to Caller | Typical Goal |
|---|---|---|---|---|
| Fail-Fast | First failure aborts whole operation | No partial output | Yes | Strong correctness |
| Fail-Partial | Failed services are skipped | Successful results only | No (for per-service failures) | Best-effort usefulness |
| Fail-Soft | Failed services replaced by fallback value | Full-shaped output with fallbacks | No | High availability |

## 1. Fail-Fast (Atomic Policy)
### Definition
If any concurrent microservice fails, the entire operation fails.

### When It Is Appropriate
- Payment/transaction pipelines where partial results are invalid.
- Safety- or compliance-critical workflows.
- Any operation that must be all-or-nothing.

### Example
An order-placement flow validates inventory, payment, and fraud checks in parallel.  
If fraud check fails, returning a partial "almost approved" result is dangerous. The operation should fail immediately.

## 2. Fail-Partial (Best-Effort Policy)
### Definition
Each service failure is handled locally. Successful service outputs are returned; failed ones are omitted (or marked explicitly).

### When It Is Appropriate
- Dashboards combining data from many independent sources.
- Recommendation systems where missing one signal is acceptable.
- Analytics/aggregation where incomplete data still has value.

### Example
A monitoring dashboard requests CPU, memory, and network status from several nodes.  
If one node times out, returning remaining node metrics is still useful for operators.

## 3. Fail-Soft (Fallback Policy)
### Definition
Any failed service call is replaced by a predefined fallback value; the operation completes normally.

### When It Is Appropriate
- User-facing features where graceful degradation is preferred to total failure.
- High-availability systems with strict uptime goals.
- Non-critical data enrichment steps.

### Example
A product page fetches live recommendation text.  
If recommendation service fails, show a default recommendation message rather than breaking page rendering.

## Risks of Hiding Failures in Concurrent Systems
Fail-soft and, to a lesser extent, fail-partial can hide real operational issues if not monitored.

### Main Risks
- **Masked outages**: fallback values make failing dependencies look healthy.
- **Silent data quality degradation**: outputs look complete but are less accurate.
- **Delayed incident detection**: no explicit exception means failures may go unnoticed.
- **Debugging difficulty**: root causes are harder to trace after replacement/omission.

### Concrete Risk Example
An analytics pipeline replaces failed fraud-service responses with `"LOW_RISK"` fallback values.  
The system remains "available," but risk scoring becomes systematically wrong. This can create financial and compliance exposure.

## Practical Guidance
- Choose **Fail-Fast** when correctness is mandatory.
- Choose **Fail-Partial** when partial value is useful and consumers can handle missing items.
- Choose **Fail-Soft** when availability matters most, but pair it with:
  - structured logging for every fallback substitution,
  - metrics/alerts on fallback rate,
  - periodic review to ensure fallback is not becoming the normal path.

## Final Takeaway
Concurrency is manageable only when failure semantics are explicit.  
The right policy depends on the trade-off between correctness, usefulness of partial results, and availability.
