package com.workshopper.service;

import com.workshopper.dto.ActivityBlockDto;
import com.workshopper.dto.ActivitySectionDto;
import com.workshopper.dto.PdfExportRequestDto;
import com.workshopper.dto.WorkshopSessionDto;
import com.workshopper.dto.WorkshopInputDto;
import com.workshopper.dto.LearningGoalPlanDto;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Service
public class PdfExportService {

    private static final Logger log = LoggerFactory.getLogger(PdfExportService.class);

    public PdfExportService() {}

    public byte[] exportToPdf(PdfExportRequestDto request) throws Exception {
        WorkshopSessionDto session = request.session();
        WorkshopInputDto meta = request.meta();
        List<LearningGoalPlanDto> goals = request.goals();

        try (PDDocument document = new PDDocument()) {
            PdfRenderer renderer = new PdfRenderer(document);
            
            // --- 1. Lecture Information ---
            String title = (session.title() != null && !session.title().isBlank()) ? session.title() : "Workshop Session Plan";
            renderer.addText(title, 20, true);
            renderer.addSpace(15);
            
            if (meta != null) {
                renderer.addText("Session Type: " + (meta.sessionType() != null ? meta.sessionType() : "Workshop"), 12, false);
                renderer.addText("Participants: " + (meta.participants() > 0 ? meta.participants() : "Unknown"), 12, false);
                if (meta.studentBackground() != null && !meta.studentBackground().isBlank()) {
                    renderer.addText("Student Background: " + meta.studentBackground(), 12, false);
                }
            }
            renderer.addSpace(10);
            
            if (goals != null && !goals.isEmpty()) {
                renderer.addText("Learning Goals:", 13, true);
                for (LearningGoalPlanDto g : goals) {
                    renderer.addText("• " + g.goal(), 12, false, 15);
                }
                renderer.addSpace(10);
            } else if (session.learningGoal() != null) {
                renderer.addText("Learning Goal:", 13, true);
                renderer.addText(session.learningGoal(), 12, false);
                renderer.addSpace(10);
            }
            if (session.prerequisites() != null && !session.prerequisites().isBlank()) {
                renderer.addText("Prerequisites:", 13, true);
                renderer.addText(session.prerequisites(), 12, false);
                renderer.addSpace(10);
            }
            renderer.addSpace(10);

            // --- 2. Overall Time Table ---
            renderer.addText("Overall Time Table", 16, true);
            renderer.addSpace(10);
            
            // Table Header
            renderer.addTableRow(new String[]{"Phase / Topic", "Duration"}, new float[]{350, 100}, 12, true);
            
            for (ActivityBlockDto block : session.blocks()) {
                String durationCol = block.duration() + " min";
                renderer.addTableRow(new String[]{block.phaseLabel(), durationCol}, new float[]{350, 100}, 12, false);
            }
            renderer.addSpace(20);

            // --- 3. Detailed Session Plan ---
            renderer.addText("Detailed Session Plan", 16, true);
            renderer.addSpace(15);
            
            for (ActivityBlockDto block : session.blocks()) {
                String blockTitle = String.format("%s  |  %d min", 
                        block.phaseLabel(), block.duration());
                        
                renderer.addText(blockTitle, 14, true, getPhaseColor(block.phase()));
                renderer.addSpace(2);
                
                renderer.drawLine(getPhaseColor(block.phase()), 3f);
                renderer.addSpace(12);
                
                if (block.objective() != null && !block.objective().isBlank()) {
                    renderer.addText("Objective: " + block.objective(), 11, false);
                }
                if (block.description() != null && !block.description().isBlank() && !block.description().equals(block.objective())) {
                    renderer.addText(block.description(), 11, false);
                }
                renderer.addSpace(10);
                
                if (block.sections() != null && !block.sections().isEmpty()) {
                    for (ActivitySectionDto section : block.sections()) {
                        String secTitle = section.title() + (section.duration() > 0 ? " (" + section.duration() + " min)" : "");
                        renderer.addText(secTitle, 12, true, 15);
                        renderer.addSpace(5);
                        
                        if (section.methods() != null && !section.methods().isEmpty()) {
                            renderer.addText("Methods: " + String.join(", ", section.methods()), 10, false, 30);
                        }
                        if (section.materials() != null && !section.materials().isEmpty()) {
                            renderer.addText("Materials: " + String.join(", ", section.materials()), 10, false, 30);
                        }
                        
                        if (section.steps() != null && !section.steps().isEmpty()) {
                            renderer.addSpace(5);
                            for (String step : section.steps()) {
                                renderer.addText("• " + step, 11, false, 30);
                            }
                        }
                        renderer.addSpace(10);
                    }
                } else {
                    if (block.methods() != null && !block.methods().isEmpty()) {
                        renderer.addText("Methods & Activities:", 11, true, 15);
                        for (String m : block.methods()) {
                            renderer.addText("• " + m, 11, false, 30);
                        }
                    }
                    if (block.materials() != null && !block.materials().isEmpty()) {
                        renderer.addText("Materials: " + String.join(", ", block.materials()), 10, false, 15);
                    }
                }
                renderer.addSpace(10);
            }

            renderer.drawLine(java.awt.Color.LIGHT_GRAY, 1f);
            renderer.close();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private java.awt.Color getPhaseColor(String phase) {
        if (phase == null) return java.awt.Color.GRAY;
        return switch (phase.toUpperCase()) {
            case "ARRIVE", "INFORM" -> new java.awt.Color(59, 130, 246); // blue-500
            case "ACTIVATE", "PROCESS" -> new java.awt.Color(245, 158, 11); // amber-500
            case "EVALUATE", "LEARNING_CYCLE" -> new java.awt.Color(168, 85, 247); // purple-500
            case "SUMMARY" -> new java.awt.Color(16, 185, 129); // emerald-500
            case "BREAK" -> new java.awt.Color(100, 116, 139); // slate-500
            default -> java.awt.Color.GRAY;
        };
    }

    private static class PdfRenderer {
        private final PDDocument document;
        private PDPage page;
        private PDPageContentStream contentStream;
        private final PDType1Font fontNormal = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        private final PDType1Font fontBold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        
        private final float margin = 50;
        private final float width;
        private float currentY;
        
        public PdfRenderer(PDDocument document) throws IOException {
            this.document = document;
            this.page = new PDPage();
            document.addPage(page);
            this.width = page.getMediaBox().getWidth() - 2 * margin;
            this.currentY = page.getMediaBox().getUpperRightY() - margin;
            this.contentStream = new PDPageContentStream(document, page);
        }

        public void addSpace(float height) throws IOException {
            currentY -= height;
            checkPageBreak(12);
        }

        public void addText(String text, float fontSize, boolean isBold) throws IOException {
            addText(text, fontSize, isBold, 0, java.awt.Color.BLACK);
        }

        public void addText(String text, float fontSize, boolean isBold, float indent) throws IOException {
            addText(text, fontSize, isBold, indent, java.awt.Color.BLACK);
        }

        public void addText(String text, float fontSize, boolean isBold, java.awt.Color color) throws IOException {
            addText(text, fontSize, isBold, 0, color);
        }

        public void addText(String text, float fontSize, boolean isBold, float indent, java.awt.Color color) throws IOException {
            if (text == null || text.isBlank()) return;
            PDType1Font font = isBold ? fontBold : fontNormal;
            
            // Clean unprintable and unsupported chars (PDFBox standard fonts support only WinAnsiEncoding)
            text = text.replaceAll("[^\\x20-\\x7E]", " ");
            
            float effectiveWidth = width - indent;
            List<String> lines = wrapText(text, font, fontSize, effectiveWidth);
            
            for (String line : lines) {
                checkPageBreak(fontSize + 4);
                contentStream.beginText();
                contentStream.setFont(font, fontSize);
                contentStream.setNonStrokingColor(color);
                contentStream.newLineAtOffset(margin + indent, currentY);
                contentStream.showText(line);
                contentStream.endText();
                contentStream.setNonStrokingColor(java.awt.Color.BLACK);
                currentY -= (fontSize + 4);
            }
        }

        public void addTableRow(String[] cells, float[] columnWidths, float fontSize, boolean isBold) throws IOException {
            PDType1Font font = isBold ? fontBold : fontNormal;
            checkPageBreak(fontSize + 8);
            
            float x = margin;
            for (int i = 0; i < cells.length; i++) {
                if (cells[i] == null) continue;
                String text = cells[i].replaceAll("[^\\x20-\\x7E]", " ");
                if (text.length() > 40) {
                    text = text.substring(0, 37) + "..."; // prevent overlapping in simple table
                }
                
                contentStream.beginText();
                contentStream.setFont(font, fontSize);
                contentStream.newLineAtOffset(x, currentY);
                contentStream.showText(text);
                contentStream.endText();
                
                if (i < columnWidths.length) {
                    x += columnWidths[i];
                }
            }
            currentY -= (fontSize + 8);
            
            // Draw a subtle line under table row
            contentStream.setLineWidth(0.5f);
            contentStream.setStrokingColor(200f/255f, 200f/255f, 200f/255f);
            contentStream.moveTo(margin, currentY + 12);
            contentStream.lineTo(margin + width, currentY + 12);
            contentStream.stroke();
            contentStream.setStrokingColor(0f, 0f, 0f); // reset
        }

        public void drawLine(java.awt.Color color, float widthModifier) throws IOException {
            checkPageBreak(10);
            contentStream.setLineWidth(widthModifier);
            contentStream.setStrokingColor(color);
            contentStream.moveTo(margin, currentY);
            contentStream.lineTo(margin + width, currentY);
            contentStream.stroke();
            contentStream.setStrokingColor(0f, 0f, 0f); // reset
        }

        private void checkPageBreak(float requiredSpace) throws IOException {
            if (currentY - requiredSpace < margin) {
                contentStream.close();
                page = new PDPage();
                document.addPage(page);
                contentStream = new PDPageContentStream(document, page);
                currentY = page.getMediaBox().getUpperRightY() - margin;
            }
        }

        private List<String> wrapText(String text, PDType1Font font, float fontSize, float maxWidth) throws IOException {
            List<String> lines = new ArrayList<>();
            String[] words = text.split(" ");
            StringBuilder line = new StringBuilder();
            
            for (String word : words) {
                if (line.length() == 0) {
                    line.append(word);
                } else {
                    String testLine = line + " " + word;
                    float testWidth = 0;
                    try {
                        testWidth = font.getStringWidth(testLine) / 1000 * fontSize;
                    } catch (IllegalArgumentException e) {
                        // Ignore unsupported characters
                    }
                    if (testWidth > maxWidth) {
                        lines.add(line.toString());
                        line = new StringBuilder(word);
                    } else {
                        line.append(" ").append(word);
                    }
                }
            }
            if (line.length() > 0) {
                lines.add(line.toString());
            }
            return lines;
        }

        public void close() throws IOException {
            if (contentStream != null) {
                contentStream.close();
            }
        }
    }
}
