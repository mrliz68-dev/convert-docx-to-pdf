package com.techlab.renderpdf.controller;

import com.techlab.renderpdf.model.PdfGenerationRequest;
import com.techlab.renderpdf.service.PdfGenerationService;
import com.techlab.renderpdf.service.TemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for template management: upload, preview, list, delete
 */
@Slf4j
@RestController
@RequestMapping("/api/templates")
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateService templateService;

    private final PdfGenerationService pdfGenerationService;

    /**
     * Upload a DOCX template file
     * 
     * POST /api/templates/upload
     * Content-Type: multipart/form-data
     * 
     * @param file The DOCX file to upload
     * @param templateName Optional template name (without extension). If not provided, uses original filename
     * @return Response with template name and success message
     */
    @PostMapping("/upload")
    public ResponseEntity<?> uploadTemplate(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "templateName", required = false) String templateName) {
        try {
            log.info("Uploading template: {}", file.getOriginalFilename());
            
            String savedTemplateName = templateService.uploadTemplate(file, templateName);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Template uploaded successfully");
            response.put("templateName", savedTemplateName);
            response.put("originalFilename", file.getOriginalFilename());
            
            return ResponseEntity.ok(response);
            
        } catch (IllegalArgumentException e) {
            log.error("Invalid file: {}", e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
            
        } catch (Exception e) {
            log.error("Error uploading template", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "Error uploading template: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * Preview a template by converting it to PDF
     * 
     * GET /api/templates/{templateName}/preview
     * 
     * @param templateName Template name (without .docx extension)
     * @return PDF file for preview
     */
    @GetMapping("/{templateName}/preview")
    public ResponseEntity<byte[]> previewTemplate(@PathVariable String templateName) {
        try {
            log.info("Previewing template: {}", templateName);
            
            byte[] pdfBytes = templateService.previewTemplate(templateName);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDispositionFormData("inline", templateName + "_preview.pdf");
            headers.setContentLength(pdfBytes.length);
            
            return new ResponseEntity<>(pdfBytes, headers, HttpStatus.OK);
            
        } catch (java.io.FileNotFoundException e) {
            log.error("Template not found: {}", templateName);
            return ResponseEntity.notFound().build();
            
        } catch (Exception e) {
            log.error("Error previewing template: {}", templateName, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(("Error: " + e.getMessage()).getBytes());
        }
    }

    /**
     * Preview a template by converting it to PDF
     * 
     * GET /api/templates/{templateName}/preview
     * 
     * @param templateName Template name (without .docx extension)
     * @return PDF file for preview
     */
    @GetMapping("/{templateName}/preview-v2")
    public ResponseEntity<byte[]> previewTemplateV2(@PathVariable String templateName) {
        try {
            log.info("Previewing template: {}", templateName);
            
            byte[] pdfBytes = pdfGenerationService.generatePdfFromDocxTemplate(new PdfGenerationRequest(templateName, null, null, null));
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDispositionFormData("inline", templateName + "_preview.pdf");
            headers.setContentLength(pdfBytes.length);
            
            return new ResponseEntity<>(pdfBytes, headers, HttpStatus.OK);
            
        } catch (java.io.FileNotFoundException e) {
            log.error("Template not found: {}", templateName);
            return ResponseEntity.notFound().build();
            
        } catch (Exception e) {
            log.error("Error previewing template: {}", templateName, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(("Error: " + e.getMessage()).getBytes());
        }
    }

    /**
     * List all available templates
     * 
     * GET /api/templates
     * 
     * @return List of template names
     */
    @GetMapping
    public ResponseEntity<?> listTemplates() {
        try {
            List<String> templates = templateService.listTemplates();
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("templates", templates);
            response.put("count", templates.size());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error listing templates", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "Error listing templates: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * Extract all param fields (placeholders) from template
     * 
     * GET /api/templates/{templateName}/params
     * 
     * @param templateName Template name (without .docx extension)
     * @return JSON containing all variables and placeholders found in template
     */
    @GetMapping("/{templateName}/params")
    public ResponseEntity<?> extractTemplateParams(@PathVariable String templateName) {
        try {
            log.info("Extracting params from template: {}", templateName);
            
            Map<String, Object> params = templateService.extractParamsFromTemplate(templateName);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", params);
            
            return ResponseEntity.ok(response);
            
        } catch (java.io.FileNotFoundException e) {
            log.error("Template not found: {}", templateName);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "Template not found: " + templateName);
            return ResponseEntity.notFound().build();
            
        } catch (Exception e) {
            log.error("Error extracting params from template: {}", templateName, e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "Error extracting params: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * Delete a template
     * 
     * DELETE /api/templates/{templateName}
     * 
     * @param templateName Template name (without .docx extension)
     * @return Success or error message
     */
    @DeleteMapping("/{templateName}")
    public ResponseEntity<?> deleteTemplate(@PathVariable String templateName) {
        try {
            boolean deleted = templateService.deleteTemplate(templateName);
            
            Map<String, Object> response = new HashMap<>();
            if (deleted) {
                response.put("success", true);
                response.put("message", "Template deleted successfully");
                return ResponseEntity.ok(response);
            } else {
                response.put("success", false);
                response.put("message", "Template not found: " + templateName);
                return ResponseEntity.notFound().build();
            }
            
        } catch (Exception e) {
            log.error("Error deleting template: {}", templateName, e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "Error deleting template: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }
}

