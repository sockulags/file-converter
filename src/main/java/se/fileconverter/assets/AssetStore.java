package se.fileconverter.assets;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import se.fileconverter.config.ConverterProperties;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Resurser som administreras i drift: uppladdade fonter och fontalias.
 *
 * <p>Allt ligger i en katalog ({@code converter.assets.dir}) som i Kubernetes är en delad volym.
 * Varje pod läser katalogen och laddar om när innehållet ändras, så en uppladdning via en pod når alla.
 *
 * <pre>
 * {dir}/fonts/*.ttf
 * {dir}/aliases.json   {"Garamond": "EB Garamond", ...}
 * </pre>
 */
@Component
public class AssetStore {

    public static final String ALIASES_FILE = "aliases.json";
    private static final Pattern FONT_FILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._ -]{0,120}\\.ttf");
    private static final long MAX_IMPORT_BYTES = 200L * 1024 * 1024;
    private static final int MAX_IMPORT_ENTRIES = 500;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Logger log = LoggerFactory.getLogger(AssetStore.class);

    private final Path root;
    private final Path fontsDir;

    public AssetStore(ConverterProperties properties) {
        this.root = Paths.get(properties.assets().dir()).toAbsolutePath().normalize();
        this.fontsDir = root.resolve("fonts");
        try {
            Files.createDirectories(fontsDir);
        } catch (IOException e) {
            // Vanligast i Kubernetes: volymen saknar skrivrätt för uid 1001 (fsGroup saknas eller stöds inte
            // av lagringen). Starta ändå, med imagens fonter, i stället för att hamna i CrashLoopBackOff.
            log.warn("Kan inte skapa resurskatalogen {} ({}). Startar utan uppladdade fonter, och uppladdning "
                    + "i administrationen fungerar inte. Kontrollera volymens behörigheter (fsGroup).", fontsDir, e.toString());
        }
    }

    public Path fontsDir() {
        return fontsDir;
    }

    public List<Path> fontFiles() {
        if (!Files.isDirectory(fontsDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(fontsDir)) {
            return files.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ttf")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Kontrollerar och sparar fonten. Ett befintligt filnamn skrivs över. */
    public FontInspector.FontInfo addFont(String fileName, byte[] data) throws FontInspector.InvalidFontException {
        String name = Paths.get(fileName == null ? "" : fileName).getFileName().toString();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".ttf")) {
            name = name.replaceAll("\\.[^.]*$", "") + ".ttf";
        }
        if (!FONT_FILE_NAME.matcher(name).matches()) {
            throw new FontInspector.InvalidFontException("Ogiltigt filnamn: " + fileName);
        }
        FontInspector.FontInfo info = FontInspector.inspect(data);
        writeAtomically(fontsDir.resolve(name), data);
        return info;
    }

    public boolean deleteFont(String fileName) {
        if (fileName == null || !FONT_FILE_NAME.matcher(fileName).matches()) {
            return false;
        }
        try {
            return Files.deleteIfExists(fontsDir.resolve(fileName));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Map<String, String> aliases() {
        Path file = root.resolve(ALIASES_FILE);
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try {
            return parseAliases(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void saveAliases(Map<String, String> aliases) {
        Map<String, String> clean = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        aliases.forEach((from, to) -> {
            if (from != null && to != null && !from.isBlank() && !to.isBlank()) {
                clean.put(from.trim(), to.trim());
            }
        });
        if (clean.equals(aliases()) && Files.isRegularFile(root.resolve(ALIASES_FILE))) {
            return;  // Oförändrat: skriv inte, så att andra poddar inte laddar om i onödan.
        }
        writeAtomically(root.resolve(ALIASES_FILE), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(new LinkedHashMap<>(clean)));
    }

    /**
     * Ändras när fonter eller alias ändras. Jämförs regelbundet av {@link AssetReloader}.
     */
    public String fingerprint() {
        StringBuilder print = new StringBuilder();
        List<Path> files = new ArrayList<>(fontFiles());
        files.add(root.resolve(ALIASES_FILE));
        for (Path file : files) {
            try {
                BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                print.append(file.getFileName()).append(':').append(attrs.size()).append(':')
                        .append(attrs.lastModifiedTime().toMillis()).append(';');
            } catch (IOException e) {
                print.append(file.getFileName()).append(":-;");
            }
        }
        return print.toString();
    }

    /** Alla uppladdade fonter och alias i en zip, för att flytta uppsättningen mellan miljöer. */
    public byte[] exportZip() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Path font : fontFiles()) {
                zip.putNextEntry(new ZipEntry("fonts/" + font.getFileName()));
                zip.write(Files.readAllBytes(font));
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry(ALIASES_FILE));
            zip.write(JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(aliases()));
            zip.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    public record ImportResult(int fonts, int aliases) {
    }

    /**
     * Ersätter alla uppladdade fonter och alias med innehållet i zip-filen. Allt kontrolleras
     * innan något skrivs, så en ogiltig fil lämnar nuvarande uppsättning orörd.
     */
    public ImportResult importZip(byte[] zipBytes) throws FontInspector.InvalidFontException {
        Map<String, byte[]> fonts = new LinkedHashMap<>();
        Map<String, String> aliases = Map.of();
        long total = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (++entries > MAX_IMPORT_ENTRIES) {
                    throw new FontInspector.InvalidFontException("Zip-filen har för många filer.");
                }
                if (entry.isDirectory()) {
                    continue;
                }
                byte[] data = zip.readNBytes((int) Math.min(MAX_IMPORT_BYTES - total + 1, Integer.MAX_VALUE - 8));
                total += data.length;
                if (total > MAX_IMPORT_BYTES) {
                    throw new FontInspector.InvalidFontException("Zip-filen är för stor uppackad.");
                }
                String name = entry.getName();
                if (name.equals(ALIASES_FILE)) {
                    aliases = parseAliases(data);
                } else if (name.startsWith("fonts/") && FONT_FILE_NAME.matcher(name.substring(6)).matches()) {
                    try {
                        FontInspector.inspect(data);
                    } catch (FontInspector.InvalidFontException e) {
                        throw new FontInspector.InvalidFontException(name.substring(6) + ": " + e.getMessage());
                    }
                    fonts.put(name.substring(6), data);
                } else {
                    throw new FontInspector.InvalidFontException("Okänd fil i zip: " + name);
                }
            }
        } catch (IOException e) {
            throw new FontInspector.InvalidFontException("Zip-filen kunde inte läsas: " + e.getMessage());
        }

        for (Path existing : fontFiles()) {
            if (!fonts.containsKey(existing.getFileName().toString())) {
                deleteFont(existing.getFileName().toString());
            }
        }
        fonts.forEach((name, data) -> writeAtomically(fontsDir.resolve(name), data));
        saveAliases(aliases);
        return new ImportResult(fonts.size(), aliases.size());
    }

    private static Map<String, String> parseAliases(byte[] json) {
        return JSON.readValue(json, new TypeReference<LinkedHashMap<String, String>>() {
        });
    }

    /** Skriv till temporär fil och flytta, så att andra poddar aldrig läser en halvskriven fil. */
    private static void writeAtomically(Path target, byte[] data) {
        try {
            Path temp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            Files.write(temp, data);
            try {
                // createTempFile ger 600; på en delad volym ska filerna gå att läsa för alla.
                Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-r--r--"));
            } catch (UnsupportedOperationException ignored) {
                // Inte POSIX (t.ex. Windows vid lokal utveckling).
            }
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
