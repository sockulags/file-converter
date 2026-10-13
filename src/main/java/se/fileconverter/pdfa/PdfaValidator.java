package se.fileconverter.pdfa;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider;
import org.verapdf.pdfa.Foundries;
import org.verapdf.pdfa.PDFAParser;
import org.verapdf.pdfa.PDFAValidator;
import org.verapdf.pdfa.results.TestAssertion;
import org.verapdf.pdfa.results.ValidationResult;

/**
 * Validerar mot veraPDF, referensvalidatorn för PDF/A. Körs i samma process.
 */
@Component
public class PdfaValidator {

    static {
        VeraGreenfieldFoundryProvider.initialise();
    }

    public record Report(PdfaLevel level, boolean compliant, List<String> violations) {
    }

    public Report validate(byte[] pdf, PdfaLevel level) {
        try (PDFAParser parser = Foundries.defaultInstance()
                        .createParser(new ByteArrayInputStream(pdf), level.flavour());
                PDFAValidator validator = Foundries.defaultInstance().createValidator(level.flavour(), false)) {
            ValidationResult result = validator.validate(parser);
            return new Report(level, result.isCompliant(), summarize(result));
        } catch (Exception e) {
            return new Report(level, false, List.of("veraPDF kunde inte läsa filen: " + e.getMessage()));
        }
    }

    /** En rad per brutet regel-id, inte en per förekomst. */
    private static List<String> summarize(ValidationResult result) {
        Map<String, String> byRule = new LinkedHashMap<>();
        for (TestAssertion assertion : result.getTestAssertions()) {
            if (assertion.getStatus() != TestAssertion.Status.FAILED) {
                continue;
            }
            String rule = assertion.getRuleId().getClause() + "-" + assertion.getRuleId().getTestNumber();
            String message = assertion.getErrorMessage() != null ? assertion.getErrorMessage() : assertion.getMessage();
            byRule.putIfAbsent(rule, rule + ": " + message);
        }
        return List.copyOf(byRule.values());
    }
}
