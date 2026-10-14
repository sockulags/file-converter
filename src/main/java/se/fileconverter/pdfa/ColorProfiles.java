package se.fileconverter.pdfa;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDStream;

/**
 * ICC-profiler som PDF/A-1 godtar (ICC version 2). sRGB och gråskala kommer från Java, CMYK är
 * FOGRA39 från colord (CC0, fri att använda och sprida).
 */
final class ColorProfiles {

    static final byte[] SRGB = ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData();
    static final byte[] GRAY = ICC_Profile.getInstance(ColorSpace.CS_GRAY).getData();
    static final byte[] CMYK = load("/icc/FOGRA39L_coated.icc");

    private ColorProfiles() {
    }

    /** Profil med samma antal färgkomponenter, eller null för ovanliga antal. */
    static byte[] forComponents(int components) {
        return switch (components) {
            case 1 -> GRAY;
            case 3 -> SRGB;
            case 4 -> CMYK;
            default -> null;
        };
    }

    /** PDF/A-1 bygger på PDF 1.4, som bara känner till ICC-profiler före version 4. */
    static boolean tooNew(COSStream profile) throws IOException {
        try (InputStream in = profile.createInputStream()) {
            byte[] header = in.readNBytes(12);
            return header.length >= 9 && (header[8] & 0xff) >= 4;
        }
    }

    /** Skriver om profilens data med en version 2-profil för samma antal komponenter. */
    static boolean downgrade(COSStream profile) throws IOException {
        byte[] replacement = forComponents(profile.getInt(COSName.N, 3));
        if (replacement == null) {
            return false;
        }
        try (OutputStream out = profile.createOutputStream(COSName.FLATE_DECODE)) {
            out.write(replacement);
        }
        return true;
    }

    /** {@code [/ICCBased ström]} för t.ex. DefaultCMYK. */
    static COSArray iccBased(PDDocument doc, byte[] profile, int components) throws IOException {
        PDStream stream = new PDStream(doc, new ByteArrayInputStream(profile), COSName.FLATE_DECODE);
        stream.getCOSObject().setInt(COSName.N, components);
        stream.getCOSObject().setItem(COSName.ALTERNATE,
                components == 4 ? COSName.DEVICECMYK : components == 1 ? COSName.DEVICEGRAY : COSName.DEVICERGB);
        COSArray array = new COSArray();
        array.add(COSName.ICCBASED);
        array.add(stream);
        return array;
    }

    static int components(COSBase profile) {
        return profile instanceof COSStream stream ? stream.getInt(COSName.N, 3) : 3;
    }

    private static byte[] load(String resource) {
        try (InputStream in = ColorProfiles.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Saknar " + resource);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
