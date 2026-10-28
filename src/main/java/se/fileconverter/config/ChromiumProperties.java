package se.fileconverter.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Chromium för HTML-mallar som behöver flexbox, grid eller JavaScript (se {@code EngineSelector}).
 *
 * @param enabled          av = alla mallar renderas med openhtmltopdf, och mallar som kräver Chromium får 503
 * @param path             sökväg till chrome-headless-shell eller chrome; tomt = leta på kända platser
 * @param sandbox          Chromiums egen sandlåda. Kräver user namespaces, som de flesta containrar saknar;
 *                         isoleringen sköts då av containern (seccomp, ingen nätverkstrafik, icke-root)
 * @param maxConcurrent    samtidiga Chromium-sidor per instans
 * @param queueTimeout     hur länge ett anrop väntar på en plats innan det får 503 BUSY
 * @param jobTimeout       tak för en hel rendering, inklusive waitDelay och waitForExpression
 * @param maxWaitDelay     tak för Gotenbergs waitDelay
 * @param restartAfterJobs webbläsaren startas om efter så många renderingar, mot minnesläckor
 */
@ConfigurationProperties("converter.chromium")
public record ChromiumProperties(
        boolean enabled,
        String path,
        boolean sandbox,
        int maxConcurrent,
        Duration queueTimeout,
        Duration jobTimeout,
        Duration maxWaitDelay,
        int restartAfterJobs) {
}
