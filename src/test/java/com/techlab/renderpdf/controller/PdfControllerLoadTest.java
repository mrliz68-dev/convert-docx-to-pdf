package com.techlab.renderpdf.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.techlab.renderpdf.model.PdfGenerationRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Load test for PDF generation API
 * Simulates 10 requests/second for 1 minute (600 requests total)
 * 
 * VỀ VIRTUAL THREADS VÀ THREAD POOL:
 * 
 * 1. VIRTUAL THREADS TRONG SPRING BOOT (Server-side):
 *    - Virtual threads được enable trong application.yml: spring.threads.virtual.enabled=true
 *    - Được dùng để xử lý các HTTP requests từ client đến server
 *    - Spring Boot tự động sử dụng virtual threads cho các incoming requests
 *    - Lợi ích: Có thể xử lý hàng ngàn concurrent requests với ít memory hơn
 * 
 * 2. EXECUTOR SERVICE TRONG TEST (Client-side):
 *    - Executor service trong test code là để GIẢ LẬP CLIENT
 *    - Dùng để gửi nhiều HTTP requests đồng thời từ phía test client
 *    - Nếu dùng traditional thread pool (FixedThreadPool): Giới hạn số concurrent requests
 *    - Nếu dùng virtual thread executor: Có thể tạo hàng ngàn concurrent client requests
 * 
 * 3. TẠI SAO DÙNG VIRTUAL THREADS CHO CẢ CLIENT VÀ SERVER:
 *    - Client-side: Có thể gửi nhiều requests đồng thời hơn mà không bị giới hạn bởi thread pool size
 *    - Server-side: Có thể xử lý tất cả các requests đó một cách hiệu quả
 *    - Kết hợp cả hai: Test được khả năng thực sự của server khi handle nhiều concurrent requests
 */
@SpringBootTest
@AutoConfigureMockMvc
public class PdfControllerLoadTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Quick test: 10 requests/second for 10 seconds (100 requests)
     * Dùng để test nhanh trước khi chạy test đầy đủ
     */
    @Test
    public void testLoad10RequestsPerSecondFor10Seconds() throws Exception {
        runLoadTest(10, 10, "template-1");
    }

    /**
     * Full load test: 10 requests/second for 1 minute (600 requests total)
     * Chạy test đầy đủ để phân tích performance
     */
    @Test
    public void testLoad10RequestsPerSecondFor1Minute() throws Exception {
        runLoadTest(10, 60, "template-1");
    }

    /**
     * Concurrent capacity test: Tìm số lượng concurrent requests tối đa mà server có thể xử lý
     * Test với nhiều mức concurrent khác nhau để tìm breaking point
     */
    @Test
    public void testMaxConcurrentRequests() throws Exception {
        runConcurrentCapacityTest("template-1");
    }

    /**
     * Load test implementation
     * 
     * @param requestsPerSecond Số requests mỗi giây
     * @param durationSeconds Thời gian chạy test (giây)
     * @param templateName Tên template để test
     */
    private void runLoadTest(int requestsPerSecond, int durationSeconds, String templateName) throws Exception {
        System.out.println("\n" + "=".repeat(80));
        System.out.printf("LOAD TEST: %d requests/second for %d seconds (%d requests total)%n", 
                requestsPerSecond, durationSeconds, requestsPerSecond * durationSeconds);
        System.out.println("=".repeat(80) + "\n");

        // Test configuration
        int totalRequests = requestsPerSecond * durationSeconds;

        // Metrics collection
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        AtomicLong totalResponseTime = new AtomicLong(0);
        List<Long> responseTimes = Collections.synchronizedList(new ArrayList<>());
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        // Prepare request body
        PdfGenerationRequest request = new PdfGenerationRequest();
        request.setTemplateName(templateName);
        Map<String, Object> variables = new HashMap<>();
        variables.put("testVar1", "Test Value 1");
        variables.put("testVar2", "Test Value 2");
        request.setVariables(variables);

        String requestBody = objectMapper.writeValueAsString(request);

        // Sử dụng Virtual Thread Executor cho client-side (gửi requests)
        // Virtual threads rất phù hợp cho I/O-bound operations như HTTP requests
        // Không cần giới hạn thread pool size như traditional threads
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<?>> futures = new ArrayList<>();

        long startTime = System.currentTimeMillis();
        long testEndTime = startTime + (durationSeconds * 1000L);

        System.out.println("Starting load test at: " + new Date(startTime));
        System.out.println("Target: " + requestsPerSecond + " requests/second");
        System.out.println("Duration: " + durationSeconds + " seconds");
        System.out.println("Total requests: " + totalRequests);
        System.out.println("Client-side executor: Virtual Thread Per Task Executor");
        System.out.println("  ℹ️  Virtual threads cho phép tạo hàng ngàn concurrent client requests");
        System.out.println("  ℹ️  Server-side đã enable virtual threads để xử lý các requests này");
        System.out.println("\n" + "-".repeat(80) + "\n");

        // Schedule requests: 10 requests every second
        int requestNumber = 0;
        while (System.currentTimeMillis() < testEndTime && requestNumber < totalRequests) {
            // Calculate delay to maintain 10 requests/second
            long nextRequestTime = startTime + ((requestNumber / requestsPerSecond) * 1000L) 
                    + ((requestNumber % requestsPerSecond) * (1000L / requestsPerSecond));
            long delay = Math.max(0, nextRequestTime - System.currentTimeMillis());
            
            if (delay > 0) {
                Thread.sleep(delay);
            }

            final int currentRequest = requestNumber++;

            Future<?> future = executor.submit(() -> {
                long requestStart = System.currentTimeMillis();
                try {
                    MvcResult result = mockMvc.perform(
                            post("/api/pdf/generate")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(requestBody)
                    )
                    .andExpect(status().isOk())
                    .andReturn();

                    long requestDuration = System.currentTimeMillis() - requestStart;
                    responseTimes.add(requestDuration);
                    totalResponseTime.addAndGet(requestDuration);
                    successCount.incrementAndGet();

                    byte[] responseBody = result.getResponse().getContentAsByteArray();
                    if (responseBody.length > 0) {
                        System.out.printf("[Request #%d] SUCCESS - Response time: %d ms, Size: %d bytes%n",
                                currentRequest, requestDuration, responseBody.length);
                    }

                } catch (Exception e) {
                    long requestDuration = System.currentTimeMillis() - requestStart;
                    errorCount.incrementAndGet();
                    String errorMsg = String.format("[Request #%d] ERROR after %d ms: %s",
                            currentRequest, requestDuration, 
                            e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                    errors.add(errorMsg);
                    System.err.println(errorMsg);
                }
            });

            futures.add(future);
        }

        System.out.println("\n" + "-".repeat(80));
        System.out.println("Waiting for all requests to complete...");
        System.out.println("-".repeat(80) + "\n");

        // Wait for all requests to complete (with timeout)
        for (Future<?> future : futures) {
            try {
                future.get(30, TimeUnit.SECONDS); // Timeout 30 giây cho mỗi request
            } catch (TimeoutException e) {
                future.cancel(true);
                errorCount.incrementAndGet();
                errors.add("Request timeout: " + e.getMessage());
            } catch (Exception e) {
                errorCount.incrementAndGet();
                errors.add("Future error: " + e.getMessage());
            }
        }

        executor.shutdown();
        try {
            executor.awaitTermination(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long totalDuration = System.currentTimeMillis() - startTime;

        // Calculate statistics
        double actualRequestsPerSecond = (successCount.get() + errorCount.get()) * 1000.0 / totalDuration;
        double successRate = (successCount.get() + errorCount.get()) > 0 
                ? (successCount.get() * 100.0 / (successCount.get() + errorCount.get())) 
                : 0;

        // Response time statistics
        Collections.sort(responseTimes);
        long minResponseTime = responseTimes.isEmpty() ? 0 : responseTimes.get(0);
        long maxResponseTime = responseTimes.isEmpty() ? 0 : responseTimes.get(responseTimes.size() - 1);
        double avgResponseTime = responseTimes.isEmpty() ? 0 
                : responseTimes.stream().mapToLong(Long::longValue).average().orElse(0);
        
        // Percentiles
        long p50 = percentile(responseTimes, 50);
        long p75 = percentile(responseTimes, 75);
        long p90 = percentile(responseTimes, 90);
        long p95 = percentile(responseTimes, 95);
        long p99 = percentile(responseTimes, 99);

        // Display results
        System.out.println("\n" + "=".repeat(80));
        System.out.println("LOAD TEST RESULTS");
        System.out.println("=".repeat(80) + "\n");

        System.out.println("Test Configuration:");
        System.out.printf("  - Target rate: %d requests/second%n", requestsPerSecond);
        System.out.printf("  - Duration: %d seconds%n", durationSeconds);
        System.out.printf("  - Total requests: %d%n", totalRequests);
        System.out.printf("  - Client executor: Virtual Thread Per Task Executor (unlimited)%n");
        System.out.println();

        System.out.println("Test Execution:");
        System.out.printf("  - Start time: %s%n", new Date(startTime));
        System.out.printf("  - End time: %s%n", new Date(startTime + totalDuration));
        System.out.printf("  - Total duration: %.2f seconds%n", totalDuration / 1000.0);
        System.out.println();

        System.out.println("Request Statistics:");
        System.out.printf("  - Total requests sent: %d%n", successCount.get() + errorCount.get());
        System.out.printf("  - Successful requests: %d%n", successCount.get());
        System.out.printf("  - Failed requests: %d%n", errorCount.get());
        System.out.printf("  - Success rate: %.2f%%%n", successRate);
        System.out.printf("  - Actual throughput: %.2f requests/second%n", actualRequestsPerSecond);
        System.out.println();

        System.out.println("Response Time Statistics (ms):");
        System.out.printf("  - Minimum: %d ms%n", minResponseTime);
        System.out.printf("  - Maximum: %d ms%n", maxResponseTime);
        System.out.printf("  - Average: %.2f ms%n", avgResponseTime);
        System.out.printf("  - P50 (Median): %d ms%n", p50);
        System.out.printf("  - P75: %d ms%n", p75);
        System.out.printf("  - P90: %d ms%n", p90);
        System.out.printf("  - P95: %d ms%n", p95);
        System.out.printf("  - P99: %d ms%n", p99);
        System.out.println();

        // Error details
        if (!errors.isEmpty()) {
            System.out.println("Error Details:");
            System.out.printf("  - Total errors: %d%n", errors.size());
            if (errors.size() <= 20) {
                errors.forEach(error -> System.out.println("    " + error));
            } else {
                errors.subList(0, 20).forEach(error -> System.out.println("    " + error));
                System.out.printf("    ... and %d more errors%n", errors.size() - 20);
            }
            System.out.println();
        }

        // Performance analysis
        System.out.println("Performance Analysis:");
        System.out.println("-".repeat(80));
        
        // Throughput analysis
        String throughputStatus;
        if (actualRequestsPerSecond >= requestsPerSecond * 0.9) {
            throughputStatus = "✅ GOOD";
        } else if (actualRequestsPerSecond >= requestsPerSecond * 0.7) {
            throughputStatus = "⚠️  ACCEPTABLE";
        } else {
            throughputStatus = "❌ BELOW TARGET";
        }
        System.out.printf("  Throughput: %s (target: %d req/s, achieved: %.2f req/s, ratio: %.1f%%)%n",
                throughputStatus, requestsPerSecond, actualRequestsPerSecond, 
                (actualRequestsPerSecond / requestsPerSecond) * 100);

        // Success rate analysis
        String successRateStatus;
        if (successRate >= 95) {
            successRateStatus = "✅ EXCELLENT";
        } else if (successRate >= 90) {
            successRateStatus = "⚠️  GOOD";
        } else if (successRate >= 80) {
            successRateStatus = "⚠️  ACCEPTABLE";
        } else {
            successRateStatus = "❌ NEEDS IMPROVEMENT";
        }
        System.out.printf("  Success Rate: %s (%.2f%%)%n", successRateStatus, successRate);

        // Response time analysis
        String avgTimeStatus;
        if (avgResponseTime < 2000) {
            avgTimeStatus = "✅ GOOD";
        } else if (avgResponseTime < 5000) {
            avgTimeStatus = "⚠️  ACCEPTABLE";
        } else {
            avgTimeStatus = "❌ SLOW";
        }
        System.out.printf("  Average Response Time: %s (%.2f ms)%n", avgTimeStatus, avgResponseTime);

        String p95Status;
        if (p95 < 5000) {
            p95Status = "✅ GOOD";
        } else if (p95 < 10000) {
            p95Status = "⚠️  ACCEPTABLE";
        } else {
            p95Status = "❌ NEEDS ATTENTION";
        }
        System.out.printf("  P95 Response Time: %s (%d ms)%n", p95Status, p95);

        System.out.println();

        // Summary table
        System.out.println("Summary Table:");
        System.out.println("+".repeat(80));
        System.out.printf("| %-30s | %-45s |%n", "Metric", "Value");
        System.out.println("+".repeat(80));
        System.out.printf("| %-30s | %-45s |%n", "Total Requests", String.valueOf(successCount.get() + errorCount.get()));
        System.out.printf("| %-30s | %-45s |%n", "Successful", String.valueOf(successCount.get()));
        System.out.printf("| %-30s | %-45s |%n", "Failed", String.valueOf(errorCount.get()));
        System.out.printf("| %-30s | %-45s |%n", "Success Rate", String.format("%.2f%%", successRate));
        System.out.printf("| %-30s | %-45s |%n", "Throughput", String.format("%.2f req/s", actualRequestsPerSecond));
        System.out.printf("| %-30s | %-45s |%n", "Avg Response Time", String.format("%.2f ms", avgResponseTime));
        System.out.printf("| %-30s | %-45s |%n", "Min Response Time", String.format("%d ms", minResponseTime));
        System.out.printf("| %-30s | %-45s |%n", "Max Response Time", String.format("%d ms", maxResponseTime));
        System.out.printf("| %-30s | %-45s |%n", "P50 (Median)", String.format("%d ms", p50));
        System.out.printf("| %-30s | %-45s |%n", "P95", String.format("%d ms", p95));
        System.out.printf("| %-30s | %-45s |%n", "P99", String.format("%d ms", p99));
        System.out.printf("| %-30s | %-45s |%n", "Total Duration", String.format("%.2f seconds", totalDuration / 1000.0));
        System.out.println("+".repeat(80));

        System.out.println("\n" + "=".repeat(80) + "\n");

        // Assertions (optional - có thể comment nếu chỉ muốn xem kết quả)
        // Assertions.assertTrue(successCount.get() > 0, "Should have at least some successful requests");
        // Assertions.assertTrue(successRate >= 90, "Success rate should be at least 90%");
    }

    /**
     * Concurrent capacity test: Tìm số lượng concurrent requests tối đa mà server có thể xử lý
     * Test với nhiều mức concurrent khác nhau, gửi tất cả requests cùng lúc (burst test)
     * 
     * @param templateName Tên template để test
     */
    private void runConcurrentCapacityTest(String templateName) throws Exception {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("CONCURRENT CAPACITY TEST: Tìm số lượng concurrent requests tối đa");
        System.out.println("=".repeat(80) + "\n");

        // Các mức concurrent để test (tăng dần)
        int[] concurrentLevels = {50, 100, 200, 500, 1000, 2000, 3000, 5000, 10000};

        // Prepare request body
        PdfGenerationRequest request = new PdfGenerationRequest();
        request.setTemplateName(templateName);
        Map<String, Object> variables = new HashMap<>();
        variables.put("testVar1", "Test Value 1");
        variables.put("testVar2", "Test Value 2");
        request.setVariables(variables);

        final String requestBody = objectMapper.writeValueAsString(request);

        System.out.println("Test Strategy:");
        System.out.println("  - Gửi tất cả requests cùng lúc (burst) ở mỗi mức concurrent");
        System.out.println("  - Đợi tất cả requests hoàn thành hoặc timeout (60s)");
        System.out.println("  - Thu thập metrics để xác định breaking point");
        System.out.println("  - Breaking point: khi success rate < 95% hoặc có quá nhiều errors/timeouts");
        System.out.println("\n" + "-".repeat(80) + "\n");

        // Kết quả test cho từng mức concurrent
        List<ConcurrentTestResult> results = new ArrayList<>();

        for (int concurrentRequests : concurrentLevels) {
            System.out.printf("\n🧪 Testing with %d concurrent requests...%n", concurrentRequests);

            // Metrics collection
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger errorCount = new AtomicInteger(0);
            AtomicInteger timeoutCount = new AtomicInteger(0);
            AtomicLong totalResponseTime = new AtomicLong(0);
            List<Long> responseTimes = Collections.synchronizedList(new ArrayList<>());
            List<String> errors = Collections.synchronizedList(new ArrayList<>());

            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            CountDownLatch latch = new CountDownLatch(concurrentRequests);

            long testStartTime = System.currentTimeMillis();

            // Gửi tất cả requests cùng lúc
            for (int i = 0; i < concurrentRequests; i++) {
                final int requestId = i;
                executor.submit(() -> {
                    long requestStart = System.currentTimeMillis();
                    try {
                        // Timeout cho mỗi request: 60 giây
                        mockMvc.perform(
                                post("/api/pdf/generate")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(requestBody)
                        )
                        .andExpect(status().isOk())
                        .andReturn();

                        long requestDuration = System.currentTimeMillis() - requestStart;
                        responseTimes.add(requestDuration);
                        totalResponseTime.addAndGet(requestDuration);
                        successCount.incrementAndGet();

                    } catch (Exception e) {
                        long requestDuration = System.currentTimeMillis() - requestStart;
                        
                        // Kiểm tra timeout
                        if (requestDuration >= 60000) {
                            timeoutCount.incrementAndGet();
                            errors.add(String.format("Request #%d: TIMEOUT (>60s)", requestId));
                        } else {
                            errorCount.incrementAndGet();
                            String errorMsg = e.getMessage();
                            if (errorMsg == null || errorMsg.isEmpty()) {
                                errorMsg = e.getClass().getSimpleName();
                            }
                            errors.add(String.format("Request #%d: %s", requestId, errorMsg));
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            // Đợi tất cả requests hoàn thành hoặc timeout (70 giây total)
            boolean completed = latch.await(70, TimeUnit.SECONDS);
            long testDuration = System.currentTimeMillis() - testStartTime;

            executor.shutdown();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }

            // Calculate metrics
            int total = successCount.get() + errorCount.get() + timeoutCount.get();
            double successRate = total > 0 ? (double) successCount.get() / total * 100 : 0;
            double avgResponseTime = successCount.get() > 0 ? (double) totalResponseTime.get() / successCount.get() : 0;

            Collections.sort(responseTimes);
            long minResponseTime = responseTimes.isEmpty() ? 0 : responseTimes.get(0);
            long maxResponseTime = responseTimes.isEmpty() ? 0 : responseTimes.get(responseTimes.size() - 1);
            long p50 = percentile(responseTimes, 50);
            long p95 = percentile(responseTimes, 95);
            long p99 = percentile(responseTimes, 99);

            // Lưu kết quả
            ConcurrentTestResult result = new ConcurrentTestResult(
                    concurrentRequests,
                    total,
                    successCount.get(),
                    errorCount.get(),
                    timeoutCount.get(),
                    successRate,
                    avgResponseTime,
                    minResponseTime,
                    maxResponseTime,
                    p50,
                    p95,
                    p99,
                    testDuration,
                    completed,
                    errors.size() > 0 ? errors.subList(0, Math.min(5, errors.size())) : Collections.emptyList()
            );
            results.add(result);

            // In kết quả từng mức
            System.out.printf("  ✅ Completed: %d/%d requests%n", total, concurrentRequests);
            System.out.printf("  📊 Success: %d, Errors: %d, Timeouts: %d%n", 
                    successCount.get(), errorCount.get(), timeoutCount.get());
            System.out.printf("  📈 Success Rate: %.2f%%%n", successRate);
            System.out.printf("  ⏱️  Avg Response Time: %.2f ms%n", avgResponseTime);
            System.out.printf("  ⚡ Duration: %.2f seconds%n", testDuration / 1000.0);

            // Nếu success rate quá thấp, dừng test
            if (successRate < 50) {
                System.out.printf("  ⚠️  Success rate < 50%%, stopping test at %d concurrent requests%n", concurrentRequests);
                break;
            }

            // Nghỉ 2 giây giữa các test để server recover
            if (concurrentRequests < concurrentLevels[concurrentLevels.length - 1]) {
                Thread.sleep(2000);
            }
        }

        // In tổng kết
        System.out.println("\n" + "=".repeat(80));
        System.out.println("CONCURRENT CAPACITY TEST RESULTS - SUMMARY");
        System.out.println("=".repeat(80) + "\n");

        System.out.printf("| %-15s | %-10s | %-10s | %-10s | %-10s | %-12s | %-15s |%n",
                "Concurrent", "Total", "Success", "Errors", "Timeouts", "Success %", "Avg Time (ms)");
        System.out.println("|" + "-".repeat(15) + "|" + "-".repeat(10) + "|" + "-".repeat(10) + 
                          "|" + "-".repeat(10) + "|" + "-".repeat(10) + "|" + "-".repeat(12) + "|" + "-".repeat(15) + "|");

        for (ConcurrentTestResult result : results) {
            System.out.printf("| %-15d | %-10d | %-10d | %-10d | %-10d | %-11.2f%% | %-15.2f |%n",
                    result.concurrentRequests, result.total, result.success, result.errors, result.timeouts,
                    result.successRate, result.avgResponseTime);
        }

        System.out.println("\n" + "-".repeat(80));

        // Phân tích kết quả
        System.out.println("\n📊 ANALYSIS:");
        
        ConcurrentTestResult maxSuccess = null;
        ConcurrentTestResult breakingPoint = null;

        for (ConcurrentTestResult result : results) {
            if (result.successRate >= 95) {
                if (maxSuccess == null || result.concurrentRequests > maxSuccess.concurrentRequests) {
                    maxSuccess = result;
                }
            } else if (breakingPoint == null && result.successRate < 95) {
                breakingPoint = result;
            }
        }

        if (maxSuccess != null) {
            System.out.printf("  ✅ Max concurrent with 95%%+ success rate: %d requests%n", maxSuccess.concurrentRequests);
            System.out.printf("     - Success rate: %.2f%%%n", maxSuccess.successRate);
            System.out.printf("     - Avg response time: %.2f ms%n", maxSuccess.avgResponseTime);
            System.out.printf("     - Min/Max response time: %d ms / %d ms%n", maxSuccess.minResponseTime, maxSuccess.maxResponseTime);
            System.out.printf("     - P50 (Median): %d ms, P95: %d ms, P99: %d ms%n", maxSuccess.p50, maxSuccess.p95, maxSuccess.p99);
            System.out.printf("     - Test duration: %.2f seconds%n", maxSuccess.testDuration / 1000.0);
            System.out.printf("     - Completed: %s%n", maxSuccess.completed ? "Yes" : "No (timeout)");
        }

        if (breakingPoint != null) {
            System.out.printf("  ⚠️  Breaking point (success rate < 95%%): %d concurrent requests%n", 
                    breakingPoint.concurrentRequests);
            System.out.printf("     - Success rate: %.2f%%%n", breakingPoint.successRate);
            System.out.printf("     - Errors: %d, Timeouts: %d%n", breakingPoint.errors, breakingPoint.timeouts);
            if (!breakingPoint.sampleErrors.isEmpty()) {
                System.out.printf("     - Sample errors (first %d):%n", breakingPoint.sampleErrors.size());
                for (String error : breakingPoint.sampleErrors) {
                    System.out.printf("       • %s%n", error);
                }
            }
        }

        if (maxSuccess == null) {
            System.out.println("  ⚠️  Warning: Không tìm thấy mức concurrent nào có success rate >= 95%");
        }

        System.out.println("\n💡 RECOMMENDATIONS:");
        if (maxSuccess != null) {
            int recommendedConcurrent = (int) (maxSuccess.concurrentRequests * 0.8);
            System.out.printf("  - Recommended max concurrent: ~%d requests (80%% của %d)%n", 
                    recommendedConcurrent, maxSuccess.concurrentRequests);
            System.out.printf("  - Có thể handle tối đa: ~%d concurrent requests%n", maxSuccess.concurrentRequests);
        } else {
            System.out.println("  - Cần kiểm tra lại cấu hình server hoặc giảm số lượng concurrent test");
        }

        System.out.println("\n" + "=".repeat(80) + "\n");
    }

    /**
     * Inner class để lưu kết quả test concurrent
     */
    private static class ConcurrentTestResult {
        final int concurrentRequests;
        final int total;
        final int success;
        final int errors;
        final int timeouts;
        final double successRate;
        final double avgResponseTime;
        final long minResponseTime;
        final long maxResponseTime;
        final long p50;
        final long p95;
        final long p99;
        final long testDuration;
        final boolean completed;
        final List<String> sampleErrors;

        ConcurrentTestResult(int concurrentRequests, int total, int success, int errors, int timeouts,
                            double successRate, double avgResponseTime, long minResponseTime, long maxResponseTime,
                            long p50, long p95, long p99, long testDuration, boolean completed, List<String> sampleErrors) {
            this.concurrentRequests = concurrentRequests;
            this.total = total;
            this.success = success;
            this.errors = errors;
            this.timeouts = timeouts;
            this.successRate = successRate;
            this.avgResponseTime = avgResponseTime;
            this.minResponseTime = minResponseTime;
            this.maxResponseTime = maxResponseTime;
            this.p50 = p50;
            this.p95 = p95;
            this.p99 = p99;
            this.testDuration = testDuration;
            this.completed = completed;
            this.sampleErrors = sampleErrors;
        }
    }

    /**
     * Calculate percentile from sorted list
     */
    private long percentile(List<Long> sortedList, int percentile) {
        if (sortedList.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil((percentile / 100.0) * sortedList.size()) - 1;
        index = Math.max(0, Math.min(index, sortedList.size() - 1));
        return sortedList.get(index);
    }
}

