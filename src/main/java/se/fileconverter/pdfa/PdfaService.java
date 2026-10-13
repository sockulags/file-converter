package se.fileconverter.pdfa;

import org.springframework.stereotype.Service;
import se.fileconverter.api.ConversionException;
import se.fileconverter.config.ConverterProperties;

/**
 * Normaliserar och validerar. Ingen fil lämnar tjänsten märkt som PDF/A utan att veraPDF har godkänt den,
 * om inte klienten uttryckligen har stängt av strikt läge.
 */
@Service
public class PdfaService {

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

    public Result convert(byte[] pdf, PdfaLevel level, Boolean strict) {
        byte[] normalized = normalizer.normalize(pdf, level);
        PdfaValidator.Report report = validator.validate(normalized, level);
        if (!report.compliant() && (strict != null ? strict : strictByDefault)) {
            throw ConversionException.unprocessable(ConversionException.Reason.PDFA_INVALID,
                    "Resultatet klarar inte " + level.label() + "-valideringen (veraPDF).", report.violations());
        }
        return new Result(normalized, report);
    }
}
