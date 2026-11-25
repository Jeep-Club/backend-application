package com.jeepclub.backend.platform.export;
import com.jeepclub.backend.shared.export.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;
class ExportRendererTest {
    private final Clock clock=Clock.fixed(Instant.parse("2026-09-28T12:00:00Z"),ZoneOffset.UTC);
    private final DefaultExportRenderer renderer=new DefaultExportRenderer(clock,20000,500,16777216);
    private ExportDocument document(String cell) {
        return new ExportDocument("relatorio","Relatório de teste",List.of("Descrição"),List.of("Situação: Ativo"),sink->sink.accept(ExportRow.of(cell)));
    }
    @Test void csvHasBomEscapingPortugueseAndDownloadHeaders() {
        var file=renderer.render(document("São Paulo; \"aspas\"\nlinha"),ExportFormat.CSV);
        assertThat(new String(file.bytes(),StandardCharsets.UTF_8)).isEqualTo("\uFEFF\"Descrição\"\r\n\"São Paulo; \"\"aspas\"\"\nlinha\"\r\n");
        var response=ExportDownload.response(file);
        assertThat(response.getHeaders().getFirst("Content-Disposition")).contains("attachment", "relatorio-20260928-120000.csv");
        assertThat(response.getHeaders().getFirst("Content-Type")).isEqualTo("text/csv;charset=UTF-8");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    }
    @ParameterizedTest @ValueSource(strings={"=SUM(A1)","+1","-1","@cmd","  =cmd","\tcmd","\rcmd","\ncmd"})
    void protectsFormulas(String value) {
        String csv=new String(renderer.render(document(value),ExportFormat.CSV).bytes(),StandardCharsets.UTF_8);
        assertThat(csv).contains("\"'"+value+"\"");
    }
    @Test void emptyDatasetRetainsHeader() {
        var d=new ExportDocument("vazio","Vazio",List.of("Nome"),List.of(),sink->{});
        assertThat(new String(renderer.render(d,ExportFormat.CSV).bytes(),StandardCharsets.UTF_8)).isEqualTo("\uFEFF\"Nome\"\r\n");
    }
    @Test void nullIsEmptyAndBooleanIsPortuguese() {
        assertThat(ExportRow.of(null,true,false).cells()).containsExactly("","Sim","Não");
    }
    @Test void pdfContainsMetadataFiltersAccentsAndPaginatedLongContent() throws Exception {
        var file=renderer.render(document("Informação clínica\n".repeat(130)),ExportFormat.PDF);
        try(var pdf=Loader.loadPDF(file.bytes())) {
            assertThat(pdf.getNumberOfPages()).isGreaterThan(1);
            assertThat(pdf.getDocumentInformation().getTitle()).isEqualTo("Relatório de teste");
            var stripper = new PDFTextStripper();
            String text=stripper.getText(pdf);
            assertThat(text).contains("Descrição", "Situação: Ativo", "28/09/2026 09:00:00", "Página 2", "Informação clínica");
            Set<org.apache.pdfbox.cos.COSBase> imageStreams = new HashSet<>();
            for (int pageNumber = 1; pageNumber <= pdf.getNumberOfPages(); pageNumber++) {
                PDPage page = pdf.getPage(pageNumber - 1);
                List<PDImageXObject> images = pageImages(page);
                assertThat(images).hasSize(1);
                assertThat(images.get(0).getWidth()).isEqualTo(256);
                assertThat(images.get(0).getHeight()).isEqualTo(246);
                imageStreams.add(images.get(0).getCOSObject());
                stripper.setStartPage(pageNumber);
                stripper.setEndPage(pageNumber);
                assertThat(stripper.getText(pdf)).contains("JEEP CLUB | Relatório de teste", "Gerado em 28/09/2026 09:00:00", "Página " + pageNumber);
            }
            assertThat(imageStreams).hasSize(1);
        }
        assertThat(ExportDownload.response(file).getHeaders().getFirst("Content-Type")).isEqualTo("application/pdf");
    }

    @Test void pdfLongTitleWrapsWithoutBreakingInstitutionalSealOrCsv() throws Exception {
        String longTitle = "Relatório administrativo consolidado de veículos e associados com cabeçalho extenso";
        var doc = new ExportDocument("titulo-extenso", longTitle, List.of("Campo"), List.of(),
                sink -> sink.accept(ExportRow.of("Valor legível")));
        var pdfFile = renderer.render(doc, ExportFormat.PDF);
        try (var pdf = Loader.loadPDF(pdfFile.bytes())) {
            assertThat(pdf.getNumberOfPages()).isEqualTo(1);
            assertThat(pageImages(pdf.getPage(0))).hasSize(1);
            String text = new PDFTextStripper().getText(pdf).replaceAll("\\s+", " ");
            assertThat(text).contains("JEEP CLUB |", "Gerado em 28/09/2026 09:00:00", "Campo", "Valor legível", "Página 1");
        }
        var csvFile = renderer.render(doc, ExportFormat.CSV);
        assertThat(new String(csvFile.bytes(), StandardCharsets.UTF_8))
                .isEqualTo("\uFEFF\"Campo\"\r\n\"Valor legível\"\r\n");
    }

    private static List<PDImageXObject> pageImages(PDPage page) throws Exception {
        List<PDImageXObject> images = new ArrayList<>();
        for (var name : page.getResources().getXObjectNames()) {
            var xObject = page.getResources().getXObject(name);
            if (xObject instanceof PDImageXObject image) {
                images.add(image);
            }
        }
        return images;
    }
    @Test void rowLimitFailsWithoutPartialDownload() {
        var limited=new DefaultExportRenderer(clock,1,1,16777216);
        var d=new ExportDocument("limite","Limite",List.of("ID"),List.of(),sink->{sink.accept(ExportRow.of(1));sink.accept(ExportRow.of(2));});
        for(var format:ExportFormat.values()) assertThatThrownBy(()->limited.render(d,format)).isInstanceOf(ExportException.class)
            .extracting("reason").isEqualTo(ExportException.Reason.LIMIT);
    }
    @Test void byteLimitIsEnforced() {
        assertThatThrownBy(()->new DefaultExportRenderer(clock,10,10,64).render(document("a".repeat(100)),ExportFormat.CSV))
            .isInstanceOf(ExportException.class).extracting("reason").isEqualTo(ExportException.Reason.LIMIT);
    }
    @Test void characterLimitFailsBeforeWritingOversizedCell() {
        for(var format:ExportFormat.values())assertThatThrownBy(()->renderer.render(document("a".repeat(4_000_001)),format))
            .isInstanceOf(ExportException.class).extracting("reason").isEqualTo(ExportException.Reason.LIMIT);
    }
    @Test void pageLimitFailsWithoutTruncatingLongPdf() {
        assertThatThrownBy(()->renderer.render(document("linha\n".repeat(60_000)),ExportFormat.PDF))
            .isInstanceOf(ExportException.class).extracting("reason").isEqualTo(ExportException.Reason.LIMIT);
    }
    @Test void unsafeFilenameIsRejected() {
        var d=new ExportDocument("../injection\r\n","Teste",List.of("ID"),List.of(),sink->{});
        assertThatThrownBy(()->renderer.render(d,ExportFormat.CSV)).isInstanceOf(ExportException.class);
    }
    @Test void mandatoryAuditFailurePreventsFileDelivery() {
        var audit=org.mockito.Mockito.mock(com.jeepclub.backend.platform.logging.SystemLogService.class);
        renderer.audit(audit);
        var principal=org.mockito.Mockito.mock(com.jeepclub.backend.platform.security.principal.UserPrincipal.class);
        org.mockito.Mockito.when(principal.getUserId()).thenReturn(42L);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal,null,List.of()));
        org.mockito.Mockito.doThrow(new IllegalStateException("Audit unavailable")).when(audit).recordRequired(org.mockito.ArgumentMatchers.any());
        try {assertThatThrownBy(()->renderer.render(document("clinical"),ExportFormat.PDF)).isInstanceOf(IllegalStateException.class);}
        finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    }

    @Test void pdfDrawsInstitutionalSealInHeaderBoundsWithoutOverlappingTextOrBodyAcrossScenarios() throws Exception {
        // 1. Empty dataset with many filters selected
        List<String> manyFilters = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            manyFilters.add("Critério sintético " + i + ": valor de filtragem administrativa extenso " + i);
        }
        var emptyWithManyFilters = new ExportDocument(
                "sem-registros",
                "Relatório administrativo com múltiplos filtros e conjunto vazio",
                List.of("Coluna A", "Coluna B"),
                manyFilters,
                sink -> {}
        );
        var emptyPdfFile = renderer.render(emptyWithManyFilters, ExportFormat.PDF);
        try (var pdf = Loader.loadPDF(emptyPdfFile.bytes())) {
            assertThat(pdf.getNumberOfPages()).isGreaterThanOrEqualTo(1);
            assertThat(pdf.getDocumentInformation().getCreator()).isEqualTo("Jeep Club");
            String text = new PDFTextStripper().getText(pdf);
            assertThat(text).contains("Nenhum registro encontrado.", "Critério sintético 1:", "Critério sintético 25:");
            for (int p = 0; p < pdf.getNumberOfPages(); p++) {
                assertSealGeometryAndNonOverlappingLayout(pdf, p);
            }
        }
        // CSV stays plain and never receives PDF seal text or placeholder
        String emptyCsv = new String(renderer.render(emptyWithManyFilters, ExportFormat.CSV).bytes(), StandardCharsets.UTF_8);
        assertThat(emptyCsv).isEqualTo("\uFEFF\"Coluna A\";\"Coluna B\"\r\n")
                .doesNotContain("JEEP CLUB", "Nenhum registro encontrado");

        // 2. Multi-page grouped PDF report
        var groupedDoc = new ExportDocument(
                "agrupado",
                "Relatório agrupado por titular com múltiplas páginas",
                List.of("Placa", "Modelo", "Titular", "CPF"),
                List.of("Titular: Associado Sintético"),
                sink -> {
                    for (int i = 1; i <= 35; i++) {
                        String group = "Titular: Associado Sintético " + ((i - 1) / 10 + 1);
                        sink.accept(ExportRow.of("EXP00" + String.format("%02d", i), "Jeep Wrangler " + i, group, "00000000000")
                                .grouped(group, 2, 3));
                    }
                }
        );
        var groupedPdfFile = renderer.render(groupedDoc, ExportFormat.PDF);
        try (var pdf = Loader.loadPDF(groupedPdfFile.bytes())) {
            assertThat(pdf.getNumberOfPages()).isGreaterThan(1);
            String text = new PDFTextStripper().getText(pdf);
            assertThat(text).contains("Titular: Associado Sintético 1", "Titular: Associado Sintético 4", "Registro 1", "Registro 35", "EXP0035");
            for (int p = 0; p < pdf.getNumberOfPages(); p++) {
                assertSealGeometryAndNonOverlappingLayout(pdf, p);
            }
        }
    }

    @Test void pdfWriterClosesUnderlyingPdfDocumentWhenConstructorFails() {
        var doc = document("valor");
        var rawMissingSeal = new org.apache.pdfbox.pdmodel.PDDocument();
        assertThatThrownBy(() -> new PdfExportWriter(rawMissingSeal, doc, clock.instant(), "/exports/inexistente.png"))
                .isInstanceOf( java.io.IOException.class )
                .hasMessageContaining("Missing institutional PDF seal resource");
        assertThat(rawMissingSeal.getDocument().isClosed()).isTrue();

        // Failure after opening page content stream (filters exceeding 1000-page limit inside constructor)
        List<String> excessiveFilters = Collections.nCopies(50_000, "Filtro extenso repetido para exceder limite de páginas");
        var overflowDoc = new ExportDocument("overflow", "Overflow", List.of("ID"), excessiveFilters, sink -> {});
        var rawOverflow = new org.apache.pdfbox.pdmodel.PDDocument();
        assertThatThrownBy(() -> new PdfExportWriter(rawOverflow, overflowDoc, clock.instant(), "/exports/jeepclube-emblema.png"))
                .isInstanceOf(ExportException.class)
                .extracting("reason")
                .isEqualTo(ExportException.Reason.LIMIT);
        assertThat(rawOverflow.getDocument().isClosed()).isTrue();
    }

    private static void assertSealGeometryAndNonOverlappingLayout(org.apache.pdfbox.pdmodel.PDDocument pdf, int pageIndex) throws Exception {
        PDPage page = pdf.getPage(pageIndex);
        List<float[]> drawnImages = new ArrayList<>();
        var engine = new org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine(page) {
            @Override public void appendRectangle(java.awt.geom.Point2D p0, java.awt.geom.Point2D p1, java.awt.geom.Point2D p2, java.awt.geom.Point2D p3) {}
            @Override public void clip(int windingRule) {}
            @Override public void moveTo(float x, float y) {}
            @Override public void lineTo(float x, float y) {}
            @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {}
            @Override public java.awt.geom.Point2D getCurrentPoint() { return new java.awt.geom.Point2D.Float(); }
            @Override public void closePath() {}
            @Override public void endPath() {}
            @Override public void strokePath() {}
            @Override public void fillPath(int windingRule) {}
            @Override public void fillAndStrokePath(int windingRule) {}
            @Override public void shadingFill(org.apache.pdfbox.cos.COSName shadingName) {}
            @Override public void drawImage(org.apache.pdfbox.pdmodel.graphics.image.PDImage pdImage) {
                var ctm = getGraphicsState().getCurrentTransformationMatrix();
                drawnImages.add(new float[]{
                        ctm.getTranslateX(),
                        ctm.getTranslateY(),
                        ctm.getScalingFactorX(),
                        ctm.getScalingFactorY(),
                        pdImage.getWidth(),
                        pdImage.getHeight()
                });
            }
        };
        engine.processPage(page);

        assertThat(drawnImages).as("drawn seal on page " + (pageIndex + 1)).hasSize(1);
        float[] box = drawnImages.get(0);
        float x = box[0], y = box[1], w = box[2], h = box[3], pxW = box[4], pxH = box[5];
        assertThat(pxW).isEqualTo(256f);
        assertThat(pxH).isEqualTo(246f);
        assertThat(x).isGreaterThanOrEqualTo(40f);
        assertThat(y).isGreaterThanOrEqualTo(760f);
        assertThat(y + h).isLessThanOrEqualTo(page.getMediaBox().getHeight() - 20f);
        assertThat(h).isEqualTo(36f);
        assertThat(w / h).isCloseTo(256f / 246f, within(0.01f));

        // Verify text coordinates: header lines sit to the right of the seal; body lines sit below the seal; footer sits at bottom
        float pageHeight = page.getMediaBox().getHeight();
        List<float[]> headerGlyphs = new ArrayList<>();
        List<float[]> bodyGlyphs = new ArrayList<>();
        var posStripper = new PDFTextStripper() {
            @Override
            protected void writeString(String text, List<org.apache.pdfbox.text.TextPosition> positions) {
                if (text == null || text.isBlank() || positions.isEmpty()) return;
                float minX = Float.MAX_VALUE;
                float pdfBaselineY = pageHeight - positions.get(0).getYDirAdj();
                for (var tp : positions) {
                    minX = Math.min(minX, tp.getXDirAdj());
                }
                if (text.startsWith("JEEP CLUB |") || text.startsWith("Gerado em ")) {
                    headerGlyphs.add(new float[]{minX, pdfBaselineY});
                } else if (!text.startsWith("Página ")) {
                    bodyGlyphs.add(new float[]{minX, pdfBaselineY});
                }
            }
        };
        posStripper.setStartPage(pageIndex + 1);
        posStripper.setEndPage(pageIndex + 1);
        posStripper.getText(pdf);

        assertThat(headerGlyphs).isNotEmpty();
        for (float[] hg : headerGlyphs) {
            assertThat(hg[0]).as("header text X clears seal right edge").isGreaterThan(x + w + 5f);
        }
        for (float[] bg : bodyGlyphs) {
            assertThat(bg[1]).as("body text Y stays below header seal and above footer").isLessThan(y - 8f).isGreaterThan(38f);
        }
    }
}
