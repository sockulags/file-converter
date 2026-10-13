package se.fileconverter.pdfa;

import java.util.Optional;
import org.verapdf.pdfa.flavours.PDFAFlavour;

public enum PdfaLevel {
    PDFA_1A("PDF/A-1a", "A", PDFAFlavour.PDFA_1_A),
    PDFA_1B("PDF/A-1b", "B", PDFAFlavour.PDFA_1_B);

    private final String label;
    private final String conformance;
    private final PDFAFlavour flavour;

    PdfaLevel(String label, String conformance, PDFAFlavour flavour) {
        this.label = label;
        this.conformance = conformance;
        this.flavour = flavour;
    }

    /**
     * Tolkar Gotenbergs formulärvärde, t.ex. "PDF/A-1b". Tomt värde betyder ingen PDF/A.
     */
    public static Optional<PdfaLevel> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        for (PdfaLevel level : values()) {
            if (level.label.equalsIgnoreCase(value.trim())) {
                return Optional.of(level);
            }
        }
        throw new IllegalArgumentException(
                "PDF/A-nivån '" + value + "' stöds inte. Giltiga värden: PDF/A-1a, PDF/A-1b");
    }

    public String label() {
        return label;
    }

    public String conformance() {
        return conformance;
    }

    public PDFAFlavour flavour() {
        return flavour;
    }

    public boolean requiresTagging() {
        return this == PDFA_1A;
    }
}
