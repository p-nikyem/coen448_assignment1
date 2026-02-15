package coen448.computablefuture.test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * JUnit 5 test suite for AsyncProcessor failure semantics.
 * Tests three failure-handling policies: Fail-Fast, Fail-Partial, Fail-Soft.
 * 
 * NO MOCKITO - uses lightweight in-test stubs only.
 */
public class AsyncProcessorTest {

    // =====================================================================
    // LIGHTWEIGHT TEST STUBS (No Mockito)
    // =====================================================================

    /**
     * Always-success microservice stub with optional delay.
     */
    static class SuccessService extends Microservice {
        private final String response;
        private final long delayMs;

        SuccessService(String serviceId, String response) {
            this(serviceId, response, 0);
        }

        SuccessService(String serviceId, String response, long delayMs) {
            super(serviceId);
            this.response = response;
            this.delayMs = delayMs;
        }

        @Override
        public CompletableFuture<String> retrieveAsync(String message) {
            return CompletableFuture.supplyAsync(() -> {
                if (delayMs > 0) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(delayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                }
                return response;
            });
        }
    }

    /**
     * Always-failing microservice stub that completes exceptionally.
     */
    static class FailingService extends Microservice {
        private final RuntimeException exception;
        private final long delayMs;

        FailingService(String serviceId) {
            this(serviceId, new RuntimeException("Service " + serviceId + " failed"), 0);
        }

        FailingService(String serviceId, RuntimeException exception) {
            this(serviceId, exception, 0);
        }

        FailingService(String serviceId, RuntimeException exception, long delayMs) {
            super(serviceId);
            this.exception = exception;
            this.delayMs = delayMs;
        }

        @Override
        public CompletableFuture<String> retrieveAsync(String message) {
            return CompletableFuture.supplyAsync(() -> {
                if (delayMs > 0) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(delayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                throw exception;
            });
        }
    }

    /**
     * Service that throws synchronously during retrieveAsync invocation.
     */
    static class SynchronouslyFailingService extends Microservice {
        SynchronouslyFailingService(String serviceId) {
            super(serviceId);
        }

        @Override
        public CompletableFuture<String> retrieveAsync(String message) {
            throw new RuntimeException("Synchronous failure in " + message);
        }
    }

    // =====================================================================
    // FAIL-FAST TESTS
    // =====================================================================

    @Nested
    @DisplayName("Fail-Fast Policy Tests")
    class FailFastTests {

        private final AsyncProcessor processor = new AsyncProcessor();

        @Test
        @DisplayName("All services succeed - returns concatenated results")
        void allSuccess_returnsConcatenatedResults() throws Exception {
            List<Microservice> services = List.of(
                new SuccessService("s1", "Hello"),
                new SuccessService("s2", "World")
            );
            List<String> messages = List.of("msg1", "msg2");

            String result = processor.processAsyncFailFast(services, messages)
                .get(2, TimeUnit.SECONDS);

            assertEquals("Hello World", result);
        }

        @Test
        @DisplayName("One service fails - exception propagates immediately")
        void oneFailure_exceptionPropagates() {
            List<Microservice> services = List.of(
                new SuccessService("s1", "Hello"),
                new FailingService("s2", new RuntimeException("Service s2 failed"))
            );
            List<String> messages = List.of("msg1", "msg2");

            CompletableFuture<String> future = processor.processAsyncFailFast(services, messages);

            ExecutionException ex = assertThrows(ExecutionException.class,
                () -> future.get(2, TimeUnit.SECONDS));
            assertTrue(ex.getCause().getMessage().contains("s2 failed"));
        }

        @Test
        @DisplayName("Multiple services fail - first exception propagates")
        void multipleFailures_exceptionPropagates() {
            List<Microservice> services = List.of(
                new FailingService("s1"),
                new FailingService("s2"),
                new FailingService("s3")
            );
            List<String> messages = List.of("msg1", "msg2", "msg3");

            CompletableFuture<String> future = processor.processAsyncFailFast(services, messages);

            assertThrows(ExecutionException.class,
                () -> future.get(2, TimeUnit.SECONDS));
            assertTrue(future.isCompletedExceptionally());
        }

        @Test
        @DisplayName("First service fails among many - entire operation fails")
        void firstFails_entireOperationFails() {
            List<Microservice> services = List.of(
                new FailingService("fast-fail", new RuntimeException("Fast failure"), 0),
                new SuccessService("slow1", "Result1", 500),
                new SuccessService("slow2", "Result2", 500)
            );
            List<String> messages = List.of("msg1", "msg2", "msg3");

            CompletableFuture<String> future = processor.processAsyncFailFast(services, messages);

            ExecutionException ex = assertThrows(ExecutionException.class,
                () -> future.get(2, TimeUnit.SECONDS));
            assertTrue(ex.getCause().getMessage().contains("Fast failure"));
        }

        @Test
        @DisplayName("Null services list - completes exceptionally")
        void nullServices_completesExceptionally() {
            CompletableFuture<String> future = processor.processAsyncFailFast(null, List.of("msg"));

            assertThrows(ExecutionException.class,
                () -> future.get(1, TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("Null messages list - completes exceptionally")
        void nullMessages_completesExceptionally() {
            List<Microservice> services = List.of(new SuccessService("s1", "Hello"));

            CompletableFuture<String> future = processor.processAsyncFailFast(services, null);

            assertThrows(ExecutionException.class,
                () -> future.get(1, TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("Mismatched list sizes - completes exceptionally")
        void mismatchedSizes_completesExceptionally() {
            List<Microservice> services = List.of(
                new SuccessService("s1", "Hello"),
                new SuccessService("s2", "World")
            );
            List<String> messages = List.of("msg1");

            CompletableFuture<String> future = processor.processAsyncFailFast(services, messages);

            assertThrows(ExecutionException.class,
                () -> future.get(1, TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("Empty lists - returns empty string")
        void emptyLists_returnsEmptyString() throws Exception {
            String result = processor.processAsyncFailFast(List.of(), List.of())
                .get(1, TimeUnit.SECONDS);

            assertEquals("", result);
        }
    }

    // =====================================================================
    // FAIL-PARTIAL TESTS
    // =====================================================================

    @Nested
    @DisplayName("Fail-Partial Policy Tests")
    class FailPartialTests {

        private final AsyncProcessor processor = new AsyncProcessor();

        @Test
        @DisplayName("All services succeed - returns all results")
        void allSuccess_returnsAllResults() throws Exception {
            List<Microservice> services = List.of(
                new SuccessService("s1", "Hello"),
                new SuccessService("s2", "World"),
                new SuccessService("s3", "Test")
            );
            List<String> messages = List.of("msg1", "msg2", "msg3");

            List<String> result = processor.processAsyncFailPartial(services, messages)
                .get(2, TimeUnit.SECONDS);

            assertEquals(3, result.size());
            assertTrue(result.contains("Hello"));
            assertTrue(result.contains("World"));
            assertTrue(result.contains("Test"));
        }

        @Test
        @DisplayName("One service fails - returns partial results without exception")
        void oneFailure_returnsPartialResults() throws Exception {
            List<Microservice> services = List.of(
                new SuccessService("s1", "Hello"),
                new FailingService("s2"),
                new SuccessService("s3", "World")
            );
            List<String> messages = List.of("msg1", "msg2", "msg3");

            List<String> result = processor.processAsyncFailPartial(services, messages)
                .get(2, TimeUnit.SECONDS);

            // No exception thrown - this is the key semantic
            assertDoesNotThrow(() -> result.size());
            assertEquals(2, result.size());
            assertTrue(result.contains("Hello"));
            assertTrue(result.contains("World"));
            assertFalse(result.stream().anyMatch(s -> s.contains("s2")));
        }

        @Test
        @DisplayName("Multiple services fail - returns only successful results")
        void multipleFailures_returnsOnlySuccessful() throws Exception {
            List<Microservice> services = List.of(
                new FailingService("fail1"),
                new SuccessService("s1", "OnlySuccess"),
                new FailingService("fail2"),
                new FailingService("fail3")
            );
            List<String> messages = List.of("msg1", "msg2", "msg3", "msg4");

            List<String> result = processor.processAsyncFailPartial(services, messages)
                .get(2, TimeUnit.SECONDS);

            assertEquals(1, result.size());
            assertEquals("OnlySuccess", result.get(0));
        }

        @Test
        @DisplayName("All services fail - returns empty list without exception")
        void allFail_returnsEmptyList() throws Exception {
            List<Microservice> services = List.of(
                new FailingService("fail1"),
                new FailingService("fail2"),
                new FailingService("fail3")
            );
            List<String> messages = List.of("msg1", "msg2", "msg3");

            List<String> result = processor.processAsyncFailPartial(services, messages)
                .get(2, TimeUnit.SECONDS);

            // No exception - aggregate completes normally
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Null service in list - handled gracefully")
        void nullServiceInList_handledGracefully() throws Exception {
            List<Microservice> services = new ArrayList<>();
            services.add(new SuccessService("s1", "Hello"));
            services.add(null);
            services.add(new SuccessService("s3", "World"));
            List<String> messages = List.of("msg1", "msg2", "msg3");

            List<String> result = processor.processAsyncFailPartial(services, messages)
                .get(2, TimeUnit.SECONDS);

            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("Null services list - completes exceptionally (input validation)")
        void nullServices_completesExceptionally() {
            CompletableFuture<List<String>> future = processor.processAsyncFailPartial(null, List.of("msg"));

            assertThrows(ExecutionException.class,
                () -> future.get(1, TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("Mismatched sizes - completes exceptionally (input validation)")
        void mismatchedSizes_completesExceptionally() {
            List<Microservice> services = List.of(new SuccessService("s1", "Hello"));
            List<String> messages = List.of("msg1", "msg2");

            CompletableFuture<List<String>> future = processor.processAsyncFailPartial(services, messages);

            assertThrows(ExecutionException.class,
                () -> future.get(1, TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("Empty lists - returns empty list")
        void emptyLists_returnsEmptyList() throws Exception {
            List<String> result = processor.processAsyncFailPartial(List.of(), List.of())
                .get(1, TimeUnit.SECONDS);

            assertTrue(result.isEmpty());
        }
    }

    // =====================================================================
    // FAIL-SOFT TESTS
    // =====================================================================

    @Nested
    @DisplayName("Fail-Soft Policy Tests")
    class FailSoftTests {

        private final AsyncProcessor processor = new AsyncProcessor();
        private static final String FALLBACK = "FALLBACK";

        @Test
        @DisplayName("All services succeed - returns all results")
        void allSuccess_returnsAllResults() throws Exception {
            List<Microservice> services = List.of(
                new SuccessService("s1", "Hello"),
                new SuccessService("s2", "World")
            );
            List<String> messages = List.of("msg1", "msg2");

            String result = processor.processAsyncFailSoft(services, messages, FALLBACK)
                .get(2, TimeUnit.SECONDS);

            assertEquals("Hello World", result);
        }

        @Test
        @DisplayName("One service fails - fallback replaces failed result")
        void oneFailure_fallbackUsed() throws Exception {
            List<Microservice> services = List.of(
                new SuccessService("s1", "Hello"),
                new FailingService("s2"),
                new SuccessService("s3", "World")
            );
            List<String> messages = List.of("msg1", "msg2", "msg3");

            String result = processor.processAsyncFailSoft(services, messages, FALLBACK)
                .get(2, TimeUnit.SECONDS);

            // No exception thrown
            assertDoesNotThrow(() -> result.length());
            assertEquals("Hello FALLBACK World", result);
        }

        @Test
        @DisplayName("Multiple services fail - multiple fallbacks used")
        void multipleFailures_multipleFallbacksUsed() throws Exception {
            List<Microservice> services = List.of(
                new FailingService("fail1"),
                new SuccessService("s1", "Success"),
                new FailingService("fail2")
            );
            List<String> messages = List.of("msg1", "msg2", "msg3");

            String result = processor.processAsyncFailSoft(services, messages, FALLBACK)
                .get(2, TimeUnit.SECONDS);

            assertEquals("FALLBACK Success FALLBACK", result);
        }

        @Test
        @DisplayName("All services fail - all fallbacks returned, no exception")
        void allFail_allFallbacksReturned() throws Exception {
            List<Microservice> services = List.of(
                new FailingService("fail1"),
                new FailingService("fail2"),
                new FailingService("fail3")
            );
            List<String> messages = List.of("msg1", "msg2", "msg3");

            String result = processor.processAsyncFailSoft(services, messages, FALLBACK)
                .get(2, TimeUnit.SECONDS);

            assertEquals("FALLBACK FALLBACK FALLBACK", result);
        }

        @Test
        @DisplayName("Null fallback value - uses empty string")
        void nullFallback_usesEmptyString() throws Exception {
            List<Microservice> services = List.of(
                new SuccessService("s1", "Hello"),
                new FailingService("s2")
            );
            List<String> messages = List.of("msg1", "msg2");

            String result = processor.processAsyncFailSoft(services, messages, null)
                .get(2, TimeUnit.SECONDS);

            assertEquals("Hello ", result);
        }

        @Test
        @DisplayName("Null services list - returns empty string, never fails")
        void nullServices_returnsEmptyString() throws Exception {
            String result = processor.processAsyncFailSoft(null, List.of("msg"), FALLBACK)
                .get(1, TimeUnit.SECONDS);

            assertEquals("", result);
        }

        @Test
        @DisplayName("Null messages list - fallbacks used, never fails")
        void nullMessages_useFallbacks() throws Exception {
            List<Microservice> services = List.of(
                new SuccessService("s1", "Ignored")
            );

            String result = processor.processAsyncFailSoft(services, null, FALLBACK)
                .get(1, TimeUnit.SECONDS);

            // Should complete normally with fallback
            assertNotNull(result);
        }

        @Test
        @DisplayName("Mismatched list sizes - completes normally with fallbacks")
        void mismatchedSizes_completesNormally() throws Exception {
            List<Microservice> services = List.of(
                new SuccessService("s1", "Hello"),
                new SuccessService("s2", "World"),
                new SuccessService("s3", "Extra")
            );
            List<String> messages = List.of("msg1");

            // Should not throw - fail-soft always completes normally
            String result = processor.processAsyncFailSoft(services, messages, FALLBACK)
                .get(2, TimeUnit.SECONDS);

            assertEquals("Hello FALLBACK FALLBACK", result);
        }

        @Test
        @DisplayName("Empty services list - returns empty string")
        void emptyServices_returnsEmptyString() throws Exception {
            String result = processor.processAsyncFailSoft(List.of(), List.of(), FALLBACK)
                .get(1, TimeUnit.SECONDS);

            assertEquals("", result);
        }

        @Test
        @DisplayName("Null service in list - fallback used for that position")
        void nullServiceInList_fallbackUsed() throws Exception {
            List<Microservice> services = new ArrayList<>();
            services.add(new SuccessService("s1", "Hello"));
            services.add(null);
            services.add(new SuccessService("s3", "World"));
            List<String> messages = List.of("msg1", "msg2", "msg3");

            String result = processor.processAsyncFailSoft(services, messages, FALLBACK)
                .get(2, TimeUnit.SECONDS);

            assertEquals("Hello FALLBACK World", result);
        }
    }

    // =====================================================================
    // LIVENESS TESTS (No deadlock / infinite waiting)
    // =====================================================================

    @Nested
    @DisplayName("Liveness Tests")
    class LivenessTests {

        private final AsyncProcessor processor = new AsyncProcessor();

        @Test
        @DisplayName("Fail-Fast with mixed workload completes within deadline")
        void failFast_mixedWorkload_completesWithinDeadline() {
            List<Microservice> services = List.of(
                new SuccessService("fast", "Fast", 10),
                new FailingService("fail", new RuntimeException("Expected"), 50),
                new SuccessService("slow", "Slow", 200)
            );
            List<String> messages = List.of("msg1", "msg2", "msg3");

            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                CompletableFuture<String> future = processor.processAsyncFailFast(services, messages);
                try {
                    future.get(500, TimeUnit.MILLISECONDS);
                    fail("Should have thrown due to failure");
                } catch (ExecutionException e) {
                    // Expected - fail-fast should complete exceptionally
                    assertTrue(future.isCompletedExceptionally());
                }
            });
        }

        @Test
        @DisplayName("Fail-Partial with mixed workload completes within deadline")
        void failPartial_mixedWorkload_completesWithinDeadline() {
            List<Microservice> services = List.of(
                new SuccessService("fast", "Fast", 10),
                new FailingService("fail", new RuntimeException("Expected"), 50),
                new SuccessService("medium", "Medium", 100),
                new SuccessService("slow", "Slow", 200)
            );
            List<String> messages = List.of("msg1", "msg2", "msg3", "msg4");

            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                List<String> result = processor.processAsyncFailPartial(services, messages)
                    .get(1, TimeUnit.SECONDS);

                assertEquals(3, result.size());
            });
        }

        @Test
        @DisplayName("Fail-Soft with mixed workload completes within deadline")
        void failSoft_mixedWorkload_completesWithinDeadline() {
            List<Microservice> services = List.of(
                new SuccessService("fast", "Fast", 10),
                new FailingService("fail1", new RuntimeException("Fail1"), 30),
                new SuccessService("medium", "Medium", 100),
                new FailingService("fail2", new RuntimeException("Fail2"), 150),
                new SuccessService("slow", "Slow", 200)
            );
            List<String> messages = List.of("msg1", "msg2", "msg3", "msg4", "msg5");

            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                String result = processor.processAsyncFailSoft(services, messages, "FB")
                    .get(1, TimeUnit.SECONDS);

                assertEquals("Fast FB Medium FB Slow", result);
            });
        }

        @Test
        @DisplayName("Large batch of services completes within deadline")
        void largeBatch_completesWithinDeadline() {
            List<Microservice> services = new ArrayList<>();
            List<String> messages = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                services.add(new SuccessService("s" + i, "Result" + i, 10));
                messages.add("msg" + i);
            }

            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                String result = processor.processAsyncFailFast(services, messages)
                    .get(3, TimeUnit.SECONDS);

                // Should contain all 50 results
                String[] parts = result.split(" ");
                assertEquals(50, parts.length);
            });
        }

        @Test
        @DisplayName("All policies complete - no deadlock with concurrent failures")
        void allPolicies_noDeadlock() {
            List<Microservice> services = List.of(
                new FailingService("f1"),
                new FailingService("f2"),
                new FailingService("f3")
            );
            List<String> messages = List.of("m1", "m2", "m3");

            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                // Fail-Fast should complete exceptionally
                CompletableFuture<String> ff = processor.processAsyncFailFast(services, messages);
                assertThrows(ExecutionException.class, () -> ff.get(1, TimeUnit.SECONDS));

                // Fail-Partial should complete normally with empty result
                List<String> partialResult = processor.processAsyncFailPartial(services, messages)
                    .get(1, TimeUnit.SECONDS);
                assertTrue(partialResult.isEmpty());

                // Fail-Soft should complete normally with all fallbacks
                String softResult = processor.processAsyncFailSoft(services, messages, "FB")
                    .get(1, TimeUnit.SECONDS);
                assertEquals("FB FB FB", softResult);
            });
        }
    }

    // =====================================================================
    // NONDETERMINISM OBSERVATION TESTS
    // =====================================================================

    @Nested
    @DisplayName("Nondeterminism Observation Tests")
    class NondeterminismTests {

        private final AsyncProcessor processor = new AsyncProcessor();

        @RepeatedTest(10)
        @DisplayName("Completion order varies but all values present (observation only)")
        void completionOrder_varies_allValuesPresent() throws Exception {
            // Services with random delays to create nondeterministic completion order
            Microservice s1 = new Microservice("A");
            Microservice s2 = new Microservice("B");
            Microservice s3 = new Microservice("C");

            List<String> order = processor
                .processAsyncCompletionOrder(List.of(s1, s2, s3), "msg")
                .get(2, TimeUnit.SECONDS);

            // Log observed order (for human inspection, not assertion)
            System.out.println("Completion order: " + order);

            // Assert only stable properties - NOT order
            assertEquals(3, order.size());
            assertTrue(order.stream().anyMatch(x -> x.startsWith("A:")));
            assertTrue(order.stream().anyMatch(x -> x.startsWith("B:")));
            assertTrue(order.stream().anyMatch(x -> x.startsWith("C:")));
        }

        @RepeatedTest(5)
        @DisplayName("Fail-Partial with delays - order varies but values correct")
        void failPartial_orderVaries_valuesCorrect() throws Exception {
            List<Microservice> services = List.of(
                new SuccessService("slow", "Slow", 50),
                new SuccessService("fast", "Fast", 5),
                new SuccessService("medium", "Medium", 25)
            );
            List<String> messages = List.of("m1", "m2", "m3");

            List<String> result = processor.processAsyncFailPartial(services, messages)
                .get(2, TimeUnit.SECONDS);

            System.out.println("Fail-Partial results: " + result);

            // Assert only stable properties
            assertEquals(3, result.size());
            assertTrue(result.contains("Slow"));
            assertTrue(result.contains("Fast"));
            assertTrue(result.contains("Medium"));
            // Note: Order in result list matches input order, not completion order
        }

        @RepeatedTest(5)
        @DisplayName("Timestamps show concurrent execution")
        void timestamps_showConcurrentExecution() throws Exception {
            List<Long> timestamps = Collections.synchronizedList(new ArrayList<>());
            long startTime = System.currentTimeMillis();

            // Custom services that record timestamps
            class TimestampService extends Microservice {
                private final long delay;

                TimestampService(String id, long delay) {
                    super(id);
                    this.delay = delay;
                }

                @Override
                public CompletableFuture<String> retrieveAsync(String message) {
                    return CompletableFuture.supplyAsync(() -> {
                        try {
                            TimeUnit.MILLISECONDS.sleep(delay);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        timestamps.add(System.currentTimeMillis() - startTime);
                        return "Done";
                    });
                }
            }

            List<Microservice> services = List.of(
                new TimestampService("s1", 100),
                new TimestampService("s2", 100),
                new TimestampService("s3", 100)
            );
            List<String> messages = List.of("m1", "m2", "m3");

            String result = processor.processAsyncFailSoft(services, messages, "FB")
                .get(2, TimeUnit.SECONDS);

            System.out.println("Timestamps: " + timestamps);

            // All should complete around the same time (concurrent execution)
            // If sequential, total time would be ~300ms
            // If concurrent, all finish around ~100ms mark
            long maxTimestamp = timestamps.stream().mapToLong(Long::longValue).max().orElse(0);
            assertTrue(maxTimestamp < 250, "Should complete concurrently, not sequentially");
        }
    }

    // =====================================================================
    // ORIGINAL TESTS (preserved)
    // =====================================================================

    @Nested
    @DisplayName("Original Tests")
    class OriginalTests {

        @RepeatedTest(5)
        void testProcessAsyncSuccess() throws Exception {
            Microservice service1 = new SuccessService("s1", "Hello");
            Microservice service2 = new SuccessService("s2", "World");

            AsyncProcessor processor = new AsyncProcessor();
            String result = processor.processAsync(List.of(service1, service2), "test")
                .get(2, TimeUnit.SECONDS);

            assertEquals("Hello World", result);
        }

        @ParameterizedTest
        @CsvSource({
            "hi, Hello:HI World:HI",
            "cloud, Hello:CLOUD World:CLOUD",
            "async, Hello:ASYNC World:ASYNC"
        })
        void testProcessAsync_withDifferentMessages(
                String message,
                String expectedResult) throws Exception {

            Microservice service1 = new Microservice("Hello");
            Microservice service2 = new Microservice("World");

            AsyncProcessor processor = new AsyncProcessor();
            String result = processor.processAsync(List.of(service1, service2), message)
                .get(1, TimeUnit.SECONDS);

            assertEquals(expectedResult, result);
        }

        @RepeatedTest(20)
        void showNondeterminism_completionOrderVaries() throws Exception {
            Microservice s1 = new Microservice("A");
            Microservice s2 = new Microservice("B");
            Microservice s3 = new Microservice("C");

            AsyncProcessor processor = new AsyncProcessor();

            List<String> order = processor
                .processAsyncCompletionOrder(List.of(s1, s2, s3), "msg")
                .get(1, TimeUnit.SECONDS);

            System.out.println(order);

            assertEquals(3, order.size());
            assertTrue(order.stream().anyMatch(x -> x.startsWith("A:")));
            assertTrue(order.stream().anyMatch(x -> x.startsWith("B:")));
            assertTrue(order.stream().anyMatch(x -> x.startsWith("C:")));
        }
    }
}