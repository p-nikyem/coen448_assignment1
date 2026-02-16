# COEN448 Assignment 1 - 

This project implements and tests three explicit exception-handling policies for concurrent execution using Java's `CompletableFuture`.

## Overview

When multiple microservices execute concurrently in a fan-out/fan-in pattern, the system must define what happens if one of them fails. This assignment implements three different failure-handling policies:

| Policy | Behavior | Use Case |
|--------|----------|----------|
| **Fail-Fast** | If any service fails, entire operation fails | Correctness-critical systems |
| **Fail-Partial** | Failed services omitted, successful results returned | Dashboards, analytics |
| **Fail-Soft** | Failures replaced with fallback values | High-availability systems |

## Project Structure

```
coen448.computablefuture.test/
├── src/main/java/coen448/computablefuture/test/
│   ├── AsyncProcessor.java    # Implements the three failure policies
│   └── Microservice.java      # Async service abstraction
├── src/test/java/coen448/computablefuture/test/
│   └── AsyncProcessorTest.java # JUnit 5 test suite
└── pom.xml
```

## Implemented Methods

### `processAsyncFailFast`
```java
CompletableFuture<String> processAsyncFailFast(List<Microservice> services, List<String> messages)
```
- Propagates exceptions immediately when any service fails
- No partial results returned

### `processAsyncFailPartial`
```java
CompletableFuture<List<String>> processAsyncFailPartial(List<Microservice> services, List<String> messages)
```
- Handles failures per service
- Returns only successful results
- Never throws to the caller

### `processAsyncFailSoft`
```java
CompletableFuture<String> processAsyncFailSoft(List<Microservice> services, List<String> messages, String fallbackValue)
```
- Replaces failures with fallback values
- Always completes normally

## Building and Testing

### Prerequisites
- Java 11+
- Maven 3.6+

### Build
```bash
cd coen448.computablefuture.test
mvn clean compile
```

### Run Tests
```bash
mvn test
```

## Test Coverage

The test suite validates:
- **Fail-Fast**: Exception propagation via `assertThrows`
- **Fail-Partial**: Partial results returned without exceptions
- **Fail-Soft**: Fallback value substitution
- **Liveness**: No deadlocks (all futures awaited with timeouts)
- **Nondeterminism**: Completion order observed but not asserted

## Documentation

See [docs/failure-semantics.md](docs/failure-semantics.md) for detailed documentation on:
- Differences between the three policies
- When each policy is appropriate
- Risks of hiding failures in concurrent systems
