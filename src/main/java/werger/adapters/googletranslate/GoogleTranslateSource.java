package werger.adapters.googletranslate;

import java.util.List;
import java.util.Map;
import org.springframework.web.client.RestClientResponseException;
import werger.domain.Language;
import werger.ports.MachineTranslationSource;
import werger.ports.MtError;
import werger.ports.Result;

public class GoogleTranslateSource implements MachineTranslationSource {
    // Google Translate has covered Kurdish under the single code "ku" since adding it in 2016; verify against
    // Google's current supported-languages list before depending on this in production.
    public static final Map<String, String> KURMANJI_ONLY = Map.of("kmr", "ku");

    private final GoogleTranslateClient client;
    private final Map<String, String> languageCodes;

    public GoogleTranslateSource(GoogleTranslateClient client) {
        this(client, KURMANJI_ONLY);
    }

    public GoogleTranslateSource(GoogleTranslateClient client, Map<String, String> languageCodes) {
        this.client = client;
        this.languageCodes = languageCodes;
    }

    @Override
    public Result<MtError, Map<String, String>> translateBatch(Language language, List<String> sourceTexts) {
        String providerCode = languageCodes.get(language.code());
        if (providerCode == null) {
            return Result.err(
                    new MtError.Unsupported("No Google Translate code mapped for language " + language.code()));
        }
        try {
            return Result.ok(client.translate(sourceTexts, providerCode));
        } catch (RestClientResponseException e) {
            return Result.err(
                    e.getStatusCode().value() == 400
                            ? new MtError.Unsupported("Google Translate rejected the request: " + e.getStatusCode())
                            : new MtError.Transient("Google Translate returned " + e.getStatusCode()));
        } catch (UnexpectedResponseShapeException e) {
            return Result.err(new MtError.Unsupported(
                    "Google Translate response didn't match the expected shape: " + e.getMessage()));
        } catch (RuntimeException e) {
            return Result.err(new MtError.Transient(String.valueOf(e.getMessage())));
        }
    }
}
