package se.fileconverter.pdfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PdfaLevelTest {

    @Test
    void gotenbergValuesAreAccepted() {
        assertThat(PdfaLevel.parse("PDF/A-1b")).contains(PdfaLevel.PDFA_1B);
        assertThat(PdfaLevel.parse("PDF/A-2b")).contains(PdfaLevel.PDFA_2B);
        assertThat(PdfaLevel.parse("pdf/a-3B")).contains(PdfaLevel.PDFA_3B);
        assertThat(PdfaLevel.parse(" ")).isEmpty();
        assertThatThrownBy(() -> PdfaLevel.parse("PDF/A-4")).hasMessageContaining("PDF/A-2u");
    }

    @Test
    void higherConformanceSatisfiesLowerInTheSamePart() {
        assertThat(PdfaLevel.PDFA_2A.satisfies(PdfaLevel.PDFA_2B)).isTrue();
        assertThat(PdfaLevel.PDFA_2U.satisfies(PdfaLevel.PDFA_2B)).isTrue();
        assertThat(PdfaLevel.PDFA_2B.satisfies(PdfaLevel.PDFA_2A)).isFalse();
        assertThat(PdfaLevel.PDFA_1A.satisfies(PdfaLevel.PDFA_2B)).isFalse();
        assertThat(PdfaLevel.PDFA_3B.satisfies(PdfaLevel.PDFA_3B)).isTrue();
    }

    @Test
    void untaggedFileFallsBackToBInTheSamePart() {
        assertThat(PdfaLevel.PDFA_1A.untaggedFallback()).isEqualTo(PdfaLevel.PDFA_1B);
        assertThat(PdfaLevel.PDFA_3A.untaggedFallback()).isEqualTo(PdfaLevel.PDFA_3B);
        assertThat(PdfaLevel.PDFA_2U.untaggedFallback()).isEqualTo(PdfaLevel.PDFA_2U);
    }
}
