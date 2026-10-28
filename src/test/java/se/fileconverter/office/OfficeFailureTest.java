package se.fileconverter.office;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeoutException;
import org.jodconverter.core.office.OfficeException;
import org.junit.jupiter.api.Test;
import se.fileconverter.office.OfficeConverter.Failure;

/**
 * Bara fel i själva dokumentet får bli 422. Överlast, tidsgräns och krascher beror inte på filen.
 * Meddelandena och orsakerna är JODConverters (5.1), se AbstractOfficeWorkerPool och RunningTask.
 */
class OfficeFailureTest {

    @Test
    void fullPoolIsBusy() {
        assertThat(OfficeConverter.classify(new OfficeException("No office manager available after 30000 millisec")))
                .isEqualTo(Failure.BUSY);
        assertThat(OfficeConverter.classify(new OfficeException("The task queue is full (10 tasks waiting): task")))
                .isEqualTo(Failure.BUSY);
    }

    @Test
    void timeoutIsRecognisedByItsCause() {
        assertThat(OfficeConverter.classify(new OfficeException("Task did not complete within timeout (120000 ms): task",
                new TimeoutException()))).isEqualTo(Failure.TIMEOUT);
    }

    @Test
    void lostConnectionToSofficeIsACrash() {
        // Så här såg det ut när soffice.bin dödades mitt i en konvertering.
        assertThat(OfficeConverter.classify(new OfficeException("Could not apply filter org.jodconverter.local.filter.RefreshFilter.",
                new com.sun.star.uno.RuntimeException("JobQueue.removeJob - unexpected:java.lang.InterruptedException"))))
                .isEqualTo(Failure.CRASH);
        assertThat(OfficeConverter.classify(new OfficeException("Could not load document",
                new IllegalStateException(new com.sun.star.lang.DisposedException("java_remote_bridge is disposed")))))
                .isEqualTo(Failure.CRASH);
    }

    @Test
    void stoppedManagerIsUnavailable() {
        assertThat(OfficeConverter.classify(new OfficeException("Task was not executed, the office manager is stopped: task")))
                .isEqualTo(Failure.STOPPED);
    }

    @Test
    void everythingElseIsTheDocument() {
        assertThat(OfficeConverter.classify(new OfficeException("Could not load document: trasig.docx",
                new com.sun.star.io.IOException("loadComponentFromURL returned an empty reference")))).isEqualTo(Failure.DOCUMENT);
        assertThat(OfficeConverter.classify(new OfficeException("Could not store document"))).isEqualTo(Failure.DOCUMENT);
    }
}
