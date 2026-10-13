package se.fileconverter.assets;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import se.fileconverter.config.ConverterProperties;
import se.fileconverter.html.FontRegistry;
import se.fileconverter.office.OfficeConverter;

/**
 * Laddar om fonter och alias när resurskatalogen ändras, oavsett vilken podd som ändrade den.
 */
@Component
public class AssetReloader implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AssetReloader.class);

    private final AssetStore store;
    private final FontRegistry fonts;
    private final OfficeConverter office;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "asset-reloader");
        thread.setDaemon(true);
        return thread;
    });
    private String fingerprint;

    public AssetReloader(AssetStore store, FontRegistry fonts, OfficeConverter office, ConverterProperties properties) {
        this.store = store;
        this.fonts = fonts;
        this.office = office;
        this.fingerprint = store.fingerprint();
        long interval = properties.assets().pollInterval().toMillis();
        scheduler.scheduleWithFixedDelay(this::reloadIfChanged, interval, interval, TimeUnit.MILLISECONDS);
    }

    /** Anropas direkt efter en ändring i administrationen, och regelbundet för ändringar från andra poddar. */
    public synchronized boolean reloadIfChanged() {
        try {
            String current = store.fingerprint();
            if (current.equals(fingerprint)) {
                return false;
            }
            fonts.reload();
            office.restart();
            // Först efter lyckad omladdning, så att ett misslyckat försök görs om vid nästa kontroll.
            fingerprint = current;
            return true;
        } catch (RuntimeException e) {
            log.error("Kunde inte ladda om resurser", e);
            return false;
        }
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
    }
}
