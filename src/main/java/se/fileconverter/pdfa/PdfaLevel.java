package se.fileconverter.pdfa;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;
import org.verapdf.pdfa.flavours.PDFAFlavour;

/**
 * PDF/A-nivåerna tjänsten kan skapa. Del 1 bygger på PDF 1.4 och förbjuder transparens; del 2 och 3
 * bygger på PDF 1.7 och tillåter transparens, ICC v4, JPEG2000 och objektströmmar. Del 3 tillåter
 * dessutom bifogade filer av alla slag.
 *
 * <p>Överensstämmelsenivåer: b (utseendet bevaras), u (dessutom Unicode för all text) och a
 * (dessutom taggad, tillgänglig struktur). En högre nivå uppfyller de lägre i samma del.
 */
public enum PdfaLevel {
    PDFA_1A("PDF/A-1a", 1, "A", PDFAFlavour.PDFA_1_A),
    PDFA_1B("PDF/A-1b", 1, "B", PDFAFlavour.PDFA_1_B),
    PDFA_2A("PDF/A-2a", 2, "A", PDFAFlavour.PDFA_2_A),
    PDFA_2U("PDF/A-2u", 2, "U", PDFAFlavour.PDFA_2_U),
    PDFA_2B("PDF/A-2b", 2, "B", PDFAFlavour.PDFA_2_B),
    PDFA_3A("PDF/A-3a", 3, "A", PDFAFlavour.PDFA_3_A),
    PDFA_3U("PDF/A-3u", 3, "U", PDFAFlavour.PDFA_3_U),
    PDFA_3B("PDF/A-3b", 3, "B", PDFAFlavour.PDFA_3_B);

    private final String label;
    private final int part;
    private final String conformance;
    private final PDFAFlavour flavour;

    PdfaLevel(String label, int part, String conformance, PDFAFlavour flavour) {
        this.label = label;
        this.part = part;
        this.conformance = conformance;
        this.flavour = flavour;
    }

    /**
     * Tolkar Gotenbergs formulärvärde, t.ex. "PDF/A-2b". Tomt värde betyder ingen PDF/A.
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
        throw new IllegalArgumentException("PDF/A-nivån '" + value + "' stöds inte. Giltiga värden: "
                + Arrays.stream(values()).map(PdfaLevel::label).collect(Collectors.joining(", ")));
    }

    public static PdfaLevel of(int part, String conformance) {
        return Arrays.stream(values()).filter(level -> level.part == part && level.conformance.equals(conformance))
                .findFirst().orElseThrow();
    }

    public String label() {
        return label;
    }

    /** Delen av standarden: 1, 2 eller 3. */
    public int part() {
        return part;
    }

    public String conformance() {
        return conformance;
    }

    public PDFAFlavour flavour() {
        return flavour;
    }

    public boolean requiresTagging() {
        return conformance.equals("A");
    }

    /** Nivån en otaggad fil kan bli i bästa försök-läge: b i samma del. */
    public PdfaLevel untaggedFallback() {
        return requiresTagging() ? of(part, "B") : this;
    }

    public boolean allowsTransparency() {
        return part >= 2;
    }

    /** Om filen uppfyller den begärda nivån: samma del, och minst lika hög överensstämmelse (a ⊇ u ⊇ b). */
    public boolean satisfies(PdfaLevel requested) {
        return part == requested.part && rank() >= requested.rank();
    }

    private int rank() {
        return "BUA".indexOf(conformance);
    }
}
