package com.jeepclub.backend.platform.export;
import com.jeepclub.backend.shared.export.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import java.io.*;
import java.time.Instant;
import java.util.*;
/** A4 report with a repeated institutional masthead and readable field/value tables, including multi-page fields. */
final class PdfExportWriter implements AutoCloseable {
    private static final String SEAL_RESOURCE = "/exports/jeepclube-emblema.png";
    private static final float MARGIN_X = 40f;
    private static final float RIGHT_X = 555f;
    private static final float SEAL_Y = 766f;
    private static final float SEAL_HEIGHT = 36f;

    private final PDDocument pdf;
    private final PDType0Font font;
    private final PDImageXObject seal;
    private final ExportDocument document;
    private final Instant generated;
    private PDPageContentStream stream;
    private float y;
    private String group;
    private int rows;
    PdfExportWriter(ExportDocument document, Instant generated) throws IOException {
        this(new PDDocument(), document, generated, SEAL_RESOURCE);
    }
    PdfExportWriter(PDDocument pdf, ExportDocument document, Instant generated, String sealResource) throws IOException {
        this.pdf = Objects.requireNonNull(pdf, "pdf");
        this.document = Objects.requireNonNull(document, "document");
        this.generated = Objects.requireNonNull(generated, "generated");
        try {
            try (var input = PDType0Font.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
                if (input == null) {
                    throw new IOException("Missing embedded PDF font resource");
                }
                font = PDType0Font.load(this.pdf, input);
            }
            this.seal = loadSeal(this.pdf, sealResource);
            this.pdf.getDocumentInformation().setTitle(document.title());
            this.pdf.getDocumentInformation().setCreator("Jeep Club");
            var date = GregorianCalendar.from(generated.atZone(java.time.ZoneOffset.UTC));
            this.pdf.getDocumentInformation().setCreationDate(date);
            page();
            for (String filter : document.filters()) text("Filtro: " + filter, 10, 500);
            y -= 12;
        } catch (Throwable failure) {
            try {
                close();
            } catch (Throwable closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }
    private static PDImageXObject loadSeal(PDDocument pdf, String sealResource) throws IOException {
        try (var input = PdfExportWriter.class.getResourceAsStream(sealResource)) {
            if (input == null) {
                throw new IOException("Missing institutional PDF seal resource: " + sealResource);
            }
            return PDImageXObject.createFromByteArray(pdf, input.readAllBytes(), "jeep-club-seal");
        }
    }
    private void page() throws IOException {
        if (pdf.getNumberOfPages()>=1000) throw new ExportException(ExportException.Reason.LIMIT);
        if (stream != null) {
            var previous = stream;
            stream = null;
            previous.close();
        }
        var page=new PDPage(PDRectangle.A4); pdf.addPage(page);
        stream=new PDPageContentStream(pdf,page);
        float sealWidth = SEAL_HEIGHT * seal.getWidth() / (float) seal.getHeight();
        stream.drawImage(seal, MARGIN_X, SEAL_Y, sealWidth, SEAL_HEIGHT);
        float headerTextX = MARGIN_X + sealWidth + 10f;
        int headerTextWidth = (int) Math.floor(RIGHT_X - headerTextX);
        y = 789f;
        for (String line : wrap("JEEP CLUB | " + document.title(), headerTextWidth, 13)) {
            draw(line, headerTextX, y, 13);
            y -= 17f;
        }
        draw("Gerado em " + ExportValues.text(generated), headerTextX, y, 9);
        y = Math.min(y - 16f, SEAL_Y - 8f);
        stream.setStrokingColor(0.75f);
        stream.moveTo(MARGIN_X, y);
        stream.lineTo(RIGHT_X, y);
        stream.stroke();
        y -= 16f;
        draw("Página " + pdf.getNumberOfPages(), MARGIN_X, 28, 9);
    }
    void row(ExportRow row) throws IOException {
        if (row.group()!=null && !Objects.equals(group,row.group())) {
            group=row.group(); if(y<130) page(); text(group,12,500); y-=8;
        }
        if(y<120) page();
        text("Registro " + (++rows),11,500);
        for(int i=0;i<row.cells().size();i++) {
            if(row.group()!=null && row.groupedHeaderColumns().contains(i))continue;
            var values=wrap(row.cells().get(i).isEmpty()?"—":row.cells().get(i),335,10);
            var labels=wrap(document.columns().get(i),155,10);
            int count=Math.max(values.size(),labels.size());
            for(int line=0;line<count;line++) {
                if(y<60) page();
                if(line<labels.size()) draw(labels.get(line),42,y,10);
                if(line<values.size()) draw(values.get(line),205,y,10);
                y-=14;
            }
            y-=4;
        }
        stream.setStrokingColor(0.8f);stream.moveTo(40,y);stream.lineTo(555,y);stream.stroke();y-=16;
    }
    private void text(String value,int size,int width) throws IOException {
        for(String line:wrap(value,width,size)) { if(y<60)page();draw(line,40,y,size);y-=size+5; }
    }
    private String safe(String value) throws IOException {
        var out=new StringBuilder();
        for(int cp:value.codePoints().toArray()) {
            if(cp=='\n') {out.append('\n');continue;}
            if(Character.isISOControl(cp)) {out.append(' ');continue;}
            String s=new String(Character.toChars(cp));
            try {font.encode(s);out.append(s);}catch(IllegalArgumentException e){out.append('?');}
        }
        return out.toString();
    }
    private List<String> wrap(String value,int width,int size) throws IOException {
        var lines=new ArrayList<String>(); var line=new StringBuilder();
        for(int cp:safe(value).codePoints().toArray()) {
            if(cp=='\n') {lines.add(line.toString());line.setLength(0);continue;}
            String s=new String(Character.toChars(cp));
            if(font.getStringWidth(line+s)/1000*size>width && !line.isEmpty()) {lines.add(line.toString());line.setLength(0);}
            line.append(s);
        }
        lines.add(line.toString());return lines;
    }
    private void draw(String value,float x,float baseline,int size) throws IOException {
        stream.beginText();stream.setFont(font,size);stream.newLineAtOffset(x,baseline);stream.showText(safe(value));stream.endText();
    }
    void save(OutputStream output) throws IOException {
        if(rows==0) text("Nenhum registro encontrado.",11,500);
        if (stream != null) {
            var current = stream;
            stream = null;
            current.close();
        }
        pdf.save(output);
    }
    @Override
    public void close() throws IOException {
        try (pdf) {
            if (stream != null) {
                var current = stream;
                stream = null;
                current.close();
            }
        }
    }
}
