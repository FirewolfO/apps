import java.io.File;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/** Desktop counterpart of PdfTranscriptParser's PDFBox extraction settings. */
class ExtractPdfText {
    public static void main(String[] args) throws Exception {
        try (PDDocument document = PDDocument.load(new File(args[0]))) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setPageEnd("\f");
            System.out.print(stripper.getText(document));
        }
    }
}
