package se.fileconverter.pdfa;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import se.fileconverter.api.ConversionException;
import se.fileconverter.config.ConverterProperties;

/**
 * Normaliserar och validerar varje PDF/A-fil med veraPDF.
 *
 * <p>Två lägen, valda med {@code converter.pdfa.strict} eller per anrop med {@code pdfaStrict}:
 * <ul>
 *   <li><b>Bästa försök</b> (standard, som Gotenberg): allt som går rättas, och filen returneras även
 *       om något återstår. Svaret säger ärligt om filen klarar valideringen och vilken nivå den
 *       märktes med; en fil som inte kan bli 1a märks 1b i stället för att påstå något den inte är.</li>
 *   <li><b>Strikt</b>: en fil som inte klarar valideringen avvisas med 422 och en förklaring.</li>
 * </ul>
 */
@Service
public class PdfaService {

    /** Resultat och valideringsrapport. {@code report().level()} är nivån filen faktiskt märktes med. */
    public record Result(byte[] pdf, PdfaValidator.Report report) {
    }

    private final PdfaNormalizer normalizer;
    private final PdfaValidator validator;
    private final boolean strictByDefault;

    public PdfaService(PdfaNormalizer normalizer, PdfaValidator validator, ConverterProperties properties) {
        this.normalizer = normalizer;
        this.validator = validator;
        this.strictByDefault = properties.pdfa().strict();
    }

    public boolean isStrict(Boolean requested) {
        return requested != null ? requested : strictByDefault;
    }

    public Result convert(byte[] pdf, PdfaLevel level, Boolean strict) {
        boolean strictMode = isStrict(strict);
        PdfaNormalizer.Result normalized = normalizer.normalize(pdf, level, !strictMode);
        PdfaValidator.Report report = validator.validate(normalized.pdf(), normalized.level());
        if (!report.compliant() && strictMode) {
            throw ConversionException.unprocessable(ConversionException.Reason.PDFA_INVALID,
                    "Resultatet klarar inte " + level.label() + "-valideringen (veraPDF).", report.violations());
        }
        List<String> problems = new ArrayList<>(normalized.warnings());
        problems.addAll(report.violations());
        boolean compliant = report.compliant() && normalized.level() == level;
        return new Result(normalized.pdf(), new PdfaValidator.Report(normalized.level(), compliant, problems));
    }
}
