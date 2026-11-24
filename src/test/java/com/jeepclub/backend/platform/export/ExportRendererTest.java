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
}
