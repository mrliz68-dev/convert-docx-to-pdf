package com.techlab.renderpdf.service;

import com.techlab.renderpdf.model.PdfGenerationRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import fr.opensagres.poi.xwpf.converter.core.XWPFConverterException;
import fr.opensagres.poi.xwpf.converter.pdf.PdfConverter;
import fr.opensagres.poi.xwpf.converter.pdf.PdfOptions;

import com.lowagie.text.Font;
import com.lowagie.text.pdf.BaseFont;

import java.io.*;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSpacing;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STLineSpacingRule;

/**
 * Version 4: Điền thông tin từ request body vào file DOCX, sau đó convert sang
 * PDF bằng PdfConverter
 * 
 * Quy trình:
 * 1. Đọc file DOCX template
 * 2. Điền thông tin từ request body (variables) vào DOCX
 * 3. Sử dụng PdfConverter.getInstance().convert() để convert DOCX sang PDF
 */
@Slf4j
@Service
public class PdfGenerationService {

    @Value("${pdf.generation.template-dir:./templates}")
    private String templateDir;

    @Value("${pdf.generation.font-path:./fonts/times.ttf}")
    private String fontPath;

    private static final double DEFAULT_LINE_SPACING = 1.5d;
    
    // Font cache để tránh reload font mỗi request
    private static final Map<String, BaseFont> fontCache = new ConcurrentHashMap<>();
    private static final Object fontCacheLock = new Object();
    
    // Template byte cache - cache template file bytes
    private static final Map<String, byte[]> templateBytesCache = new ConcurrentHashMap<>();
    private static final Map<String, Long> templateFileModifiedCache = new ConcurrentHashMap<>();

    /**
     * Generate PDF from DOCX template
     * Điền thông tin từ request vào DOCX, sau đó convert sang PDF
     * Tối ưu: Cache template bytes để tránh đọc từ disk mỗi request
     */
    public byte[] generatePdfFromDocxTemplate(PdfGenerationRequest request) throws IOException, XWPFConverterException {
        String templatePath = Paths.get(templateDir, request.getTemplateName() + ".docx").toString();
        File templateFile = new File(templatePath);

        log.debug("Đang xử lý DOCX template: {}", templatePath);

        // 1. Đọc DOCX template với caching
        XWPFDocument docxDocument = loadTemplateWithCache(templateFile, request.getTemplateName());

        try {
            // 2. Điền thông tin từ request body vào DOCX
            if (request.getVariables() != null && !request.getVariables().isEmpty()) {
                log.debug("Đang điền {} biến vào DOCX", request.getVariables().size());
                fillVariablesIntoDocx(docxDocument, request.getVariables());
            }

            normalizeLineSpacing(docxDocument);

            log.debug("Đã điền xong thông tin, đang convert sang PDF bằng PdfConverter");

            // 3. Convert DOCX sang PDF bằng PdfConverter
            // Tối ưu: Dùng initial size ước lượng để giảm memory reallocation
            int estimatedSize = (int) (templateFile.length() * 1.2); // Estimate PDF ~20% larger than DOCX
            ByteArrayOutputStream pdfOutputStream = new ByteArrayOutputStream(Math.max(estimatedSize, 8192));

            try {
                PdfOptions options = PdfOptions.create();
                options.fontEncoding("UTF-8");

                // Cấu hình font với caching để tránh reload font mỗi request
                if (fontPath != null && !fontPath.trim().isEmpty()) {
                    options.fontProvider((familyName, encoding, size, style, color) -> {
                        try {
                            BaseFont baseFont = getCachedFont(fontPath);
                            return new Font(baseFont, size, style, color);
                        } catch (Exception e) {
                            log.warn("Lỗi khi load font, sử dụng font mặc định: {}", e.getMessage());
                            return new Font(Font.HELVETICA, size, style, color);
                        }
                    });
                }

                // Convert DOCX to PDF
                PdfConverter.getInstance().convert(docxDocument, pdfOutputStream, options);

                byte[] pdfBytes = pdfOutputStream.toByteArray();
                log.debug("Đã tạo PDF thành công: {} bytes", pdfBytes.length);
                
                // Tối ưu: Trả về array trước khi close streams
                return pdfBytes;
                
            } finally {
                // Cleanup output stream
                try {
                    pdfOutputStream.close();
                } catch (IOException e) {
                    log.debug("Error closing output stream: {}", e.getMessage());
                }
            }

        } finally {
            // Đóng document - QUAN TRỌNG: giải phóng memory
            if (docxDocument != null) {
                try {
                    docxDocument.close();
                } catch (IOException e) {
                    log.debug("Error closing document: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * Điền biến vào DOCX document
     * Tìm và thay thế các placeholder ${variableName} và ${tableName.field} bằng
     * giá trị từ request
     */
    private void fillVariablesIntoDocx(XWPFDocument document, Map<String, Object> variables) {
        // Xử lý bảng trước (có thể cần duplicate rows)
        for (XWPFTable table : document.getTables()) {
            processTable(table, variables);
        }

        // Điền biến đơn giản trong paragraphs
        for (XWPFParagraph paragraph : document.getParagraphs()) {
            replaceVariablesInParagraph(paragraph, variables);
        }

        // Điền biến trong headers
        if (document.getHeaderFooterPolicy() != null) {
            if (document.getHeaderFooterPolicy().getDefaultHeader() != null) {
                for (XWPFParagraph paragraph : document.getHeaderFooterPolicy().getDefaultHeader().getParagraphs()) {
                    replaceVariablesInParagraph(paragraph, variables);
                }
            }

            // Điền biến trong footers
            if (document.getHeaderFooterPolicy().getDefaultFooter() != null) {
                for (XWPFParagraph paragraph : document.getHeaderFooterPolicy().getDefaultFooter().getParagraphs()) {
                    replaceVariablesInParagraph(paragraph, variables);
                }
            }
        }
    }

    /**
     * Xử lý bảng: tìm hàng template có chứa ${tableName.field} và duplicate theo dữ
     * liệu
     */
    private void processTable(XWPFTable table, Map<String, Object> variables) {
        if (table.getRows().isEmpty() || variables == null) {
            return;
        }

        // Tìm hàng template chứa placeholder dạng ${tableName.field}
        String tableName = null;
        List<Map<String, Object>> tableData = null;
        int templateRowIndex = -1;

        // Kiểm tra từ hàng 1 trở đi (hàng 0 thường là header)
        int startRow = table.getRows().size() > 1 ? 1 : 0;

        for (int rowIndex = startRow; rowIndex < table.getRows().size(); rowIndex++) {
            XWPFTableRow row = table.getRow(rowIndex);
            String rowText = getTableRowText(row);

            // Tìm pattern ${tableName.field}
            Pattern pattern = Pattern.compile("\\$\\{([^.]+)\\.([^}]+)\\}");
            Matcher matcher = pattern.matcher(rowText);

            Set<String> foundTableNames = new HashSet<>();
            while (matcher.find()) {
                foundTableNames.add(matcher.group(1));
            }

            // Kiểm tra xem có table nào trong variables không
            for (String name : foundTableNames) {
                Object value = variables.get(name);
                if (value instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> rows = (List<Map<String, Object>>) value;
                    if (!rows.isEmpty() && rows.get(0) instanceof Map) {
                        tableName = name;
                        tableData = rows;
                        templateRowIndex = rowIndex;
                        log.info("Tìm thấy bảng '{}' với {} hàng dữ liệu tại hàng template index {}",
                                name, rows.size(), rowIndex);
                        break;
                    }
                }
            }

            if (tableName != null) {
                break;
            }
        }

        // Nếu tìm thấy bảng động, duplicate rows
        if (tableName != null && tableData != null && templateRowIndex >= 0) {
            duplicateTableRows(table, tableName, tableData, variables, templateRowIndex);
        } else {
            // Chỉ thay thế biến đơn giản trong bảng
            replaceSimpleVariablesInTable(table, variables);
        }
    }

    /**
     * Duplicate hàng trong bảng dựa trên dữ liệu
     */
    private void duplicateTableRows(XWPFTable table, String tableName,
            List<Map<String, Object>> tableData,
            Map<String, Object> variables,
            int templateRowIndex) {
        if (table.getRows().isEmpty() || tableData.isEmpty() || templateRowIndex < 0) {
            return;
        }

        // Lấy hàng template và LƯU TẤT CẢ THÔNG TIN TRƯỚC KHI XÓA
        XWPFTableRow templateRow = table.getRow(templateRowIndex);
        int numCells = templateRow.getTableCells().size();

        // Lưu thông tin về từng cell trong template row (text + formatting)
        // QUAN TRỌNG: Lưu các giá trị formatting thay vì reference để tránh disconnect
        List<CellTemplateInfo> templateCellInfos = new ArrayList<>();
        for (int i = 0; i < numCells; i++) {
            XWPFTableCell cell = templateRow.getCell(i);
            String cellText = cell.getText();
            
            // Lưu formatting từ paragraph đầu tiên (nếu có) - lưu giá trị, không phải reference
            CellTemplateInfo cellInfo = new CellTemplateInfo(cellText != null ? cellText : "");
            
            if (cell.getParagraphs() != null && !cell.getParagraphs().isEmpty()) {
                XWPFParagraph sourcePara = cell.getParagraphs().get(0);
                // Lưu các giá trị formatting
                try {
                    cellInfo.alignment = sourcePara.getAlignment();
                    cellInfo.spacingBefore = sourcePara.getSpacingBefore();
                    cellInfo.spacingAfter = sourcePara.getSpacingAfter();
                    cellInfo.lineSpacing = resolveParagraphLineSpacing(sourcePara);
                    cellInfo.indentationLeft = sourcePara.getIndentationLeft();
                    cellInfo.indentationRight = sourcePara.getIndentationRight();
                    cellInfo.indentationFirstLine = sourcePara.getIndentationFirstLine();
                } catch (Exception e) {
                    log.warn("Không thể lưu formatting từ template cell {}: {}", i, e.getMessage());
                }
            }
            
            templateCellInfos.add(cellInfo);
        }

        // Xác định có header không (hàng 0)
        boolean hasHeader = (templateRowIndex > 0);

        // Xóa các hàng data (giữ lại header nếu có)
        // QUAN TRỌNG: Sau khi xóa, không thể truy cập templateRow nữa
        int rowCount = table.getRows().size();
        int removeFromIndex = hasHeader ? templateRowIndex : 0;

        for (int i = rowCount - 1; i >= removeFromIndex; i--) {
            table.removeRow(i);
        }

        // Duplicate hàng cho mỗi item trong tableData
        int insertPosition = hasHeader ? 1 : 0;

        for (int i = 0; i < tableData.size(); i++) {
            XWPFTableRow newRow;

            try {
                newRow = table.insertNewTableRow(insertPosition + i);
            } catch (Exception e) {
                log.warn("Không thể insert hàng tại vị trí {}, tạo ở cuối: {}", insertPosition + i, e.getMessage());
                newRow = table.createRow();
            }

            // Đảm bảo số lượng cell giống template
            while (newRow.getTableCells().size() < numCells) {
                newRow.createCell();
            }
            while (newRow.getTableCells().size() > numCells) {
                newRow.removeCell(newRow.getTableCells().size() - 1);
            }

            // Copy text content và formatting từ template row (sử dụng thông tin đã lưu)
            for (int j = 0; j < numCells; j++) {
                XWPFTableCell cell = newRow.getCell(j);
                // Xóa paragraphs cũ
                for (int k = cell.getParagraphs().size() - 1; k >= 0; k--) {
                    cell.removeParagraph(k);
                }
                
                // Lấy thông tin đã lưu từ template
                if (j < templateCellInfos.size()) {
                    CellTemplateInfo cellInfo = templateCellInfos.get(j);
                    
                    // Tạo paragraph mới
                    XWPFParagraph newPara = cell.addParagraph();
                    
                    // Apply formatting đã lưu
                    try {
                        if (cellInfo.alignment != null) {
                            newPara.setAlignment(cellInfo.alignment);
                        }
                        if (cellInfo.spacingBefore > 0) {
                            newPara.setSpacingBefore(cellInfo.spacingBefore);
                        }
                        if (cellInfo.spacingAfter > 0) {
                            newPara.setSpacingAfter(cellInfo.spacingAfter);
                        }
                        if (cellInfo.lineSpacing > 0) {
                            newPara.setSpacingBetween(cellInfo.lineSpacing, LineSpacingRule.AUTO);
                        }
                        if (cellInfo.indentationLeft > 0) {
                            newPara.setIndentationLeft(cellInfo.indentationLeft);
                        }
                        if (cellInfo.indentationRight > 0) {
                            newPara.setIndentationRight(cellInfo.indentationRight);
                        }
                        if (cellInfo.indentationFirstLine > 0) {
                            newPara.setIndentationFirstLine(cellInfo.indentationFirstLine);
                        }
                        applyLineSpacingToParagraph(newPara, cellInfo.lineSpacing);
                    } catch (Exception e) {
                        log.warn("Không thể apply formatting cho cell {}: {}", j, e.getMessage());
                    }
                    
                    // Copy text từ template
                    if (cellInfo.cellText != null && !cellInfo.cellText.isEmpty()) {
                        XWPFRun run = newPara.createRun();
                        run.setText(cellInfo.cellText);
                    }
                }
            }

            // Điền dữ liệu vào hàng mới
            Map<String, Object> rowData = tableData.get(i);
            fillTableRowData(newRow, tableName, rowData, variables);
        }

        log.info("Đã duplicate {} hàng cho bảng '{}'", tableData.size(), tableName);
    }

    /**
     * Điền dữ liệu vào hàng bảng
     */
    private void fillTableRowData(XWPFTableRow row, String tableName,
            Map<String, Object> rowData,
            Map<String, Object> variables) {
        for (int cellIndex = 0; cellIndex < row.getTableCells().size(); cellIndex++) {
            XWPFTableCell cell = row.getTableCells().get(cellIndex);

            // Lấy text từ cell (có thể chứa ${tableName.field})
            String cellText = cell.getText();
            if (cellText == null || cellText.trim().isEmpty()) {
                continue;
            }

            // Thay thế ${tableName.field} bằng giá trị từ rowData
            String processedText = replaceTableVariables(cellText, tableName, rowData);

            // Thay thế các biến đơn giản khác
            processedText = replaceSimpleVariables(processedText, variables);

            // Cập nhật cell nếu có thay đổi
            if (!processedText.equals(cellText)) {
                // Preserve formatting từ paragraph hiện tại (nếu có)
                XWPFParagraph existingPara = null;
                if (cell.getParagraphs() != null && !cell.getParagraphs().isEmpty()) {
                    existingPara = cell.getParagraphs().get(0);
                }

                // Xóa paragraphs cũ
                for (int i = cell.getParagraphs().size() - 1; i >= 0; i--) {
                    cell.removeParagraph(i);
                }

                // Thêm paragraph mới với text đã xử lý
                XWPFParagraph para = cell.addParagraph();
                
                // Copy formatting từ paragraph cũ nếu có
                if (existingPara != null) {
                    copyParagraphFormatting(existingPara, para);
                }
                
                XWPFRun run = para.createRun();
                run.setText(processedText);
            }
        }
    }

    /**
     * Thay thế biến bảng ${tableName.field} trong text
     */
    private String replaceTableVariables(String text, String tableName, Map<String, Object> rowData) {
        if (text == null || tableName == null || rowData == null) {
            return text;
        }

        String result = text;
        Pattern pattern = Pattern.compile("\\$\\{" + Pattern.quote(tableName) + "\\.([^}]+)\\}");
        Matcher matcher = pattern.matcher(text);

        while (matcher.find()) {
            String field = matcher.group(1);
            String placeholder = "${" + tableName + "." + field + "}";
            Object value = rowData.get(field);
            result = result.replace(placeholder, value != null ? value.toString() : "");
        }

        return result;
    }

    /**
     * Thay thế biến đơn giản ${variableName} trong text
     */
    private String replaceSimpleVariables(String text, Map<String, Object> variables) {
        if (text == null || variables == null) {
            return text;
        }

        String result = text;

        // Chỉ thay thế biến đơn giản, bỏ qua biến bảng
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            // Bỏ qua nếu là List (bảng)
            if (value instanceof List) {
                continue;
            }

            // Bỏ qua nếu text chứa pattern ${key. (biến bảng)
            if (text.contains("${" + key + ".")) {
                continue;
            }

            String placeholder = "${" + key + "}";
            if (result.contains(placeholder)) {
                String replacement = value != null ? value.toString() : "";
                result = result.replace(placeholder, replacement);
            }
        }

        return result;
    }

    /**
     * Lấy text từ một hàng bảng
     */
    private String getTableRowText(XWPFTableRow row) {
        StringBuilder sb = new StringBuilder();
        for (XWPFTableCell cell : row.getTableCells()) {
            String cellText = cell.getText();
            if (cellText != null) {
                sb.append(cellText).append(" ");
            }
        }
        return sb.toString().trim();
    }

    /**
     * Thay thế biến đơn giản trong bảng (không duplicate)
     */
    private void replaceSimpleVariablesInTable(XWPFTable table, Map<String, Object> variables) {
        for (XWPFTableRow row : table.getRows()) {
            for (XWPFTableCell cell : row.getTableCells()) {
                for (XWPFParagraph paragraph : cell.getParagraphs()) {
                    replaceVariablesInParagraph(paragraph, variables);
                }
            }
        }
    }

    /**
     * Thay thế biến trong một paragraph
     * Tìm các placeholder ${variableName} và thay thế bằng giá trị tương ứng
     * Preserve formatting của paragraph (line spacing, spacing before/after)
     */
    private void replaceVariablesInParagraph(XWPFParagraph paragraph, Map<String, Object> variables) {
        String paragraphText = paragraph.getText();
        if (paragraphText == null || paragraphText.trim().isEmpty()) {
            return;
        }

        // Thay thế biến đơn giản
        String processedText = replaceSimpleVariables(paragraphText, variables);

        // Nếu có thay đổi, cập nhật lại paragraph
        if (!processedText.equals(paragraphText)) {
            // Preserve formatting trước khi thay đổi
            ParagraphAlignment alignment = paragraph.getAlignment();
            int spacingBefore = paragraph.getSpacingBefore();
            int spacingAfter = paragraph.getSpacingAfter();
            double lineSpacing = resolveParagraphLineSpacing(paragraph);
            int indentationLeft = paragraph.getIndentationLeft();
            int indentationRight = paragraph.getIndentationRight();
            int indentationFirstLine = paragraph.getIndentationFirstLine();

            // Xóa tất cả runs hiện tại
            for (int i = paragraph.getRuns().size() - 1; i >= 0; i--) {
                paragraph.removeRun(i);
            }

            // Thêm text mới
            if (processedText != null && !processedText.isEmpty()) {
                XWPFRun run = paragraph.createRun();
                run.setText(processedText);
            }

            // Restore formatting
            try {
                if (alignment != null) {
                    paragraph.setAlignment(alignment);
                }
                if (spacingBefore > 0) {
                    paragraph.setSpacingBefore(spacingBefore);
                }
                if (spacingAfter > 0) {
                    paragraph.setSpacingAfter(spacingAfter);
                }
                applyLineSpacingToParagraph(paragraph, lineSpacing);
                if (indentationLeft > 0) {
                    paragraph.setIndentationLeft(indentationLeft);
                }
                if (indentationRight > 0) {
                    paragraph.setIndentationRight(indentationRight);
                }
                if (indentationFirstLine > 0) {
                    paragraph.setIndentationFirstLine(indentationFirstLine);
                }
            } catch (Exception e) {
                log.warn("Không thể restore formatting cho paragraph: {}", e.getMessage());
            }
        }
    }

    /**
     * Đảm bảo paragraph có line spacing rõ ràng để PdfConverter hiểu đúng
     * Sử dụng AUTO spacing rule với giá trị multiplier chính xác
     * 
     * @param paragraph Paragraph cần set spacing
     * @param lineSpacing Line spacing multiplier (1.0 = single, 1.5 = 1.5x, etc.)
     * @param inTable true nếu paragraph trong table, false nếu paragraph thường
     */
    private void applyLineSpacingToParagraph(XWPFParagraph paragraph, double lineSpacing, boolean inTable) {
        if (paragraph == null) {
            return;
        }

        // Kiểm tra paragraph có text hay không (có runs với text)
        boolean hasText = false;
        String paragraphText = paragraph.getText();
        if (paragraphText != null && !paragraphText.trim().isEmpty()) {
            hasText = true;
        }
        
        // Kiểm tra paragraph có runs với text thực sự không (loại trừ runs chỉ có whitespace)
        if (!hasText && paragraph.getRuns() != null) {
            for (XWPFRun run : paragraph.getRuns()) {
                String runText = run.getText(0); // getText(index) - 0 là index đầu tiên
                if (runText != null && !runText.trim().isEmpty()) {
                    hasText = true;
                    break;
                }
            }
        }

        // Nếu chưa có line spacing, dùng default
        double spacingValue = lineSpacing > 0 ? lineSpacing : DEFAULT_LINE_SPACING;

        // Lấy font size từ run đầu tiên (nếu có) để tính spacing chính xác
        int fontSize = 12; // default font size
        if (paragraph.getRuns() != null && !paragraph.getRuns().isEmpty()) {
            XWPFRun firstRun = paragraph.getRuns().get(0);
            try {
                // Thử lấy font size
                Double fontSizeDouble = firstRun.getFontSizeAsDouble();
                if (fontSizeDouble != null && fontSizeDouble > 0) {
                    fontSize = fontSizeDouble.intValue();
                }
            } catch (Exception e) {
                log.debug("Không thể lấy font size, dùng default 12: {}", e.getMessage());
            }
        }

        try {
            // Set ở API level với AUTO
            paragraph.setSpacingBetween(spacingValue, LineSpacingRule.AUTO);
        } catch (Exception e) {
            log.debug("Không thể set spacingBetween: {}", e.getMessage());
        }

        try {
            // QUAN TRỌNG: Set ở XML level để PdfConverter nhận diện
            CTPPr pPr = paragraph.getCTP().isSetPPr() ? paragraph.getCTP().getPPr() : paragraph.getCTP().addNewPPr();
            CTSpacing spacing = pPr.isSetSpacing() ? pPr.getSpacing() : pPr.addNewSpacing();
            
            // Sử dụng AUTO rule với multiplier - PdfConverter có thể xử lý tốt hơn
            // AUTO rule: line spacing = multiplier * font size (tính bằng twips)
            // Line spacing 1.5 = 1.5 * 240 = 360 twips
            BigInteger lineSpacingTwips = BigInteger.valueOf(Math.round(spacingValue * 240));
            spacing.setLine(lineSpacingTwips);
            spacing.setLineRule(STLineSpacingRule.AUTO);
            
            log.debug("Set line spacing AUTO: {} twips (multiplier {}, fontSize={}, hasText={}, inTable={})", 
                    lineSpacingTwips, spacingValue, fontSize, hasText, inTable);
            
            // Đảm bảo spacing element được set vào paragraph properties
            if (!pPr.isSetSpacing()) {
                pPr.setSpacing(spacing);
            }
            
            // QUAN TRỌNG: Xử lý spacingAfter và spacingBefore
            // - Paragraphs trống (không có text): KHÔNG set spacingAfter để tránh dãn quá nhiều
            // - Paragraphs có text: chỉ set spacingAfter nhẹ cho paragraphs ngoài bảng
            if (!hasText) {
                // Paragraph trống: không set spacingAfter và spacingBefore
                // Chỉ unset nếu đã được set trước đó
                try {
                    if (spacing.isSetAfter()) {
                        spacing.unsetAfter();
                    }
                } catch (Exception e) {
                    // Ignore nếu không thể unset
                }
                try {
                    if (spacing.isSetBefore()) {
                        spacing.unsetBefore();
                    }
                } catch (Exception e) {
                    // Ignore nếu không thể unset
                }
                log.debug("Paragraph trống: unset spacingAfter và spacingBefore");
            } else {
                // Paragraph có text - thêm spacingAfter để tạo khoảng cách giữa các paragraphs
                // Cách này tạo khoảng cách giữa các dòng text bằng cách tăng khoảng cách giữa paragraphs
                if (!inTable) {
                    // Ngoài bảng: set spacingAfter để tạo khoảng cách giữa các paragraphs
                    // SpacingAfter tạo khoảng cách sau paragraph, làm các dòng text trông dãn nhau hơn
                    // Tính dựa trên font size và line spacing: fontSize * (spacingValue - 1) * 20 (points to twips)
                    double extraSpacingPoints = fontSize * (spacingValue - 0.5);
                    
                    // Kiểm tra xem paragraph đã có spacingAfter chưa
                    int existingSpacingAfter = paragraph.getSpacingAfter();
                    int spacingAfterTwips;
                    
                    if (extraSpacingPoints > 0) {
                        // Có line spacing > 1.0: tính spacingAfter dựa trên extra spacing
                        // HỆ SỐ: 1.5 = 150% (giảm từ 1.0 để tránh dãn quá nhiều)
                        spacingAfterTwips = (int) Math.round(extraSpacingPoints * 20 * 1.5);
                        // Nếu đã có spacingAfter sẵn, lấy giá trị lớn hơn (nhưng không quá lớn)
                        if (existingSpacingAfter > 0) {
                            spacingAfterTwips = Math.max(spacingAfterTwips, (int)(existingSpacingAfter * 1.2));
                        }
                    } else {
                        // SpacingValue = 1.0: set spacingAfter nhẹ
                        // HỆ SỐ: 0.4 = 40% (giảm từ 0.6 để tránh dãn quá nhiều)
                        spacingAfterTwips = (int) Math.round(fontSize * 0.4 * 20);
                        // Nếu đã có spacingAfter sẵn và nhỏ hơn, giữ nguyên
                        if (existingSpacingAfter > 0 && existingSpacingAfter < spacingAfterTwips) {
                            spacingAfterTwips = existingSpacingAfter;
                        }
                    }
                    
                    // Giới hạn spacingAfter tối đa để tránh dãn quá nhiều
                    int maxSpacingAfter = (int) Math.round(fontSize * 2.0 * 20); // Tối đa 2.0x font size
                    spacingAfterTwips = Math.min(spacingAfterTwips, maxSpacingAfter);
                    
                    if (spacingAfterTwips > 0) {
                        spacing.setAfter(BigInteger.valueOf(spacingAfterTwips));
                        paragraph.setSpacingAfter(spacingAfterTwips); // Cũng set ở API level
                    }
                } else {
                    // Trong bảng: set spacingAfter nhẹ hơn để không dãn quá nhiều
                    double extraSpacingPoints = fontSize * (spacingValue - 1.0);
                    
                    // Kiểm tra spacingAfter hiện tại
                    int existingSpacingAfter = paragraph.getSpacingAfter();
                    int spacingAfterTwips;
                    
                    if (extraSpacingPoints > 0) {
                        // HỆ SỐ: 0.3 = 30% (giảm từ 0.5 để tránh dãn quá nhiều trong bảng)
                        spacingAfterTwips = (int) Math.round(extraSpacingPoints * 20 * 0.5);
                        if (existingSpacingAfter > 0) {
                            spacingAfterTwips = Math.max(spacingAfterTwips, (int)(existingSpacingAfter * 1.1));
                        }
                    } else {
                        // HỆ SỐ: 0.2 = 20% (giảm từ 0.3 để tránh dãn quá nhiều trong bảng)
                        spacingAfterTwips = (int) Math.round(fontSize * 0.5 * 20);
                        // Giữ nguyên nếu đã có spacingAfter nhỏ hơn
                        if (existingSpacingAfter > 0 && existingSpacingAfter < spacingAfterTwips) {
                            spacingAfterTwips = existingSpacingAfter;
                        }
                    }
                    
                    // Giới hạn spacingAfter trong bảng (nhỏ hơn ngoài bảng)
                    int maxSpacingAfter = (int) Math.round(fontSize * 0.8 * 20); // Tối đa 0.8x font size
                    spacingAfterTwips = Math.min(spacingAfterTwips, maxSpacingAfter);
                    
                    if (spacingAfterTwips > 0) {
                        spacing.setAfter(BigInteger.valueOf(spacingAfterTwips));
                        paragraph.setSpacingAfter(spacingAfterTwips);
                    } else {
                        // Nếu không set được spacingAfter, đảm bảo không có spacingAfter cũ
                        try {
                            if (spacing.isSetAfter()) {
                                spacing.unsetAfter();
                            }
                        } catch (Exception e) {
                            // Ignore nếu không thể unset
                        }
                    }
                }
                // Không set spacingBefore cho paragraphs có text
                try {
                    if (spacing.isSetBefore()) {
                        spacing.unsetBefore();
                    }
                } catch (Exception e) {
                    // Ignore nếu không thể unset
                }
            }
            
        } catch (Exception e) {
            String errorMsg = e.getMessage();
            if (errorMsg == null || errorMsg.isEmpty()) {
                errorMsg = e.getClass().getSimpleName();
            }
            log.warn("Không thể set CTSpacing: {} - {}", errorMsg, e.getClass().getName());
            log.debug("Stack trace cho CTSpacing error:", e);
        }
        
        // Force verify và set lại nếu cần
        try {
            if (paragraph.getSpacingBetween() <= 0) {
                paragraph.setSpacingBetween(spacingValue, LineSpacingRule.AUTO);
            }
        } catch (Exception e) {
            log.debug("Không thể force set spacingBetween: {}", e.getMessage());
        }
        
        // Force unset spacingAfter ở API level cho paragraphs trống
        if (!hasText) {
            try {
                paragraph.setSpacingAfter(0);
                paragraph.setSpacingBefore(0);
            } catch (Exception e) {
                log.debug("Không thể unset spacingAfter/spacingBefore: {}", e.getMessage());
            }
        }
    }

    /**
     * Overload method - mặc định không trong bảng
     */
    private void applyLineSpacingToParagraph(XWPFParagraph paragraph, double lineSpacing) {
        applyLineSpacingToParagraph(paragraph, lineSpacing, false);
    }

    /**
     * Đọc line spacing thực sự của paragraph (kể cả từ CTSpacing)
     */
    private double resolveParagraphLineSpacing(XWPFParagraph paragraph) {
        if (paragraph == null) {
            return 0;
        }

        double spacing = paragraph.getSpacingBetween();
        if (spacing > 0) {
            return spacing;
        }

        try {
            CTPPr pPr = paragraph.getCTP().getPPr();
            if (pPr != null && pPr.isSetSpacing()) {
                CTSpacing spacingElement = pPr.getSpacing();
                if (spacingElement != null && spacingElement.isSetLine()) {
                    Object lineObj = spacingElement.getLine();
                    if (lineObj instanceof BigInteger line && line.doubleValue() > 0) {
                        double resolved = line.doubleValue() / 240d;
                        if (resolved > 0) {
                            return resolved;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Không thể đọc line spacing: {}", e.getMessage());
        }

        return 0;
    }

    /**
     * Chuẩn hóa và preserve line spacing trong document
     * Đảm bảo line spacing được giữ nguyên khi convert sang PDF
     * Force set line spacing cho TẤT CẢ paragraphs để PdfConverter nhận diện đúng
     */
    private void normalizeLineSpacing(XWPFDocument document) {
        log.debug("Bắt đầu normalize line spacing cho toàn bộ document");
        
        int paragraphCount = 0;
        
        // Xử lý paragraphs trong body (KHÔNG trong bảng)
        for (XWPFParagraph paragraph : document.getParagraphs()) {
            preserveParagraphSpacing(paragraph, false); // false = không trong bảng
            paragraphCount++;
        }

        // Xử lý trong tables (TRONG bảng)
        for (XWPFTable table : document.getTables()) {
            for (XWPFTableRow row : table.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) {
                    for (XWPFParagraph paragraph : cell.getParagraphs()) {
                        preserveParagraphSpacing(paragraph, true); // true = trong bảng
                        paragraphCount++;
                    }
                }
            }
        }

        // Xử lý trong headers và footers (KHÔNG trong bảng)
        if (document.getHeaderFooterPolicy() != null) {
            if (document.getHeaderFooterPolicy().getDefaultHeader() != null) {
                for (XWPFParagraph paragraph : document.getHeaderFooterPolicy().getDefaultHeader().getParagraphs()) {
                    preserveParagraphSpacing(paragraph, false); // false = không trong bảng
                    paragraphCount++;
                }
            }
            if (document.getHeaderFooterPolicy().getDefaultFooter() != null) {
                for (XWPFParagraph paragraph : document.getHeaderFooterPolicy().getDefaultFooter().getParagraphs()) {
                    preserveParagraphSpacing(paragraph, false); // false = không trong bảng
                    paragraphCount++;
                }
            }
        }
        
        log.debug("Đã normalize line spacing cho {} paragraphs", paragraphCount);
    }

    /**
     * Copy formatting từ source paragraph sang target paragraph
     */
    private void copyParagraphFormatting(XWPFParagraph source, XWPFParagraph target) {
        if (source == null || target == null) {
            return;
        }

        try {
            // Copy alignment
            if (source.getAlignment() != null) {
                target.setAlignment(source.getAlignment());
            }

            // Copy spacing before
            if (source.getSpacingBefore() > 0) {
                target.setSpacingBefore(source.getSpacingBefore());
            }

            // Copy spacing after
            if (source.getSpacingAfter() > 0) {
                target.setSpacingAfter(source.getSpacingAfter());
            }

            // Copy line spacing
            double lineSpacing = resolveParagraphLineSpacing(source);
            applyLineSpacingToParagraph(target, lineSpacing);

            // Copy indentation
            if (source.getIndentationLeft() > 0) {
                target.setIndentationLeft(source.getIndentationLeft());
            }
            if (source.getIndentationRight() > 0) {
                target.setIndentationRight(source.getIndentationRight());
            }
            if (source.getIndentationFirstLine() > 0) {
                target.setIndentationFirstLine(source.getIndentationFirstLine());
            }
        } catch (Exception e) {
            String errorMsg = e.getMessage();
            if (errorMsg == null || errorMsg.isEmpty()) {
                errorMsg = e.getClass().getSimpleName();
            }
            log.warn("Không thể copy paragraph formatting: {} - {}", errorMsg, e.getClass().getName());
            log.debug("Stack trace cho copy paragraph formatting error:", e);
        }
    }

    /**
     * Preserve line spacing và các spacing properties của paragraph
     * Nếu paragraph chưa có line spacing, sẽ set default line spacing
     * 
     * @param paragraph Paragraph cần preserve spacing
     * @param inTable true nếu paragraph trong table, false nếu paragraph thường
     */
    private void preserveParagraphSpacing(XWPFParagraph paragraph, boolean inTable) {
        if (paragraph == null) {
            return;
        }

        double lineSpacing = resolveParagraphLineSpacing(paragraph);
        
        // Nếu chưa có line spacing rõ ràng, set default
        if (lineSpacing <= 0) {
            lineSpacing = DEFAULT_LINE_SPACING;
        }
        
        applyLineSpacingToParagraph(paragraph, lineSpacing, inTable);
    }

    /**
     * Load template với caching để tối ưu hiệu năng
     * Cache template bytes và kiểm tra file modification time để invalidate cache khi file thay đổi
     * 
     * @param templateFile File template
     * @param templateName Tên template (để làm cache key)
     * @return XWPFDocument từ template
     * @throws IOException Nếu không đọc được file
     */
    private XWPFDocument loadTemplateWithCache(File templateFile, String templateName) throws IOException {
        if (!templateFile.exists()) {
            throw new FileNotFoundException("Template not found: " + templateFile.getAbsolutePath());
        }

        String cacheKey = templateName;
        long currentModifiedTime = templateFile.lastModified();
        
        // Kiểm tra cache và file modification time
        byte[] templateBytes = templateBytesCache.get(cacheKey);
        Long cachedModifiedTime = templateFileModifiedCache.get(cacheKey);
        
        // Invalidate cache nếu file đã thay đổi hoặc chưa có trong cache
        if (templateBytes == null || cachedModifiedTime == null || cachedModifiedTime < currentModifiedTime) {
            log.debug("Loading template from disk: {} (cache miss or file changed)", templateName);
            
            // Đọc template từ disk
            templateBytes = Files.readAllBytes(templateFile.toPath());
            
            // Update cache
            templateBytesCache.put(cacheKey, templateBytes);
            templateFileModifiedCache.put(cacheKey, currentModifiedTime);
            
            // Cleanup cache nếu quá lớn (giữ tối đa 100 templates)
            if (templateBytesCache.size() > 100) {
                // Remove oldest entry (simple strategy - remove first entry)
                String oldestKey = templateBytesCache.keySet().iterator().next();
                templateBytesCache.remove(oldestKey);
                templateFileModifiedCache.remove(oldestKey);
                log.debug("Cache cleanup: removed template {}", oldestKey);
            }
        } else {
            log.debug("Loading template from cache: {}", templateName);
        }
        
        // Tạo XWPFDocument từ cached bytes
        return new XWPFDocument(new ByteArrayInputStream(templateBytes));
    }

    /**
     * Get font với caching để tránh reload font mỗi request
     * Font được cache trong memory để tối ưu hiệu năng
     * 
     * @param fontPath Đường dẫn đến font file
     * @return BaseFont từ cache hoặc load mới
     * @throws Exception Nếu không load được font
     */
    private BaseFont getCachedFont(String fontPath) throws Exception {
        BaseFont cachedFont = fontCache.get(fontPath);
        
        if (cachedFont != null) {
            return cachedFont;
        }
        
        // Synchronized để tránh load font nhiều lần cùng lúc
        synchronized (fontCacheLock) {
            // Double-check sau khi acquire lock
            cachedFont = fontCache.get(fontPath);
            if (cachedFont != null) {
                return cachedFont;
            }
            
            // Load font mới
            log.debug("Loading font from disk: {}", fontPath);
            BaseFont newFont = BaseFont.createFont(fontPath, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            fontCache.put(fontPath, newFont);
            
            // Cleanup font cache nếu quá lớn (giữ tối đa 10 fonts)
            if (fontCache.size() > 10) {
                String oldestKey = fontCache.keySet().iterator().next();
                fontCache.remove(oldestKey);
                log.debug("Font cache cleanup: removed font {}", oldestKey);
            }
            
            return newFont;
        }
    }

    /**
     * Helper class để lưu thông tin về cell template (text + formatting)
     * Sử dụng để tránh XmlValueDisconnectedException khi truy cập templateRow sau khi xóa
     * Lưu các giá trị formatting thay vì reference đến paragraph object
     */
    private static class CellTemplateInfo {
        final String cellText;
        ParagraphAlignment alignment;
        int spacingBefore = 0;
        int spacingAfter = 0;
        double lineSpacing = 0;
        int indentationLeft = 0;
        int indentationRight = 0;
        int indentationFirstLine = 0;

        CellTemplateInfo(String cellText) {
            this.cellText = cellText;
        }
    }
}

