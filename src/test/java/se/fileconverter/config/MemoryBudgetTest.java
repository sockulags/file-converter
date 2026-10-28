package se.fileconverter.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class MemoryBudgetTest {

    private static final long MB = DataSize.ofMegabytes(1).toBytes();

    @Test
    void noWarningWhenEverythingFits() {
        // Manifestet i deploy/k8s/base: 3 GiB, heap 40 %, pool 2 × 512 MB.
        assertThat(MemoryBudget.warning(1228 * MB, 2, 512 * MB, 3072 * MB)).isEmpty();
    }

    @Test
    void warnsWhenThePoolDoesNotFit() {
        assertThat(MemoryBudget.warning(1228 * MB, 4, 512 * MB, 3072 * MB))
                .hasValueSatisfying(message -> assertThat(message).contains("3576 MB").contains("3072 MB"));
    }

    @Test
    void unknownLimitGivesNoWarning() {
        assertThat(MemoryBudget.warning(1228 * MB, 4, 512 * MB, -1)).isEmpty();
    }
}
