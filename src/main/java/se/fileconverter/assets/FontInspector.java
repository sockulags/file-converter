package se.fileconverter.assets;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.apache.fontbox.ttf.NamingTable;
import org.apache.fontbox.ttf.OS2WindowsMetricsTable;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBuffer;

/**
 * Läser och kontrollerar en fontfil innan den får användas.
 *
 * <p>Avvisar det som garanterat ger problem i PDF/A-1: OpenType/CFF, fontsamlingar och fonter vars
 * licens (OS/2 fsType) inte tillåter inbäddning. PDF/A kräver att alla fonter bäddas in.
 */
public final class FontInspector {

    private static final int FS_TYPE_RESTRICTED = 0x0002;
    private static final int FS_TYPE_NO_SUBSETTING = 0x0100;
    private static final int FS_TYPE_BITMAP_ONLY = 0x0200;

    public record FontInfo(String family, String style, int weight, boolean italic, List<String> warnings) {
    }

    public static class InvalidFontException extends Exception {
        public InvalidFontException(String message) {
            super(message);
        }
    }

    private FontInspector() {
    }

    public static FontInfo inspect(byte[] data) throws InvalidFontException {
        if (data.length < 12) {
            throw new InvalidFontException("Filen är inte en font.");
        }
        String tag = new String(data, 0, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
        if (tag.equals("OTTO")) {
            throw new InvalidFontException("OpenType/CFF (.otf) stöds inte, eftersom PDF/A-1 inte alltid godtar hur de bäddas in. Använd TrueType (.ttf).");
        }
        if (tag.equals("ttcf")) {
            throw new InvalidFontException("Fontsamlingar (.ttc) stöds inte. Ladda upp varje font som en egen .ttf-fil.");
        }
        if (!tag.equals("true") && !(data[0] == 0 && data[1] == 1 && data[2] == 0 && data[3] == 0)) {
            throw new InvalidFontException("Filen är inte en TrueType-font.");
        }
        try (TrueTypeFont ttf = new TTFParser().parse(new RandomAccessReadBuffer(data))) {
            if (ttf.getGlyph() == null) {
                throw new InvalidFontException("Fonten saknar TrueType-konturer (glyf-tabell).");
            }
            NamingTable naming = ttf.getNaming();
            String family = naming == null ? null : first(naming.getName(16, 3, 1, 0x409), naming.getFontFamily());
            if (family == null) {
                throw new InvalidFontException("Fonten saknar familjenamn.");
            }
            String style = first(naming.getName(17, 3, 1, 0x409), naming.getFontSubFamily(), "Regular");

            OS2WindowsMetricsTable os2 = ttf.getOS2Windows();
            List<String> warnings = new ArrayList<>();
            if (os2 != null) {
                int fsType = os2.getFsType();
                if ((fsType & 0x000F) == FS_TYPE_RESTRICTED) {
                    throw new InvalidFontException("Fontens licens förbjuder inbäddning (fsType: Restricted License). PDF/A kräver inbäddade fonter.");
                }
                if ((fsType & FS_TYPE_BITMAP_ONLY) != 0) {
                    throw new InvalidFontException("Fontens licens tillåter bara inbäddning som bitmap, vilket inte fungerar i PDF.");
                }
                if ((fsType & FS_TYPE_NO_SUBSETTING) != 0) {
                    warnings.add("Licensen förbjuder delmängder, men PDF:er bäddar normalt in delmängder. Kontrollera licensen.");
                }
            }
            int weight = os2 != null ? os2.getWeightClass() : 400;
            boolean italic = os2 != null && (os2.getFsSelection() & 1) != 0;
            return new FontInfo(family, style, weight, italic, List.copyOf(warnings));
        } catch (IOException e) {
            throw new InvalidFontException("Fonten kunde inte läsas: " + e.getMessage());
        }
    }

    private static String first(String... values) {
        return Stream.of(values).filter(Objects::nonNull).filter(v -> !v.isBlank()).findFirst().orElse(null);
    }
}
