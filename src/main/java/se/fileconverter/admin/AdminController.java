package se.fileconverter.admin;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import se.fileconverter.api.ConcurrencyLimit;
import se.fileconverter.api.ConversionException;
import se.fileconverter.api.Workspace;
import se.fileconverter.assets.AssetReloader;
import se.fileconverter.assets.AssetStore;
import se.fileconverter.assets.FontInspector;
import se.fileconverter.html.FontRegistry;
import se.fileconverter.observability.ConversionTelemetry;
import se.fileconverter.pdfa.PdfaLevel;

/**
 * API för administrationssidan ({@code /admin}). Skyddas av {@link AdminAuthInterceptor}.
 */
@RestController
public class AdminController {

    private final AssetStore store;
    private final AssetReloader reloader;
    private final FontRegistry fonts;
    private final CheckService check;

    private final ConversionTelemetry telemetry;

    public AdminController(AssetStore store, AssetReloader reloader, FontRegistry fonts, CheckService check,
            ConversionTelemetry telemetry) {
        this.telemetry = telemetry;
        this.store = store;
        this.reloader = reloader;
        this.fonts = fonts;
        this.check = check;
    }

    public record FontView(String file, String family, String style, int weight, boolean italic, boolean uploaded) {
    }

    public record UploadResult(String file, boolean ok, String family, String style, String message, List<String> warnings) {
    }

    @GetMapping({"/admin", "/admin/"})
    public ResponseEntity<Void> index() {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/admin/index.html")).build();
    }

    /** Gränssnittet levereras härifrån, inte som statisk resurs, så att samma inloggningskontroll gäller. */
    @GetMapping("/admin/index.html")
    public ResponseEntity<byte[]> page() throws IOException {
        try (var in = AdminController.class.getResourceAsStream("/admin/index.html")) {
            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(in.readAllBytes());
        }
    }

    /** Statistik för den här instansen sedan start. För hela klustret: Prometheus. */
    @GetMapping("/admin/api/stats")
    public ConversionTelemetry.Stats stats() {
        return telemetry.snapshot();
    }

    @GetMapping("/admin/api/fonts")
    public List<FontView> listFonts() {
        return fonts.fonts().stream()
                .map(font -> new FontView(font.file().getName(), font.family(), font.style(), font.weight(), font.italic(),
                        font.source() == FontRegistry.Source.UPLOADED))
                .toList();
    }

    @PostMapping("/admin/api/fonts")
    public List<UploadResult> uploadFonts(@RequestParam("files") List<MultipartFile> files) throws IOException {
        List<UploadResult> results = new ArrayList<>();
        for (MultipartFile file : files) {
            try {
                FontInspector.FontInfo info = store.addFont(file.getOriginalFilename(), file.getBytes());
                results.add(new UploadResult(file.getOriginalFilename(), true, info.family(), info.style(), "Installerad", info.warnings()));
            } catch (FontInspector.InvalidFontException e) {
                results.add(new UploadResult(file.getOriginalFilename(), false, null, null, e.getMessage(), List.of()));
            }
        }
        reloader.reloadIfChanged();
        return results;
    }

    @DeleteMapping("/admin/api/fonts/{file}")
    public ResponseEntity<Void> deleteFont(@PathVariable String file) {
        if (!store.deleteFont(file)) {
            return ResponseEntity.notFound().build();
        }
        reloader.reloadIfChanged();
        return ResponseEntity.noContent().build();
    }

    /** Administrerade alias plus de inbyggda från konfigurationen (som inte kan tas bort här). */
    @GetMapping("/admin/api/aliases")
    public Map<String, Object> aliases() {
        return Map.of("custom", store.aliases(), "effective", fonts.aliases());
    }

    @PutMapping("/admin/api/aliases")
    public Map<String, Object> saveAliases(@RequestBody Map<String, String> aliases) {
        store.saveAliases(aliases);
        reloader.reloadIfChanged();
        return aliases();
    }

    @GetMapping("/admin/api/export")
    public ResponseEntity<byte[]> export() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header("Content-Disposition", ContentDisposition.attachment().filename("file-converter-resurser.zip").build().toString())
                .body(store.exportZip());
    }

    @PostMapping("/admin/api/import")
    public AssetStore.ImportResult importZip(@RequestParam("file") MultipartFile file) throws IOException {
        try {
            AssetStore.ImportResult result = store.importZip(file.getBytes());
            reloader.reloadIfChanged();
            return result;
        } catch (FontInspector.InvalidFontException e) {
            throw ConversionException.badRequest(e.getMessage());
        }
    }

    @ConcurrencyLimit.Limited
    @PostMapping("/admin/api/check")
    public CheckService.Result check(MultipartHttpServletRequest request) {
        PdfaLevel level = PdfaLevel.parse(request.getParameter("pdfa")).orElse(PdfaLevel.PDFA_1A);
        try (Workspace workspace = Workspace.from(request)) {
            return check.check(workspace, level);
        }
    }
}
