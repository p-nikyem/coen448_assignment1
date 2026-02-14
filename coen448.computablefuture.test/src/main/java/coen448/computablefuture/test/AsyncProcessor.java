package coen448.computablefuture.test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class AsyncProcessor {
	
    public CompletableFuture<String> processAsyncFailFast(
            List<Microservice> services,
            List<String> messages) {

        if (services == null || messages == null) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("services and messages must not be null"));
        }
        if (services.size() != messages.size()) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("services and messages must have the same size"));
        }

        CompletableFuture<String> result = new CompletableFuture<>();

        List<CompletableFuture<String>> futures = IntStream.range(0, services.size())
            .mapToObj(i -> services.get(i).retrieveAsync(messages.get(i)))
            .collect(Collectors.toList());

        // Fail fast: complete result exceptionally as soon as ANY future fails
        for (CompletableFuture<String> future : futures) {
            future.exceptionally(ex -> {
                result.completeExceptionally(ex);
                return null;
            });
        }

        // Complete normally only when ALL futures succeed
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenAccept(v -> {
                if (!result.isDone()) {
                    result.complete(futures.stream()
                        .map(CompletableFuture::join)
                        .collect(Collectors.joining(" ")));
                }
            });

        return result;
    }

    public CompletableFuture<List<String>> processAsyncFailPartial(
            List<Microservice> services,
            List<String> messages) {

        // Input validation: invalid arguments are treated as programmer errors.
        if (services == null || messages == null) {
            CompletableFuture<List<String>> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalArgumentException("services and messages must not be null"));
            return failed;
        }

        if (services.size() != messages.size()) {
            CompletableFuture<List<String>> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalArgumentException("services and messages must have the same size"));
            return failed;
        }

        // Fail-partial policy: never fail the aggregate future due to per-service failures.
        List<CompletableFuture<String>> futures = IntStream.range(0, services.size())
            .mapToObj(i -> {
                Microservice service = services.get(i);
                String message = messages.get(i);

                if (service == null || message == null) {
                    return CompletableFuture.<String>completedFuture(null);
                }

                try {
                    CompletableFuture<String> future = service.retrieveAsync(message);
                    if (future == null) {
                        return CompletableFuture.<String>completedFuture(null);
                    }

                    // Per-service failures are suppressed and omitted from final results.
                    return future.handle((value, ex) -> ex == null ? value : null);
                } catch (RuntimeException ex) {
                    return CompletableFuture.<String>completedFuture(null);
                }
            })
            .collect(Collectors.toList());

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream()
                .map(f -> f.getNow(null))
                .filter(value -> value != null)
                .collect(Collectors.toList()))
            .exceptionally(ex -> Collections.emptyList());
    }

    /**
     * Fail-soft policy:
     * 1) every service is invoked concurrently,
     * 2) every failure is converted to a fallback value,
     * 3) the returned future completes normally.
     *
     * Risk note: this improves availability but can mask real production failures.
     * Hidden failures can make diagnosis harder and may propagate degraded data.
     */
    public CompletableFuture<String> processAsyncFailSoft(
            List<Microservice> services,
            List<String> messages,
            String fallbackValue) {

        final String safeFallback = fallbackValue == null ? "" : fallbackValue;

        // Always complete normally, even for invalid inputs.
        if (services == null || services.isEmpty()) {
            return CompletableFuture.completedFuture("");
        }

        List<CompletableFuture<String>> futures = IntStream.range(0, services.size())
            .mapToObj(i -> {
                Microservice service = services.get(i);
                String message = (messages != null && i < messages.size()) ? messages.get(i) : null;

                if (service == null) {
                    return CompletableFuture.completedFuture(safeFallback);
                }

                if (message == null) {
                    return CompletableFuture.completedFuture(safeFallback);
                }
                try {
                    CompletableFuture<String> future = service.retrieveAsync(message);
                    if (future == null) {
                        return CompletableFuture.completedFuture(safeFallback);
                    }

                    // Any per-service failure or null output becomes fallback.
                    return future.handle((value, ex) -> {
                        if (ex != null || value == null) {
                            return safeFallback;
                        }
                        return value;
                    });
                } catch (RuntimeException ex) {
                    return CompletableFuture.completedFuture(safeFallback);
                }
            })
            .collect(Collectors.toList());

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream()
                .map(f -> f.getNow(safeFallback))
                .map(value -> value == null ? safeFallback : value)
                .collect(Collectors.joining(" ")))
            // Final safety net: keep aggregate completion normal.
            .exceptionally(ex -> IntStream.range(0, services.size())
                .mapToObj(i -> safeFallback)
                .collect(Collectors.joining(" ")));
    }

    public CompletableFuture<String> processAsync(List<Microservice> microservices, String message) {
    	
        List<CompletableFuture<String>> futures = microservices.stream()
            .map(client -> client.retrieveAsync(message))
            .collect(Collectors.toList());
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.joining(" ")));
        
    }
    
    public CompletableFuture<List<String>> processAsyncCompletionOrder(
            List<Microservice> microservices, String message) {

        List<String> completionOrder =
            Collections.synchronizedList(new ArrayList<>());

        List<CompletableFuture<Void>> futures = microservices.stream()
            .map(ms -> ms.retrieveAsync(message)
                .thenAccept(completionOrder::add))
            .collect(Collectors.toList());

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> completionOrder);
        
    }
    
}
