package se.fileconverter.pdfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import se.fileconverter.TestPdfs;
import se.fileconverter.api.ConversionException;
import se.fileconverter.config.ConverterProperties;

class PdfaServiceTest {

    private final PdfaValidator validator = new PdfaValidator();
    private final PdfaService service = new PdfaService(new PdfaNormalizer(), validator,
            new ConverterProperties(null, new ConverterProperties.Fonts(List.of(), Map.of()), new ConverterProperties.Pdfa(true), null, null, null));

    @Test
    void untaggedPdfBecomesValidPdfa1b() {
        byte[] input = TestPdfs.untaggedWithAlphaImage();
        assertThat(validator.validate(input, PdfaLevel.PDFA_1B).compliant()).isFalse();

        PdfaService.Result result = service.convert(input, PdfaLevel.PDFA_1B, null);

        assertThat(result.report().violations()).isEmpty();
        assertThat(result.report().compliant()).isTrue();
    }

    @Test
    void untaggedPdfIsRejectedForPdfa1a() {
        assertThatThrownBy(() -> service.convert(TestPdfs.untaggedWithAlphaImage(), PdfaLevel.PDFA_1A, null))
                .isInstanceOf(ConversionException.class)
                .hasMessageContaining("saknar taggstruktur");
    }

    @Test
    void emptyStructureTreeIsRejectedForPdfa1aAlthoughVeraPdfWouldAcceptIt() {
        assertThatThrownBy(() -> service.convert(TestPdfs.withEmptyStructureTree(), PdfaLevel.PDFA_1A, null))
                .isInstanceOf(ConversionException.class)
                .hasMessageContaining("saknar taggstruktur");
    }

    @Test
    void partlyTaggedPdfIsRejectedForPdfa1aAlthoughVeraPdfWouldAcceptIt() {
        // Sammanslagen taggad + otaggad fil: strukturträdet finns, men sida 2 saknar taggar.
        byte[] merged = new se.fileconverter.pdf.PdfMerger().merge(
                List.of(TestPdfs.fixture("chromium-tagged.pdf"), TestPdfs.untaggedWithAlphaImage()));

        assertThatThrownBy(() -> service.convert(merged, PdfaLevel.PDFA_1A, null))
                .isInstanceOfSatisfying(ConversionException.class,
                        e -> assertThat(e.details()).anyMatch(d -> d.startsWith("Sida 2 saknar taggar")));
    }

    @Test
    void taggedChromiumPdfBecomesValidPdfa1a() {
        PdfaService.Result result = service.convert(TestPdfs.fixture("chromium-tagged.pdf"), PdfaLevel.PDFA_1A, null);

        assertThat(result.report().violations()).isEmpty();
    }

    @Test
    void type1FontSubsetGetsCharSet() {
        // LibreOffice-PDF med en OpenType/CFF-font (Inter), inbäddad som Type 1 utan CharSet.
        byte[] input = TestPdfs.fixture("type1-cff-subset.pdf");
        assertThat(validator.validate(input, PdfaLevel.PDFA_1A).violations()).anyMatch(v -> v.startsWith("6.3.5-2"));

        PdfaService.Result result = service.convert(input, PdfaLevel.PDFA_1A, null);

        assertThat(result.report().violations()).isEmpty();
    }

    @Test
    void hiddenAnnotationsAreRemovedNotRevealed() throws Exception {
        PdfaService.Result result = service.convert(TestPdfs.withHiddenAnnotation(), PdfaLevel.PDFA_1B, null);

        assertThat(result.report().violations()).isEmpty();
        try (var doc = org.apache.pdfbox.Loader.loadPDF(result.pdf())) {
            assertThat(doc.getPage(0).getAnnotations()).extracting(a -> a.getSubtype()).containsExactly("Link");
        }
    }

    @Test
    void hugeImageIsRejectedInsteadOfExhaustingMemory() {
        assertThatThrownBy(() -> service.convert(TestPdfs.withHugeImage(), PdfaLevel.PDFA_1B, null))
                .isInstanceOfSatisfying(ConversionException.class,
                        e -> assertThat(e.details()).anyMatch(d -> d.contains("7000 × 7000")));
    }

    @Test
    void cmykContentKeepsItsColoursWithDefaultCmyk() {
        byte[] input = TestPdfs.withCmyk();
        PdfaService.Result result = service.convert(input, PdfaLevel.PDFA_1B, null);

        assertThat(result.report().violations()).isEmpty();
    }

    @Test
    void iccVersion4ProfilesAreReplaced() {
        PdfaService.Result result = service.convert(TestPdfs.withIccV4Image(), PdfaLevel.PDFA_1B, null);

        assertThat(result.report().violations()).isEmpty();
    }

    @Test
    void incompleteCidSetIsRecomputed() throws Exception {
        byte[] broken;
        try (var doc = org.apache.pdfbox.Loader.loadPDF(TestPdfs.fixture("chromium-tagged.pdf"))) {
            var resources = doc.getPage(0).getResources();
            for (var name : resources.getFontNames()) {
                if (resources.getFont(name) instanceof org.apache.pdfbox.pdmodel.font.PDType0Font type0) {
                    type0.getDescendantFont().getFontDescriptor().setCIDSet(new org.apache.pdfbox.pdmodel.common.PDStream(doc,
                            new java.io.ByteArrayInputStream(new byte[] {(byte) 0x80})));
                }
            }
            var out = new java.io.ByteArrayOutputStream();
            doc.save(out);
            broken = out.toByteArray();
        }

        PdfaService.Result result = service.convert(broken, PdfaLevel.PDFA_1A, null);

        assertThat(result.report().violations()).isEmpty();
    }

    @Test
    void bestEffortReturnsTheFileAndSaysWhatIsLeft() {
        PdfaService lenient = new PdfaService(new PdfaNormalizer(), validator,
                new ConverterProperties(null, new ConverterProperties.Fonts(List.of(), Map.of()), new ConverterProperties.Pdfa(false), null, null, null));

        PdfaService.Result result = lenient.convert(TestPdfs.withTransparency(), PdfaLevel.PDFA_1B, null);

        assertThat(result.pdf()).isNotEmpty();
        assertThat(result.report().compliant()).isFalse();
        assertThat(result.report().violations()).anyMatch(v -> v.startsWith("TRANSPARENCY: Transparens på sida 1"));
    }

    @Test
    void bestEffortLabelsUntaggedFileAs1bInsteadOfClaiming1a() {
        PdfaService lenient = new PdfaService(new PdfaNormalizer(), validator,
                new ConverterProperties(null, new ConverterProperties.Fonts(List.of(), Map.of()), new ConverterProperties.Pdfa(false), null, null, null));

        PdfaService.Result result = lenient.convert(TestPdfs.untaggedWithAlphaImage(), PdfaLevel.PDFA_1A, null);

        assertThat(result.report().level()).isEqualTo(PdfaLevel.PDFA_1B);
        assertThat(result.report().compliant()).as("1a begärdes men blev 1b").isFalse();
        assertThat(result.report().violations()).singleElement().asString().startsWith("UNTAGGED:");
        assertThat(validator.validate(result.pdf(), PdfaLevel.PDFA_1B).compliant()).isTrue();
    }

    @Test
    void realTransparencyIsRejected() {
        assertThatThrownBy(() -> service.convert(TestPdfs.withTransparency(), PdfaLevel.PDFA_1B, null))
                .isInstanceOfSatisfying(ConversionException.class,
                        e -> assertThat(e.details()).anyMatch(d -> d.contains("Transparens på sida 1")));
    }

    @Test
    void nonEmbeddedFontIsRejected() {
        assertThatThrownBy(() -> service.convert(TestPdfs.withStandardFont(), PdfaLevel.PDFA_1B, null))
                .isInstanceOfSatisfying(ConversionException.class,
                        e -> assertThat(e.details()).anyMatch(d -> d.contains("Helvetica")));
    }

    @Test
    void encryptedPdfIsRejected() {
        assertThatThrownBy(() -> service.convert(TestPdfs.encrypted(), PdfaLevel.PDFA_1B, null))
                .isInstanceOf(ConversionException.class)
                .hasMessageContaining("krypterad");
    }
}
