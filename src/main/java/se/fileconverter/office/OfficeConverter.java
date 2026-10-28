package se.fileconverter.office;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.jodconverter.core.document.DefaultDocumentFormatRegistry;
import org.jodconverter.core.document.DocumentFormat;
import org.jodconverter.core.office.OfficeException;
import org.jodconverter.core.office.OfficeManager;
import org.jodconverter.local.LocalConverter;
import org.jodconverter.local.office.LocalOfficeManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import se.fileconverter.api.ConversionException;
import se.fileconverter.assets.AssetStore;
import se.fileconverter.config.ConverterProperties;
import se.fileconverter.pdfa.PdfaNormalizer;

/**
 * Office-dokument till PDF via en pool av LibreOffice-processer (JODConverter).
 *
 * <p>Med PDF/A begärd exporterar LibreOffice som taggad PDF/A-2, inte PDF/A-1: i PDF/A-1-läge
 * kastar LibreOffice hela taggstrukturen så fort dokumentet innehåller transparens (t.ex. en PNG
 * med alfakanal), och veraPDF upptäcker inte det. {@link se.fileconverter.pdfa.PdfaNormalizer}
 * gör sedan om filen till PDF/A-1 med taggarna kvar.
 *
 * <p>LibreOffice läser fonter via fontconfig när processen startar. Processerna startas därför med
 * en egen fontconfig-fil som tar med de uppladdade fonterna, och poolen startas om när de ändras.
 */
@Component
public class OfficeConverter implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(OfficeConverter.class);

    private final ConverterProperties.Office config;
    private final List<String> fontDirs;
    /** Konverteringar delar läslåset; omstart tar skrivlåset och väntar ut pågående jobb. */
    private final ReadWriteLock lock = new ReentrantReadWriteLock(true);
    private volatile OfficeManager officeManager;
    private Path fontconfigFile;
    private Path profileDir;

    /**
     * Användarprofil för LibreOffice. Dokument får inte hämta länkade resurser (bilder via http eller
     * file:), eftersom det låter en kundfil anropa interna tjänster eller bädda in lokala filer i
     * PDF:en. Makron är avstängda.
     */
    private static final String REGISTRY = """
            <?xml version="1.0" encoding="UTF-8"?>
            <oor:items xmlns:oor="http://openoffice.org/2001/registry" xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
              <item oor:path="/org.openoffice.Office.Common/Security/Scripting"><prop oor:name="BlockUntrustedRefererLinks" oor:op="fuse"><value>true</value></prop></item>
              <item oor:path="/org.openoffice.Office.Common/Security/Scripting"><prop oor:name="DisableMacrosExecution" oor:op="fuse"><value>true</value></prop></item>
              <item oor:path="/org.openoffice.Office.Common/Security/Scripting"><prop oor:name="MacroSecurityLevel" oor:op="fuse"><value>3</value></prop></item>
              <item oor:path="/org.openoffice.Office.Common/Misc"><prop oor:name="UseLocking" oor:op="fuse"><value>false</value></prop></item>
            </oor:items>
            """;

    public OfficeConverter(ConverterProperties properties, AssetStore store) {
        this.config = properties.office();
        List<String> dirs = new ArrayList<>(properties.fonts().dirs());
        dirs.add(store.fontsDir().toString());
        this.fontDirs = List.copyOf(dirs);
    }

    @Override
    public void afterPropertiesSet() throws OfficeException {
        if (!config.enabled()) {
            return;
        }
        fontconfigFile = writeFontconfig();
        profileDir = writeProfile();
        officeManager = start();
    }

    @Override
    public void destroy() throws OfficeException {
        if (officeManager != null) {
            officeManager.stop();
        }
    }

    public boolean isEnabled() {
        return config.enabled();
    }

    public boolean isRunning() {
        OfficeManager manager = officeManager;
        return manager != null && manager.isRunning();
    }

    /** Startar om LibreOffice så att nya eller borttagna fonter syns. Väntar ut pågående konverteringar. */
    public void restart() {
        if (!config.enabled()) {
            return;
        }
        lock.writeLock().lock();
        try {
            log.info("Startar om LibreOffice för att läsa in ändrade fonter");
            try {
                officeManager.stop();
            } catch (OfficeException | RuntimeException e) {
                log.warn("LibreOffice kunde inte stoppas rent: {}", e.getMessage());  // Kan redan vara stoppad efter ett tidigare fel.
            }
            officeManager = start();
        } catch (OfficeException e) {
            throw new IllegalStateException("LibreOffice kunde inte startas om", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private OfficeManager start() throws OfficeException {
        LocalOfficeManager.Builder builder = LocalOfficeManager.builder()
                .poolSize(config.poolSize())
                .taskExecutionTimeout(config.taskTimeout().toMillis())
                .taskQueueTimeout(config.queueTimeout().toMillis())
                .maxTasksPerProcess(config.maxTasksPerProcess())
                // Annars returnerar start() innan processerna är uppe, och hälsokontrollen svarar för tidigt.
                .startFailFast(true)
                // JODConverter lägger dessa argument före soffice-kommandot.
                .runAsArgs("env", "FONTCONFIG_FILE=" + fontconfigFile)
                .templateProfileDir(profileDir.toFile());
        if (config.home() != null && !config.home().isBlank()) {
            builder.officeHome(config.home());
        }
        OfficeManager manager = builder.build();
        manager.start();
        return manager;
    }

    /** Systemets fontconfig plus fontkatalogerna, med egen cache eftersom systemets inte är skrivbar. */
    private Path writeFontconfig() {
        try {
            Path dir = Files.createTempDirectory("fontconfig-");
            StringBuilder xml = new StringBuilder("""
                    <?xml version="1.0"?>
                    <!DOCTYPE fontconfig SYSTEM "fonts.dtd">
                    <fontconfig>
                      <include ignore_missing="yes">/etc/fonts/fonts.conf</include>
                    """);
            for (String fontDir : fontDirs) {
                xml.append("  <dir>").append(escape(fontDir)).append("</dir>\n");
            }
            xml.append("  <cachedir>").append(escape(dir.resolve("cache").toString())).append("</cachedir>\n");
            xml.append("</fontconfig>\n");
            Path file = dir.resolve("fonts.conf");
            Files.writeString(file, xml);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path writeProfile() {
        try {
            Path dir = Files.createTempDirectory("lo-profile-");
            Path user = Files.createDirectories(dir.resolve("user"));
            Files.writeString(user.resolve("registrymodifications.xcu"), REGISTRY);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public byte[] toPdf(Path input, boolean pdfA) {
        return toPdf(input, OfficeOptions.defaults(pdfA));
    }

    /** @param input filen, med filändelse som avgör format */
    public byte[] toPdf(Path input, OfficeOptions options) {
        if (!config.enabled()) {
            throw new ConversionException(HttpStatus.SERVICE_UNAVAILABLE, ConversionException.Reason.UNAVAILABLE,
                    "LibreOffice är inte aktiverat i den här instansen.", List.of(), null);
        }
        String extension = extension(input);
        DocumentFormat inputFormat = DefaultDocumentFormatRegistry.getInstance().getFormatByExtension(extension);
        if (inputFormat == null) {
            throw ConversionException.badRequest(ConversionException.Reason.UNSUPPORTED_FORMAT, "Filformatet '." + extension + "' stöds inte.");
        }
        if (!options.pdfA()) {
            return export(input, inputFormat, options, new HashMap<>(options.filterData()));
        }
        Map<String, Object> filterData = new HashMap<>(options.filterData());
        filterData.put("SelectPdfVersion", 2);
        filterData.put("UseTaggedPDF", true);
        byte[] pdf = export(input, inputFormat, options, filterData);
        if (PdfaNormalizer.hasTransparency(pdf)) {
            // PDF/A-1 tillåter ingen transparens. LibreOffice plattar själv ut den i PDF/A-1-läge, men tar
            // då bort taggarna: resultatet kan bli PDF/A-1b men inte 1a. Bättre än en ogiltig fil.
            log.info("{} innehåller transparens; exporterar om i PDF/A-1-läge, som plattar ut den", input.getFileName());
            filterData.put("SelectPdfVersion", 1);
            pdf = export(input, inputFormat, options, filterData);
        }
        return pdf;
    }

    /**
     * Varför en konvertering i LibreOffice misslyckades. Bara {@link #DOCUMENT} beror på filen; övriga
     * beror på last eller miljön och ger 503, så att klienten kan försöka igen.
     */
    enum Failure { BUSY, TIMEOUT, CRASH, STOPPED, DOCUMENT }

    static Failure classify(OfficeException e) {
        String message = String.valueOf(e.getMessage());
        if (message.startsWith("No office manager available") || message.startsWith("The task queue is full")) {
            return Failure.BUSY;
        }
        if (message.startsWith("Task was not executed, the office manager is stopped")) {
            return Failure.STOPPED;
        }
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException) {
                return Failure.TIMEOUT;
            }
            // UNO-fel på bryggan (t.ex. DisposedException) betyder att soffice-processen dog under jobbet.
            // Fel i själva dokumentet kommer som OfficeException utan UNO-orsak eller som com.sun.star.io-fel.
            if (cause instanceof com.sun.star.uno.RuntimeException) {
                return Failure.CRASH;
            }
        }
        return Failure.DOCUMENT;
    }

    /** Kraschar LibreOffice görs ett nytt försök, eftersom JODConverter då startar en ny process. */
    private byte[] export(Path input, DocumentFormat inputFormat, OfficeOptions options, Map<String, Object> filterData) {
        for (int attempt = 1; ; attempt++) {
            try {
                return exportOnce(input, inputFormat, options, filterData);
            } catch (OfficeException e) {
                Failure failure = classify(e);
                if (failure == Failure.CRASH && attempt == 1) {
                    log.warn("LibreOffice kraschade under konverteringen av {}; försöker igen med en ny process", input.getFileName());
                    continue;
                }
                throw failed(failure, input, e);
            }
        }
    }

    private ConversionException failed(Failure failure, Path input, OfficeException e) {
        List<String> details = List.of(String.valueOf(e.getMessage()));
        return switch (failure) {
            case BUSY -> new ConversionException(HttpStatus.SERVICE_UNAVAILABLE, ConversionException.Reason.BUSY,
                    "Alla LibreOffice-processer är upptagna. Försök igen om en stund.", details, e);
            case TIMEOUT -> new ConversionException(HttpStatus.SERVICE_UNAVAILABLE, ConversionException.Reason.LIBREOFFICE_TIMEOUT,
                    "LibreOffice blev inte klart med '" + input.getFileName() + "' inom " + config.taskTimeout().toSeconds() + " s.",
                    details, e);
            case CRASH -> new ConversionException(HttpStatus.SERVICE_UNAVAILABLE, ConversionException.Reason.LIBREOFFICE_CRASHED,
                    "LibreOffice kraschade två gånger under konverteringen av '" + input.getFileName() + "'.", details, e);
            case STOPPED -> new ConversionException(HttpStatus.SERVICE_UNAVAILABLE, ConversionException.Reason.UNAVAILABLE,
                    "LibreOffice startas om. Försök igen om en stund.", details, e);
            case DOCUMENT -> new ConversionException(HttpStatus.UNPROCESSABLE_CONTENT, ConversionException.Reason.LIBREOFFICE_FAILED,
                    "LibreOffice kunde inte konvertera '" + input.getFileName() + "'.", details, e);
        };
    }

    private byte[] exportOnce(Path input, DocumentFormat inputFormat, OfficeOptions options, Map<String, Object> filterData)
            throws OfficeException {
        Path output = input.resolveSibling(input.getFileName() + ".pdf");
        lock.readLock().lock();
        try {
            LocalConverter.Builder converter = LocalConverter.builder()
                    .officeManager(officeManager)
                    // Uppdatera aldrig länkar (OLE, externa data) vid inläsning.
                    .loadProperty("UpdateDocMode", (short) 0)
                    .storeProperty("FilterData", filterData);
            if (options.password() != null) {
                converter.loadProperty("Password", options.password());
            }
            if (options.landscape()) {
                converter.filterChain(new LandscapeFilter());
            }
            converter.build()
                    .convert(input.toFile())
                    .as(inputFormat)
                    .to(output.toFile())
                    .as(DefaultDocumentFormatRegistry.PDF)
                    .execute();
            return Files.readAllBytes(output);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            lock.readLock().unlock();
        }
    }

    private static String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }
}
