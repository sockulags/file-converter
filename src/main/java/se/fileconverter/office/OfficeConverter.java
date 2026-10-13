package se.fileconverter.office;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jodconverter.core.document.DefaultDocumentFormatRegistry;
import org.jodconverter.core.document.DocumentFormat;
import org.jodconverter.core.office.OfficeException;
import org.jodconverter.core.office.OfficeManager;
import org.jodconverter.local.LocalConverter;
import org.jodconverter.local.office.LocalOfficeManager;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import se.fileconverter.api.ConversionException;
import se.fileconverter.config.ConverterProperties;

/**
 * Office-dokument till PDF via en pool av LibreOffice-processer (JODConverter).
 *
 * <p>Med PDF/A begärd exporterar LibreOffice som taggad PDF/A-2, inte PDF/A-1: i PDF/A-1-läge
 * kastar LibreOffice hela taggstrukturen så fort dokumentet innehåller transparens (t.ex. en PNG
 * med alfakanal), och veraPDF upptäcker inte det. {@link se.fileconverter.pdfa.PdfaNormalizer}
 * gör sedan om filen till PDF/A-1 med taggarna kvar.
 */
@Component
public class OfficeConverter implements InitializingBean, DisposableBean {

    private final ConverterProperties.Office config;
    private OfficeManager officeManager;

    public OfficeConverter(ConverterProperties properties) {
        this.config = properties.office();
    }

    @Override
    public void afterPropertiesSet() throws OfficeException {
        if (!config.enabled()) {
            return;
        }
        LocalOfficeManager.Builder builder = LocalOfficeManager.builder()
                .poolSize(config.poolSize())
                .taskExecutionTimeout(config.taskTimeout().toMillis())
                .taskQueueTimeout(config.queueTimeout().toMillis())
                .maxTasksPerProcess(config.maxTasksPerProcess());
        if (config.home() != null && !config.home().isBlank()) {
            builder.officeHome(config.home());
        }
        officeManager = builder.build();
        officeManager.start();
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
        return officeManager != null && officeManager.isRunning();
    }

    /**
     * @param input    filen, med filändelse som avgör format
     * @param pdfA     exportera som taggad PDF/A, underlag för PDF/A-1-normaliseringen
     */
    public byte[] toPdf(Path input, boolean pdfA) {
        if (officeManager == null) {
            throw new ConversionException(HttpStatus.SERVICE_UNAVAILABLE, "LibreOffice är inte aktiverat i den här instansen.");
        }
        String extension = extension(input);
        DocumentFormat inputFormat = DefaultDocumentFormatRegistry.getInstance().getFormatByExtension(extension);
        if (inputFormat == null) {
            throw ConversionException.badRequest("Filformatet '." + extension + "' stöds inte.");
        }
        Map<String, Object> filterData = new HashMap<>();
        if (pdfA) {
            filterData.put("SelectPdfVersion", 2);
            filterData.put("UseTaggedPDF", true);
        }
        Path output = input.resolveSibling(input.getFileName() + ".pdf");
        try {
            LocalConverter.builder()
                    .officeManager(officeManager)
                    .storeProperty("FilterData", filterData)
                    .build()
                    .convert(input.toFile())
                    .as(inputFormat)
                    .to(output.toFile())
                    .as(DefaultDocumentFormatRegistry.PDF)
                    .execute();
            return Files.readAllBytes(output);
        } catch (OfficeException e) {
            throw new ConversionException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "LibreOffice kunde inte konvertera '" + input.getFileName() + "'.", List.of(String.valueOf(e.getMessage())), e);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }
}
