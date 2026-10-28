package se.fileconverter.chromium;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import se.fileconverter.api.ConversionException;
import se.fileconverter.assets.AssetStore;
import se.fileconverter.config.ChromiumProperties;
import se.fileconverter.config.ConverterProperties;
import se.fileconverter.html.FontRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * HTML-mallar som kräver en riktig webbläsare (flexbox, grid, JavaScript) renderas med headless
 * Chromium via DevTools-protokollet, som Gotenbergs Chromium-route.
 *
 * <p>Isolering: mallen öppnas under en påhittad adress ({@value #ORIGIN}) och varje begäran från
 * sidan fångas upp. Filer som skickats med i anropet besvaras direkt från arbetskatalogen; allt annat
 * (andra adresser, {@code file:}) stoppas. En sida på en http-adress får inte heller läsa {@code file:}.
 * Utöver det pekar alla värdnamn på ingenting och en obefintlig proxy är satt, så att inte ens
 * WebSockets når nätverket. Varje anrop får en egen inkognitokontext som slängs efteråt.
 *
 * <p>Fonterna är desamma som för LibreOffice och openhtmltopdf: systemets, de uppladdade och alias
 * (t.ex. Calibri → Carlito) via en egen fontconfig-fil. Webbläsaren startas om när de ändras.
 */
@Component
public class ChromiumConverter implements InitializingBean, DisposableBean {

    static final String ORIGIN = "http://mall.file-converter.invalid";
    private static final Logger log = LoggerFactory.getLogger(ChromiumConverter.class);
    private static final Pattern DEVTOOLS_URL = Pattern.compile("DevTools listening on (ws://\\S+)");
    private static final List<String> KNOWN_PATHS = List.of(
            "/opt/chrome-headless-shell/chrome-headless-shell", "/usr/bin/chrome-headless-shell",
            "/usr/bin/google-chrome", "/usr/bin/chromium", "/usr/bin/chromium-browser");
    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"), Map.entry("htm", "text/html; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"), Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"), Map.entry("json", "application/json"),
            Map.entry("svg", "image/svg+xml"), Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"), Map.entry("gif", "image/gif"), Map.entry("webp", "image/webp"),
            Map.entry("ttf", "font/ttf"), Map.entry("otf", "font/otf"), Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"), Map.entry("txt", "text/plain; charset=utf-8"));

    /**
     * En rendering. Mått i tum, som Page.printToPDF.
     *
     * @param pageRanges                Gotenbergs nativePageRanges, t.ex. "1-3,5"
     * @param tagged                    taggad PDF (strukturträd), underlag för PDF/A a-nivåerna
     * @param waitDelay                 väntan efter att sidan laddats, begränsas av max-wait-delay
     * @param mediaType                 "print" eller "screen"
     * @param mediaFeatures             Gotenbergs emulatedMediaFeatures, t.ex. prefers-color-scheme
     * @param failOnResourceStatusCodes statuskoder som ska ge fel; X99 betyder hela X00-serien
     */
    public record Options(double paperWidth, double paperHeight, double marginTop, double marginRight,
            double marginBottom, double marginLeft, boolean landscape, boolean preferCssPageSize,
            boolean printBackground, boolean omitBackground, double scale, String pageRanges, boolean outline,
            boolean tagged, boolean singlePage, Duration waitDelay, String waitForExpression,
            String waitForSelector, String mediaType, List<Map<String, String>> mediaFeatures, String userAgent,
            boolean failOnConsoleExceptions, boolean failOnResourceLoadingFailed,
            List<Integer> failOnResourceStatusCodes, boolean skipNetworkIdle) {
    }

    private final ChromiumProperties config;
    private final List<String> fontDirs;
    private final FontRegistry fonts;
    private final Semaphore pages;
    private final ReadWriteLock lock = new ReentrantReadWriteLock(true);
    private final AtomicInteger jobs = new AtomicInteger();
    private final ExecutorService events = Executors.newVirtualThreadPerTaskExecutor();
    private volatile Browser browser;
    private String binary;

    private record Browser(Process process, CdpClient client, Path userData, String version) {
    }

    public ChromiumConverter(ChromiumProperties config, ConverterProperties properties, AssetStore store, FontRegistry fonts) {
        this.config = config;
        List<String> dirs = new ArrayList<>(properties.fonts().dirs());
        dirs.add(store.fontsDir().toString());
        this.fontDirs = List.copyOf(dirs);
        this.fonts = fonts;
        this.pages = new Semaphore(Math.max(1, config.maxConcurrent()), true);
    }

    @Override
    public void afterPropertiesSet() {
        if (!config.enabled()) {
            return;
        }
        binary = findBinary(config.path()).orElse(null);
        if (binary == null) {
            log.warn("Chromium hittades inte (converter.chromium.path eller {}). Mallar som kräver Chromium får 503.", KNOWN_PATHS);
            return;
        }
        try {
            browser = start();
            log.info("Chromium {} startad ({})", browser.version(), binary);
        } catch (IOException e) {
            log.error("Chromium kunde inte startas: {}", e.getMessage());
        }
    }

    @Override
    public void destroy() {
        stop(browser);
        events.shutdownNow();
    }

    /** "up", "down", "disabled" eller "missing", för hälsokontrollen. */
    public String status() {
        if (!config.enabled()) {
            return "disabled";
        }
        if (binary == null) {
            return "missing";
        }
        Browser current = browser;
        return current != null && current.process().isAlive() && current.client().isOpen() ? "up" : "down";
    }

    /** Webbläsarens version, t.ex. "HeadlessChrome/153.0.8010.12", eller null om den inte är igång. */
    public String version() {
        Browser current = browser;
        return current == null ? null : current.version();
    }

    public boolean isAvailable() {
        return config.enabled() && binary != null;
    }

    /** Startar om webbläsaren så att nya fonter och alias syns. Väntar ut pågående renderingar. */
    public void restart() {
        if (!isAvailable()) {
            return;
        }
        lock.writeLock().lock();
        try {
            stop(browser);
            browser = start();
            jobs.set(0);
        } catch (IOException e) {
            browser = null;
            log.error("Chromium kunde inte startas om: {}", e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public byte[] toPdf(Path workDir, Options options) {
        if (!isAvailable()) {
            throw new ConversionException(HttpStatus.SERVICE_UNAVAILABLE, ConversionException.Reason.UNAVAILABLE,
                    "Mallen kräver Chromium, som inte finns i den här instansen.", List.of(), null);
        }
        if (!Files.isRegularFile(workDir.resolve("index.html"))) {
            throw ConversionException.badRequest("Filen index.html saknas i anropet.");
        }
        try {
            if (!pages.tryAcquire(config.queueTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                throw new ConversionException(HttpStatus.SERVICE_UNAVAILABLE, ConversionException.Reason.BUSY,
                        "Alla Chromium-sidor är upptagna. Försök igen om en stund.", List.of(), null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        try {
            for (int attempt = 1; ; attempt++) {
                Browser current = running();
                lock.readLock().lock();
                try {
                    return render(current, workDir, options);
                } catch (CdpClient.ChromiumTimeoutException e) {
                    throw new ConversionException(HttpStatus.SERVICE_UNAVAILABLE, ConversionException.Reason.CHROMIUM_TIMEOUT,
                            "Chromium blev inte klart med mallen inom " + config.jobTimeout().toSeconds() + " s.",
                            List.of(e.getMessage()), e);
                } catch (CdpClient.CdpException e) {
                    if (String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT).contains("page range")) {
                        throw ConversionException.badRequest(ConversionException.Reason.UNSUPPORTED_OPTION,
                                "nativePageRanges passar inte dokumentet: " + e.getMessage());
                    }
                    throw new ConversionException(HttpStatus.UNPROCESSABLE_CONTENT, ConversionException.Reason.CHROMIUM_FAILED,
                            "Chromium kunde inte rendera mallen.", List.of(e.getMessage()), e);
                } catch (IOException e) {
                    // Webbläsaren dog eller anslutningen bröts: ett nytt försök med en ny process.
                    if (attempt == 1) {
                        log.warn("Chromium tappades under renderingen ({}); försöker igen med en ny process", e.getMessage());
                        restartIfStill(current);
                        continue;
                    }
                    throw new ConversionException(HttpStatus.SERVICE_UNAVAILABLE, ConversionException.Reason.CHROMIUM_CRASHED,
                            "Chromium kraschade två gånger under renderingen.", List.of(String.valueOf(e.getMessage())), e);
                } finally {
                    lock.readLock().unlock();
                }
            }
        } finally {
            pages.release();
            if (jobs.incrementAndGet() >= config.restartAfterJobs() && lock.writeLock().tryLock()) {
                try {
                    stop(browser);
                    browser = null;  // startas vid nästa rendering
                    jobs.set(0);
                } finally {
                    lock.writeLock().unlock();
                }
            }
        }
    }

    private synchronized Browser running() {
        Browser current = browser;
        if (current != null && current.process().isAlive() && current.client().isOpen()) {
            return current;
        }
        stop(current);
        try {
            browser = start();
            return browser;
        } catch (IOException e) {
            browser = null;
            throw new ConversionException(HttpStatus.SERVICE_UNAVAILABLE, ConversionException.Reason.UNAVAILABLE,
                    "Chromium kunde inte startas.", List.of(e.getMessage()), e);
        }
    }

    private synchronized void restartIfStill(Browser dead) {
        if (browser == dead) {
            stop(dead);
            browser = null;
        }
    }

    private byte[] render(Browser current, Path workDir, Options options) throws IOException {
        CdpClient cdp = current.client();
        long deadline = System.nanoTime() + config.jobTimeout().toNanos();
        Path root = workDir.toRealPath();
        String contextId = cdp.send("Target.createBrowserContext", cdp.params().put("disposeOnDetach", true), null, remaining(deadline))
                .get("browserContextId").asString();
        List<String> blocked = Collections.synchronizedList(new ArrayList<>());
        List<String> failedStatus = Collections.synchronizedList(new ArrayList<>());
        List<String> exceptions = Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<Void> loaded = new CompletableFuture<>();
        CompletableFuture<Void> idle = new CompletableFuture<>();
        Runnable unsubscribe = () -> { };
        try {
            String targetId = cdp.send("Target.createTarget", cdp.params().put("url", "about:blank").put("browserContextId", contextId),
                    null, remaining(deadline)).get("targetId").asString();
            String session = cdp.send("Target.attachToTarget", cdp.params().put("targetId", targetId).put("flatten", true),
                    null, remaining(deadline)).get("sessionId").asString();
            unsubscribe = cdp.onEvent((eventSession, message) -> {
                if (!session.equals(eventSession)) {
                    return;
                }
                JsonNode params = message.path("params");
                switch (message.path("method").asString()) {
                    case "Fetch.requestPaused" -> events.execute(() -> serve(cdp, session, params, root, blocked, failedStatus, options));
                    case "Page.loadEventFired" -> loaded.complete(null);
                    case "Page.lifecycleEvent" -> {
                        if ("networkIdle".equals(params.path("name").asString())) {
                            idle.complete(null);
                        }
                    }
                    case "Runtime.exceptionThrown" -> exceptions.add(params.path("exceptionDetails").path("exception")
                            .path("description").asString(params.path("exceptionDetails").path("text").asString()));
                    default -> { }
                }
            });
            cdp.send("Page.enable", null, session, remaining(deadline));
            cdp.send("Runtime.enable", null, session, remaining(deadline));
            cdp.send("Page.setLifecycleEventsEnabled", cdp.params().put("enabled", true), session, remaining(deadline));
            ObjectNode fetch = cdp.params();
            fetch.putArray("patterns").addObject().put("urlPattern", "*").put("requestStage", "Request");
            cdp.send("Fetch.enable", fetch, session, remaining(deadline));

            ObjectNode media = cdp.params().put("media", options.mediaType());
            ArrayNode features = media.putArray("features");
            options.mediaFeatures().forEach(feature -> features.addObject()
                    .put("name", feature.getOrDefault("name", "")).put("value", feature.getOrDefault("value", "")));
            cdp.send("Emulation.setEmulatedMedia", media, session, remaining(deadline));
            if (options.userAgent() != null) {
                cdp.send("Emulation.setUserAgentOverride", cdp.params().put("userAgent", options.userAgent()), session, remaining(deadline));
            }
            if (options.omitBackground()) {
                ObjectNode color = cdp.params();
                color.putObject("color").put("r", 0).put("g", 0).put("b", 0).put("a", 0);
                cdp.send("Emulation.setDefaultBackgroundColorOverride", color, session, remaining(deadline));
            }

            JsonNode navigation = cdp.send("Page.navigate", cdp.params().put("url", ORIGIN + "/index.html"), session, remaining(deadline));
            if (navigation.has("errorText")) {
                throw new CdpClient.CdpException("Mallen kunde inte öppnas: " + navigation.get("errorText").asString());
            }
            await(loaded, deadline, "load");
            if (!options.skipNetworkIdle()) {
                // Allt besvaras lokalt, så nätverket blir snabbt stilla; vänta inte längre än nödvändigt.
                awaitQuietly(idle, Math.min(remaining(deadline).toMillis(), 5_000));
            }
            sleep(min(options.waitDelay(), config.maxWaitDelay()), deadline);
            if (options.waitForExpression() != null) {
                poll(cdp, session, "Boolean(" + options.waitForExpression() + ")", deadline, "waitForExpression");
            }
            if (options.waitForSelector() != null) {
                poll(cdp, session, "document.querySelector(" + CdpClient.JSON.writeValueAsString(options.waitForSelector()) + ") !== null",
                        deadline, "waitForSelector");
            }
            failOn(options, blocked, failedStatus, exceptions);

            double paperHeight = options.paperHeight();
            if (options.singlePage()) {
                JsonNode height = evaluate(cdp, session, "Math.max(document.documentElement.scrollHeight, document.body ? document.body.scrollHeight : 0)", deadline);
                paperHeight = height.asDouble() / 96 + options.marginTop() + options.marginBottom();
            }
            ObjectNode print = cdp.params()
                    .put("landscape", options.landscape())
                    .put("printBackground", options.printBackground())
                    .put("scale", options.scale())
                    .put("paperWidth", options.paperWidth())
                    .put("paperHeight", paperHeight)
                    .put("marginTop", options.marginTop())
                    .put("marginBottom", options.marginBottom())
                    .put("marginLeft", options.marginLeft())
                    .put("marginRight", options.marginRight())
                    .put("preferCSSPageSize", options.preferCssPageSize())
                    .put("generateTaggedPDF", options.tagged())
                    .put("generateDocumentOutline", options.outline());
            if (options.pageRanges() != null) {
                print.put("pageRanges", options.pageRanges());
            }
            Optional<String> header = read(root, "header.html");
            Optional<String> footer = read(root, "footer.html");
            if (header.isPresent() || footer.isPresent()) {
                print.put("displayHeaderFooter", true)
                        .put("headerTemplate", header.orElse("<span></span>"))
                        .put("footerTemplate", footer.orElse("<span></span>"));
            }
            JsonNode pdf = cdp.send("Page.printToPDF", print, session, remaining(deadline));
            return Base64.getDecoder().decode(pdf.get("data").asString());
        } finally {
            unsubscribe.run();
            try {
                cdp.send("Target.disposeBrowserContext", cdp.params().put("browserContextId", contextId), null, Duration.ofSeconds(5));
            } catch (IOException e) {
                log.debug("Kontexten kunde inte stängas: {}", e.getMessage());
            }
        }
    }

    /** Besvarar sidans begäran från arbetskatalogen, eller stoppar den. */
    private void serve(CdpClient cdp, String session, JsonNode params, Path root, List<String> blocked,
            List<String> failedStatus, Options options) {
        String requestId = params.path("requestId").asString();
        String url = params.path("request").path("url").asString();
        try {
            Optional<Path> file = localFile(url, root);
            if (file.isEmpty()) {
                if (!url.startsWith(ORIGIN + "/")) {
                    blocked.add(url);
                    cdp.sendAsync("Fetch.failRequest", cdp.params().put("requestId", requestId).put("errorReason", "BlockedByClient"), session);
                    return;
                }
                failedStatus.add("404 " + url);
                ObjectNode notFound = cdp.params().put("requestId", requestId).put("responseCode", 404);
                notFound.putArray("responseHeaders").addObject().put("name", "Content-Type").put("value", "text/plain");
                cdp.sendAsync("Fetch.fulfillRequest", notFound, session);
                return;
            }
            String name = file.get().getFileName().toString();
            String extension = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
            ObjectNode response = cdp.params().put("requestId", requestId).put("responseCode", 200)
                    .put("body", Base64.getEncoder().encodeToString(Files.readAllBytes(file.get())));
            response.putArray("responseHeaders").addObject().put("name", "Content-Type")
                    .put("value", CONTENT_TYPES.getOrDefault(extension, "application/octet-stream"));
            cdp.sendAsync("Fetch.fulfillRequest", response, session);
        } catch (IOException | RuntimeException e) {
            cdp.sendAsync("Fetch.failRequest", cdp.params().put("requestId", requestId).put("errorReason", "Failed"), session);
        }
    }

    /** En fil direkt i arbetskatalogen för en adress under {@value #ORIGIN}, annars tomt. */
    static Optional<Path> localFile(String url, Path root) {
        if (url == null || !url.startsWith(ORIGIN + "/")) {
            return Optional.empty();
        }
        try {
            String path = URI.create(url).getRawPath();
            String name = URLDecoder.decode(path.equals("/") ? "index.html" : path.substring(1), StandardCharsets.UTF_8);
            if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.startsWith(".")) {
                return Optional.empty();
            }
            Path target = root.resolve(name).normalize();
            return target.startsWith(root) && Files.isRegularFile(target) && target.getParent().equals(root)
                    ? Optional.of(target) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static void failOn(Options options, List<String> blocked, List<String> failedStatus, List<String> exceptions) {
        if (options.failOnConsoleExceptions() && !exceptions.isEmpty()) {
            throw new ConversionException(HttpStatus.CONFLICT, ConversionException.Reason.CONSOLE_EXCEPTION,
                    "Mallens JavaScript gav fel (failOnConsoleExceptions).", List.copyOf(exceptions), null);
        }
        List<String> failed = new ArrayList<>();
        if (options.failOnResourceLoadingFailed()) {
            blocked.forEach(url -> failed.add("Stoppad: " + url));
        }
        for (String status : failedStatus) {
            int code = Integer.parseInt(status.substring(0, 3));
            if (options.failOnResourceLoadingFailed() || options.failOnResourceStatusCodes().stream()
                    .anyMatch(c -> c == code || (c % 100 == 99 && c / 100 == code / 100))) {
                failed.add(status);
            }
        }
        if (!failed.isEmpty()) {
            throw new ConversionException(HttpStatus.CONFLICT, ConversionException.Reason.RESOURCE_FAILED,
                    "Mallen refererar resurser som inte kunde läsas.", failed, null);
        }
    }

    private static Optional<String> read(Path root, String name) throws IOException {
        Path file = root.resolve(name);
        return Files.isRegularFile(file) ? Optional.of(Files.readString(file)) : Optional.empty();
    }

    private JsonNode evaluate(CdpClient cdp, String session, String expression, long deadline) throws IOException {
        JsonNode result = cdp.send("Runtime.evaluate", cdp.params().put("expression", expression)
                .put("returnByValue", true).put("awaitPromise", true), session, remaining(deadline));
        if (result.has("exceptionDetails")) {
            throw new CdpClient.CdpException("Uttrycket gav fel: " + result.get("exceptionDetails").path("text").asString());
        }
        return result.path("result").path("value");
    }

    private void poll(CdpClient cdp, String session, String expression, long deadline, String field) throws IOException {
        while (true) {
            if (evaluate(cdp, session, expression, deadline).asBoolean(false)) {
                return;
            }
            if (remaining(deadline).toMillis() <= 100) {
                throw new CdpClient.ChromiumTimeoutException(field);
            }
            sleep(Duration.ofMillis(100), deadline);
        }
    }

    private static void await(CompletableFuture<Void> future, long deadline, String what) throws IOException {
        try {
            future.get(Math.max(1, remaining(deadline).toMillis()), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new CdpClient.ChromiumTimeoutException(what);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IOException(e.getCause());
        }
    }

    private static void awaitQuietly(CompletableFuture<Void> future, long millis) {
        try {
            future.get(Math.max(1, millis), TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // networkIdle kommer inte alltid, t.ex. med en timer som hämtar om något; då räcker load.
        }
    }

    private static void sleep(Duration duration, long deadline) throws IOException {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return;
        }
        if (duration.compareTo(remaining(deadline)) >= 0) {
            throw new CdpClient.ChromiumTimeoutException("waitDelay");
        }
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    private static Duration min(Duration a, Duration b) {
        return a == null ? Duration.ZERO : a.compareTo(b) > 0 ? b : a;
    }

    private static Duration remaining(long deadline) throws CdpClient.ChromiumTimeoutException {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) {
            throw new CdpClient.ChromiumTimeoutException("hela renderingen");
        }
        return Duration.ofNanos(nanos);
    }

    /** @param configured converter.chromium.path; tomt = leta på kända platser */
    public static Optional<String> findBinary(String configured) {
        if (configured != null && !configured.isBlank()) {
            return Files.isExecutable(Path.of(configured)) ? Optional.of(configured) : Optional.empty();
        }
        for (String candidate : KNOWN_PATHS) {
            if (Files.isExecutable(Path.of(candidate))) {
                return Optional.of(candidate);
            }
        }
        // Playwrights webbläsare (utvecklingsmiljöer och vissa CI-bilder).
        Path playwright = Path.of(System.getenv().getOrDefault("PLAYWRIGHT_BROWSERS_PATH", "/opt/pw-browsers"));
        if (Files.isDirectory(playwright)) {
            try (Stream<Path> paths = Files.walk(playwright, 3)) {
                return paths.filter(p -> p.getFileName().toString().equals("chrome-headless-shell") && Files.isExecutable(p))
                        .map(Path::toString).sorted(Comparator.reverseOrder()).findFirst();
            } catch (IOException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private Browser start() throws IOException {
        Path userData = Files.createTempDirectory("chromium-");
        Path fontconfig = writeFontconfig(userData);
        List<String> command = new ArrayList<>(List.of(binary,
                "--headless", "--remote-debugging-port=0", "--user-data-dir=" + userData,
                "--no-first-run", "--no-default-browser-check", "--disable-gpu", "--disable-dev-shm-usage",
                "--disable-extensions", "--disable-background-networking", "--disable-component-update",
                "--disable-default-apps", "--disable-sync", "--mute-audio", "--hide-scrollbars",
                // Samma utseende oavsett maskin: inga hintade glyfer, ingen subpixelkantutjämning, sRGB.
                "--font-render-hinting=none", "--disable-lcd-text", "--force-color-profile=srgb",
                // Inget nätverk: alla namn pekar på ingenting och all trafik går till en proxy som inte finns.
                "--host-resolver-rules=MAP * ~NOTFOUND", "--proxy-server=127.0.0.1:9"));
        if (!config.sandbox()) {
            command.add("--no-sandbox");
        }
        command.add("about:blank");
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("FONTCONFIG_FILE", fontconfig.toString());
        builder.environment().put("HOME", userData.toString());
        Process process = builder.start();
        CompletableFuture<String> endpoint = new CompletableFuture<>();
        // En vanlig tråd: läsningen blockerar så länge webbläsaren lever, och en virtuell tråd skulle då
        // låsa en bärartråd som andra virtuella trådar (t.ex. tidsgränserna) behöver.
        Thread.ofPlatform().daemon().name("chromium-logg").start(() -> {
            try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line = out.readLine(); line != null; line = out.readLine()) {
                    Matcher matcher = DEVTOOLS_URL.matcher(line);
                    if (matcher.find()) {
                        endpoint.complete(matcher.group(1));
                    } else {
                        log.debug("chromium: {}", line);
                    }
                }
            } catch (IOException ignored) {
                // Processen avslutades.
            }
            endpoint.completeExceptionally(new IOException("Chromium avslutades vid start (kod " + process.exitValue() + ")"));
        });
        try {
            String url = endpoint.get(30, TimeUnit.SECONDS);
            CdpClient client = CdpClient.connect(URI.create(url), Duration.ofSeconds(10));
            JsonNode version = client.send("Browser.getVersion", null, null, Duration.ofSeconds(10));
            return new Browser(process, client, userData, version.path("product").asString());
        } catch (Exception e) {
            process.destroyForcibly();
            delete(userData);
            throw e instanceof IOException io ? io : new IOException("Chromium startade inte: " + e.getMessage(), e);
        }
    }

    private void stop(Browser current) {
        if (current == null) {
            return;
        }
        current.client().close();
        current.process().descendants().forEach(ProcessHandle::destroyForcibly);
        current.process().destroyForcibly();
        try {
            current.process().waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        delete(current.userData());
    }

    /** Samma fonter som LibreOffice, plus alias från konfigurationen och administrationen. */
    private Path writeFontconfig(Path dir) throws IOException {
        StringBuilder xml = new StringBuilder("""
                <?xml version="1.0"?>
                <!DOCTYPE fontconfig SYSTEM "fonts.dtd">
                <fontconfig>
                  <include ignore_missing="yes">/etc/fonts/fonts.conf</include>
                """);
        for (String fontDir : fontDirs) {
            xml.append("  <dir>").append(escape(fontDir)).append("</dir>\n");
        }
        fonts.aliases().forEach((alias, target) -> xml.append("  <alias binding=\"same\"><family>").append(escape(alias))
                .append("</family><prefer><family>").append(escape(target)).append("</family></prefer></alias>\n"));
        xml.append("  <cachedir>").append(escape(dir.resolve("fontcache").toString())).append("</cachedir>\n");
        xml.append("</fontconfig>\n");
        Path file = dir.resolve("fonts.conf");
        Files.writeString(file, xml);
        return file;
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void delete(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException | UncheckedIOException ignored) {
            // Temporärkatalogen städas av operativsystemet om det här misslyckas.
        }
    }
}
