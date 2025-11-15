package com.techlab.renderpdf.controller;

import com.techlab.renderpdf.model.PdfGenerationRequest;
import com.techlab.renderpdf.service.PdfGenerationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.atomic.AtomicLong;

/**
 * REST controller for PDF generation
 * Uses virtual threads for concurrent processing
 * Optimized for high-performance concurrent requests
 */
@Slf4j
@RestController
@RequestMapping("/api/pdf")
@RequiredArgsConstructor
public class PdfController {

    private final PdfGenerationService pdfGenerationService;
    
    // Metrics để theo dõi performance
    private static final AtomicLong totalRequests = new AtomicLong(0);
    private static final AtomicLong successfulRequests = new AtomicLong(0);
    private static final AtomicLong failedRequests = new AtomicLong(0);
    private static final AtomicLong totalProcessingTime = new AtomicLong(0);
    private static final AtomicLong maxProcessingTime = new AtomicLong(0);
    private static final AtomicLong minProcessingTime = new AtomicLong(Long.MAX_VALUE);

    /**
     * Generate PDF from DOCX template using PdfConverter
     * Process variables/tables in DOCX first, then convert to PDF using PdfConverter
     * Better performance than LibreOffice with good format preservation
     * Optimized with caching for high concurrent requests
     */
    @PostMapping("/generate")
    public ResponseEntity<byte[]> generatePdf(@Valid @RequestBody PdfGenerationRequest request) {
        totalRequests.incrementAndGet();
        long startTime = System.currentTimeMillis();
        
        try {
            log.debug("Generating PDF for template: {}", request.getTemplateName());
            
            byte[] pdfBytes = pdfGenerationService.generatePdfFromDocxTemplate(request);
            
            long duration = System.currentTimeMillis() - startTime;
            successfulRequests.incrementAndGet();
            totalProcessingTime.addAndGet(duration);
            
            // Update min/max processing time
            updateProcessingTimeStats(duration);
            
            // Log metrics cho monitoring
            if (duration > 5000) { // Log warning nếu > 5 giây
                log.warn("Slow PDF generation: {} ms for template: {}", duration, request.getTemplateName());
            } else {
                log.info("PDF generated in {} ms, size: {} bytes, template: {}", 
                        duration, pdfBytes.length, request.getTemplateName());
            }

            String filename = request.getOutputFilename() != null 
                    ? request.getOutputFilename() 
                    : "generated_" + System.currentTimeMillis() + ".pdf";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDispositionFormData("attachment", filename);
            headers.setContentLength(pdfBytes.length);
            
            // Add performance headers
            headers.set("X-Processing-Time-Ms", String.valueOf(duration));

            return new ResponseEntity<>(pdfBytes, headers, HttpStatus.OK);
            
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            failedRequests.incrementAndGet();
            log.error("Error generating PDF for template: {} after {} ms", 
                    request.getTemplateName(), duration, e);
            
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(("Error: " + e.getMessage()).getBytes());
        }
    }
    
    /**
     * Update processing time statistics
     */
    private void updateProcessingTimeStats(long duration) {
        long currentMax = maxProcessingTime.get();
        while (duration > currentMax && !maxProcessingTime.compareAndSet(currentMax, duration)) {
            currentMax = maxProcessingTime.get();
        }
        
        long currentMin = minProcessingTime.get();
        while (duration < currentMin && !minProcessingTime.compareAndSet(currentMin, duration)) {
            currentMin = minProcessingTime.get();
        }
    }

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("PDF Generation Service is running");
    }
    
    /**
     * Metrics endpoint để theo dõi performance
     * 
     * GET /api/pdf/metrics
     */
    @GetMapping("/metrics")
    public ResponseEntity<MetricsResponse> getMetrics() {
        long total = totalRequests.get();
        long successful = successfulRequests.get();
        long failed = failedRequests.get();
        long totalTime = totalProcessingTime.get();
        long maxTime = maxProcessingTime.get();
        long minTime = minProcessingTime.get() == Long.MAX_VALUE ? 0 : minProcessingTime.get();
        
        double avgTime = total > 0 ? (double) totalTime / total : 0;
        double successRate = total > 0 ? (double) successful / total * 100 : 0;
        
        MetricsResponse metrics = MetricsResponse.builder()
                .totalRequests(total)
                .successfulRequests(successful)
                .failedRequests(failed)
                .successRate(successRate)
                .averageProcessingTimeMs(avgTime)
                .maxProcessingTimeMs(maxTime)
                .minProcessingTimeMs(minTime)
                .build();
        
        return ResponseEntity.ok(metrics);
    }
    
    /**
     * Metrics response DTO
     */
    @lombok.Data
    @lombok.Builder
    private static class MetricsResponse {
        private long totalRequests;
        private long successfulRequests;
        private long failedRequests;
        private double successRate;
        private double averageProcessingTimeMs;
        private long maxProcessingTimeMs;
        private long minProcessingTimeMs;
    }
}

