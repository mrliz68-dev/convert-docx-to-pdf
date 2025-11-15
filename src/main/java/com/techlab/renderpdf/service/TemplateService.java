package com.techlab.renderpdf.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import fr.opensagres.poi.xwpf.converter.pdf.PdfConverter;
import fr.opensagres.poi.xwpf.converter.pdf.PdfOptions;
import fr.opensagres.poi.xwpf.converter.core.XWPFConverterException;

import com.lowagie.text.Font;
import com.lowagie.text.pdf.BaseFont;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for managing templates: upload, list, and preview
 */
@Slf4j
@Service
public class TemplateService {

    @Value("${pdf.generation.template-dir:./templates}")
    private String templateDir;

    @Value("${pdf.generation.font-path:./fonts/times.ttf}")
    private String fontPath;

    /**
     * Upload template file (DOCX) to templates directory
     * 
     * @param file The DOCX file to upload
     * @param templateName Optional template name (without extension). If not provided, uses original filename
     * @return The saved template name (without .docx extension)
     * @throws IOException If file operation fails
     */
    public String uploadTemplate(MultipartFile file, String templateName) throws IOException {
        // Validate file
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File cannot be empty");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".docx")) {
            throw new IllegalArgumentException("Only .docx files are allowed");
        }

        // Validate DOCX file by trying to read it
        try (InputStream inputStream = file.getInputStream()) {
            XWPFDocument document = new XWPFDocument(inputStream);
            document.close();
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid DOCX file: " + e.getMessage());
        }

        // Determine template name
        String finalTemplateName;
        if (templateName != null && !templateName.trim().isEmpty()) {
            finalTemplateName = templateName.trim().replaceAll("[^a-zA-Z0-9_-]", "_");
        } else {
            // Remove .docx extension from original filename
            finalTemplateName = originalFilename.substring(0, originalFilename.length() - 5)
                    .replaceAll("[^a-zA-Z0-9_-]", "_");
        }

        // Ensure template directory exists
        Path templateDirectory = Paths.get(templateDir);
        if (!Files.exists(templateDirectory)) {
            Files.createDirectories(templateDirectory);
            log.info("Created template directory: {}", templateDirectory);
        }

        // Save file
        Path targetPath = templateDirectory.resolve(finalTemplateName + ".docx");
        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }

        log.info("Template uploaded successfully: {} -> {}", originalFilename, targetPath);
        return finalTemplateName;
    }

    /**
     * Preview template by converting to PDF without filling variables
     * 
     * @param templateName Template name (without .docx extension)
     * @return PDF bytes
     * @throws IOException If file operation fails
     * @throws XWPFConverterException If PDF conversion fails
     */
    public byte[] previewTemplate(String templateName) throws IOException, XWPFConverterException {
        String templatePath = Paths.get(templateDir, templateName + ".docx").toString();
        File templateFile = new File(templatePath);

        if (!templateFile.exists()) {
            throw new FileNotFoundException("Template not found: " + templateName);
        }

        log.info("Previewing template: {}", templatePath);

        // Read DOCX template
        XWPFDocument docxDocument;
        try (FileInputStream fis = new FileInputStream(templateFile)) {
            docxDocument = new XWPFDocument(fis);
        }

        try {
            // Convert DOCX to PDF without filling variables
            ByteArrayOutputStream pdfOutputStream = new ByteArrayOutputStream();

            PdfOptions options = PdfOptions.create();
            options.fontEncoding("UTF-8");

            // Configure font if available
            if (fontPath != null && !fontPath.trim().isEmpty()) {
                File fontFile = new File(fontPath);
                if (fontFile.exists()) {
                    options.fontProvider((familyName, encoding, size, style, color) -> {
                        try {
                            BaseFont baseFont = BaseFont.createFont(fontPath, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
                            return new Font(baseFont, size, style, color);
                        } catch (Exception e) {
                            log.warn("Error loading font, using default font: {}", e.getMessage());
                            return new Font(Font.HELVETICA, size, style, color);
                        }
                    });
                }
            }

            // Convert DOCX to PDF
            PdfConverter.getInstance().convert(docxDocument, pdfOutputStream, options);

            byte[] pdfBytes = pdfOutputStream.toByteArray();
            log.info("Template preview PDF generated: {} bytes", pdfBytes.length);
            return pdfBytes;

        } finally {
            if (docxDocument != null) {
                docxDocument.close();
            }
        }
    }

    /**
     * List all available templates
     * 
     * @return List of template names (without .docx extension)
     */
    public List<String> listTemplates() {
        List<String> templates = new ArrayList<>();
        
        try {
            Path templateDirectory = Paths.get(templateDir);
            if (Files.exists(templateDirectory)) {
                Files.list(templateDirectory)
                    .filter(path -> path.toString().toLowerCase().endsWith(".docx"))
                    .map(path -> {
                        String filename = path.getFileName().toString();
                        return filename.substring(0, filename.length() - 5); // Remove .docx
                    })
                    .sorted()
                    .forEach(templates::add);
            }
        } catch (IOException e) {
            log.error("Error listing templates", e);
        }

        return templates;
    }

    /**
     * Delete a template
     * 
     * @param templateName Template name (without .docx extension)
     * @return true if deleted successfully, false otherwise
     */
    public boolean deleteTemplate(String templateName) {
        try {
            Path templatePath = Paths.get(templateDir, templateName + ".docx");
            if (Files.exists(templatePath)) {
                Files.delete(templatePath);
                log.info("Template deleted: {}", templateName);
                return true;
            }
            return false;
        } catch (IOException e) {
            log.error("Error deleting template: {}", templateName, e);
            return false;
        }
    }

    /**
     * Extract all param fields (placeholders) from template
     * Returns simple variables (${varName}) and table variables (${tableName.field})
     * 
     * @param templateName Template name (without .docx extension)
     * @return Map containing:
     *   - "simpleVariables": List of simple variable names
     *   - "tableVariables": Map of tableName -> List of field names
     *   - "allVariables": List of all variable placeholders found
     * @throws IOException If file operation fails
     */
    public Map<String, Object> extractParamsFromTemplate(String templateName) throws IOException {
        String templatePath = Paths.get(templateDir, templateName + ".docx").toString();
        File templateFile = new File(templatePath);

        if (!templateFile.exists()) {
            throw new FileNotFoundException("Template not found: " + templateName);
        }

        log.info("Extracting params from template: {}", templatePath);

        // Read DOCX template
        XWPFDocument docxDocument;
        try (FileInputStream fis = new FileInputStream(templateFile)) {
            docxDocument = new XWPFDocument(fis);
        }

        try {
            // Sets to store unique variables
            Set<String> simpleVariables = new LinkedHashSet<>();
            Map<String, Set<String>> tableVariablesMap = new LinkedHashMap<>();
            Set<String> allPlaceholders = new LinkedHashSet<>();

            // Pattern for simple variables: ${variableName}
            Pattern simplePattern = Pattern.compile("\\$\\{([^}]+)\\}");
            
            // Pattern for table variables: ${tableName.field}
            Pattern tablePattern = Pattern.compile("\\$\\{([^.]+)\\.([^}]+)\\}");

            // Extract from paragraphs in body
            for (XWPFParagraph paragraph : docxDocument.getParagraphs()) {
                extractFromText(paragraph.getText(), simplePattern, tablePattern, 
                               simpleVariables, tableVariablesMap, allPlaceholders);
            }

            // Extract from tables
            for (XWPFTable table : docxDocument.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        for (XWPFParagraph paragraph : cell.getParagraphs()) {
                            extractFromText(paragraph.getText(), simplePattern, tablePattern,
                                           simpleVariables, tableVariablesMap, allPlaceholders);
                        }
                    }
                }
            }

            // Extract from headers
            if (docxDocument.getHeaderFooterPolicy() != null) {
                if (docxDocument.getHeaderFooterPolicy().getDefaultHeader() != null) {
                    for (XWPFParagraph paragraph : docxDocument.getHeaderFooterPolicy().getDefaultHeader().getParagraphs()) {
                        extractFromText(paragraph.getText(), simplePattern, tablePattern,
                                       simpleVariables, tableVariablesMap, allPlaceholders);
                    }
                }

                // Extract from footers
                if (docxDocument.getHeaderFooterPolicy().getDefaultFooter() != null) {
                    for (XWPFParagraph paragraph : docxDocument.getHeaderFooterPolicy().getDefaultFooter().getParagraphs()) {
                        extractFromText(paragraph.getText(), simplePattern, tablePattern,
                                       simpleVariables, tableVariablesMap, allPlaceholders);
                    }
                }
            }

            // Filter out simple variables that are actually table names
            // (if ${var} exists but also ${var.field} exists, then var is a table, not a simple variable)
            Set<String> filteredSimpleVariables = new LinkedHashSet<>();
            for (String var : simpleVariables) {
                if (!tableVariablesMap.containsKey(var)) {
                    filteredSimpleVariables.add(var);
                }
            }

            // Build result
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("templateName", templateName);
            result.put("simpleVariables", new ArrayList<>(filteredSimpleVariables));
            
            // Convert table variables map to list of objects for better JSON structure
            List<Map<String, Object>> tableVariablesList = new ArrayList<>();
            for (Map.Entry<String, Set<String>> entry : tableVariablesMap.entrySet()) {
                Map<String, Object> tableInfo = new LinkedHashMap<>();
                tableInfo.put("tableName", entry.getKey());
                tableInfo.put("fields", new ArrayList<>(entry.getValue()));
                tableVariablesList.add(tableInfo);
            }
            result.put("tableVariables", tableVariablesList);
            result.put("allPlaceholders", new ArrayList<>(allPlaceholders));
            result.put("summary", Map.of(
                "simpleVariableCount", filteredSimpleVariables.size(),
                "tableCount", tableVariablesMap.size(),
                "totalPlaceholderCount", allPlaceholders.size()
            ));

            log.info("Extracted {} simple variables, {} tables from template", 
                    filteredSimpleVariables.size(), tableVariablesMap.size());

            return result;

        } finally {
            if (docxDocument != null) {
                docxDocument.close();
            }
        }
    }

    /**
     * Extract variables from text using patterns
     */
    private void extractFromText(String text, 
                                Pattern simplePattern, 
                                Pattern tablePattern,
                                Set<String> simpleVariables,
                                Map<String, Set<String>> tableVariablesMap,
                                Set<String> allPlaceholders) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }

        // Find table variables first (they have priority)
        Matcher tableMatcher = tablePattern.matcher(text);
        while (tableMatcher.find()) {
            String tableName = tableMatcher.group(1);
            String fieldName = tableMatcher.group(2);
            String placeholder = "${" + tableName + "." + fieldName + "}";
            
            allPlaceholders.add(placeholder);
            tableVariablesMap.computeIfAbsent(tableName, k -> new LinkedHashSet<>()).add(fieldName);
        }

        // Find simple variables (but exclude those that are table names)
        Matcher simpleMatcher = simplePattern.matcher(text);
        while (simpleMatcher.find()) {
            String varName = simpleMatcher.group(1);
            String placeholder = "${" + varName + "}";
            
            // Only add if it's not already in table variables (to avoid duplicates)
            if (!tableVariablesMap.containsKey(varName) && !allPlaceholders.contains(placeholder)) {
                // Check if this is actually a table variable by looking for ${varName. pattern
                if (!text.contains("${" + varName + ".")) {
                    simpleVariables.add(varName);
                    allPlaceholders.add(placeholder);
                }
            }
        }
    }
}

