package se.fileconverter.config;

import java.lang.management.ManagementFactory;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

/**
 * Varnar vid start om heap och LibreOffice-processer tillsammans inte ryms i containerns minne.
 * Annars märks det först under last, när alla processer i poolen arbetar samtidigt och podden dödas
 * (OOMKilled).
 *
 * <p>Budgeten: max heap + poolstorlek × minne per process + ett fast tillägg för JVM:ens övriga minne
 * (metaspace, trådar, direktbuffertar). JVM:en läser containerns gräns från cgroup; utan gräns är det
 * maskinens minne. Se docs/kapacitet.md.
 */
@Component
public class MemoryBudget implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(MemoryBudget.class);

    /** JVM:ens minne utöver heapen. Grov uppskattning; mät med container_memory_working_set_bytes. */
    static final long JVM_OVERHEAD = DataSize.ofMegabytes(300).toBytes();
    static final long DEFAULT_PROCESS_MEMORY = DataSize.ofMegabytes(512).toBytes();

    private final ConverterProperties.Office office;

    public MemoryBudget(ConverterProperties properties) {
        this.office = properties.office();
    }

    @Override
    public void afterPropertiesSet() {
        if (office == null || !office.enabled()) {
            return;
        }
        long perProcess = office.processMemory() == null ? DEFAULT_PROCESS_MEMORY : office.processMemory().toBytes();
        long heap = Runtime.getRuntime().maxMemory();
        long available = availableMemory();
        log.info("Minnesbudget: heap {} MB + {} × LibreOffice {} MB + JVM {} MB = {} MB av {} MB, {} processorer",
                mb(heap), office.poolSize(), mb(perProcess), mb(JVM_OVERHEAD),
                mb(required(heap, office.poolSize(), perProcess)), mb(available),
                Runtime.getRuntime().availableProcessors());
        warning(heap, office.poolSize(), perProcess, available).ifPresent(log::warn);
    }

    static long required(long heap, int poolSize, long perProcess) {
        return heap + poolSize * perProcess + JVM_OVERHEAD;
    }

    static Optional<String> warning(long heap, int poolSize, long perProcess, long available) {
        long required = required(heap, poolSize, perProcess);
        if (available <= 0 || required <= available) {
            return Optional.empty();
        }
        return Optional.of(("Minnet räcker troligen inte: heap %d MB + %d × LibreOffice %d MB + JVM %d MB = %d MB, "
                + "men containern har %d MB. Höj minnesgränsen eller sänk CONVERTER_OFFICE_POOL_SIZE eller MaxRAMPercentage.")
                .formatted(mb(heap), poolSize, mb(perProcess), mb(JVM_OVERHEAD), mb(required), mb(available)));
    }

    /** Containerns minnesgräns (cgroup) om den finns, annars maskinens minne. */
    private static long availableMemory() {
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            return os.getTotalMemorySize();
        }
        return -1;
    }

    private static long mb(long bytes) {
        return bytes / (1024 * 1024);
    }
}
