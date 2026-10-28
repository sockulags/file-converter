package se.fileconverter.assets;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import se.fileconverter.config.ConverterProperties;
import se.fileconverter.html.FontRegistry;
import se.fileconverter.chromium.ChromiumConverter;
import se.fileconverter.office.OfficeConverter;

/**
 * Laddar om fonter och alias när resurskatalogen ändras, oavsett vilken podd som ändrade den.
 *
 * <p>Poddar som upptäcker en ändring från en annan podd väntar en slumpad tid (högst
 * {@code converter.assets.reload-jitter}) innan de laddar om. Omladdningen startar om LibreOffice, och
 * poddarna ska inte vara ur trafik samtidigt. Podden där ändringen gjordes laddar om direkt.
 *
 * <p>Mätvärden: {@code converter_assets_reloads_total{result}} och
 * {@code converter_assets_last_reload_timestamp_seconds} (senaste lyckade omladdning, eller start).
 */
@Component
public class AssetReloader implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AssetReloader.class);

    private final AssetStore store;
    private final FontRegistry fonts;
    private final OfficeConverter office;
    private final ChromiumConverter chromium;
    private final FontAliasCheck aliases;
    private final MeterRegistry registry;
    private final long jitterMillis;
    private final AtomicLong lastReload = new AtomicLong(System.currentTimeMillis());
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "asset-reloader");
        thread.setDaemon(true);
        return thread;
    });
    private String fingerprint;
    private boolean scheduled;

    public AssetReloader(AssetStore store, FontRegistry fonts, OfficeConverter office, ChromiumConverter chromium,
            FontAliasCheck aliases, ConverterProperties properties, MeterRegistry registry) {
        this.store = store;
        this.fonts = fonts;
        this.office = office;
        this.chromium = chromium;
        this.aliases = aliases;
        this.registry = registry;
        this.fingerprint = store.fingerprint();
        Duration jitter = properties.assets().reloadJitter();
        this.jitterMillis = jitter == null ? 0 : jitter.toMillis();
        Gauge.builder("converter.assets.last.reload.timestamp", () -> lastReload.get() / 1000.0)
                .description("När fonter och alias senast lästes in (start eller lyckad omladdning)")
                .baseUnit("seconds").register(registry);
        long interval = properties.assets().pollInterval().toMillis();
        // Slumpad start, så att poddarna inte kontrollerar katalogen i takt.
        long initialDelay = interval + ThreadLocalRandom.current().nextLong(Math.max(1, interval));
        scheduler.scheduleWithFixedDelay(this::poll, initialDelay, interval, TimeUnit.MILLISECONDS);
    }

    /** Regelbunden kontroll: en ändring från en annan podd laddas in efter en slumpad väntan. */
    synchronized void poll() {
        if (scheduled || store.fingerprint().equals(fingerprint)) {
            return;
        }
        long delay = delay();
        log.info("Fonter eller alias har ändrats; laddar om om {} s", delay / 1000);
        scheduled = true;
        scheduler.schedule(() -> {
            synchronized (this) {
                scheduled = false;
            }
            reloadIfChanged();
        }, delay, TimeUnit.MILLISECONDS);
    }

    /** Väntan innan en ändring från en annan podd laddas in. */
    long delay() {
        return jitterMillis > 0 ? ThreadLocalRandom.current().nextLong(jitterMillis + 1) : 0;
    }

    /** Anropas direkt efter en ändring i administrationen, och efter väntan för ändringar från andra poddar. */
    public synchronized boolean reloadIfChanged() {
        try {
            String current = store.fingerprint();
            if (current.equals(fingerprint)) {
                return false;
            }
            fonts.reload();
            aliases.check();
            office.restart();
            chromium.restart();
            // Först efter lyckad omladdning, så att ett misslyckat försök görs om vid nästa kontroll.
            fingerprint = current;
            lastReload.set(System.currentTimeMillis());
            registry.counter("converter.assets.reloads", "result", "ok").increment();
            return true;
        } catch (RuntimeException e) {
            registry.counter("converter.assets.reloads", "result", "fel").increment();
            log.error("Kunde inte ladda om resurser", e);
            return false;
        }
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
    }
}
