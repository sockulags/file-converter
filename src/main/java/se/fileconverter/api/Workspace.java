package se.fileconverter.api;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

/** Temporär katalog per anrop med de uppladdade filerna. Tas bort när anropet är klart. */
public record Workspace(Path dir, List<Path> files) implements AutoCloseable {

    public static Workspace from(MultipartHttpServletRequest request) {
        List<MultipartFile> uploads = request.getMultiFileMap().values().stream().flatMap(List::stream).toList();
        if (uploads.isEmpty()) {
            throw ConversionException.badRequest("Inga filer i anropet.");
        }
        Workspace workspace;
        try {
            workspace = new Workspace(Files.createTempDirectory("convert-"), new ArrayList<>());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            for (MultipartFile upload : uploads) {
                String name = upload.getOriginalFilename();
                if (name == null || name.isBlank() || name.contains("/") || name.contains("\\") || name.startsWith(".")) {
                    throw ConversionException.badRequest("Ogiltigt filnamn: " + name);
                }
                Path target = workspace.dir().resolve(name);
                if (workspace.files().contains(target)) {
                    // Annars skriver den andra filen över den första och ena dokumentet försvinner tyst.
                    throw ConversionException.badRequest(ConversionException.Reason.DUPLICATE_FILENAME, "Filnamnet '" + name + "' förekommer flera gånger. Ge filerna unika namn.");
                }
                upload.transferTo(target);
                workspace.files().add(target);
            }
            return workspace;
        } catch (IOException | RuntimeException e) {
            workspace.close();
            throw e instanceof IOException io ? new UncheckedIOException(io) : (RuntimeException) e;
        }
    }

    @Override
    public void close() {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException ignored) {
            // Temporärkatalogen städas av operativsystemet om det här misslyckas.
        }
    }
}
