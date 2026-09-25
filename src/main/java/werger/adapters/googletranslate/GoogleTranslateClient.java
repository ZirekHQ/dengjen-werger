package werger.adapters.googletranslate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Raw calls to Google Cloud Translation's v2 Basic API. Chunks so one logical {@code translate} call never sends more
 * than {@code chunkSize} strings or {@code maxRequestBytes} bytes of serialized request body in a single request
 * (Google's Basic API caps a request at 100,000 bytes — verify against Google's current docs before relying on this at
 * production volume), and keys results by input text rather than position so one failed chunk never discards another
 * chunk's already-succeeded translations. Authenticates with the v2 Basic API's API-key scheme (not v3's
 * OAuth2/service-account) — the caller supplies the key, expected to be sourced from a {@code GOOGLE_TRANSLATE_API_KEY}
 * environment variable once deployment wiring reads one.
 */
public class GoogleTranslateClient {
    private static final URI ENDPOINT = URI.create("https://translation.googleapis.com/language/translate/v2");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    // Google's v2 Basic API caps one request at 128 strings and 100,000 bytes of body.
    private static final int MAX_CHUNK_SIZE = 128;
    private static final int MAX_REQUEST_BYTES = 100_000;

    private final RestClient httpClient;
    private final String apiKey;
    private final int chunkSize;
    private final int maxRequestBytes;

    private record TranslateRequest(List<String> q, String target, String format) {}

    private record ChunkResult(List<String> chunk, List<String> translated, RuntimeException error) {}

    public GoogleTranslateClient(RestClient httpClient, String apiKey) {
        this(httpClient, apiKey, 100, 100_000);
    }

    public GoogleTranslateClient(RestClient httpClient, String apiKey, int chunkSize, int maxRequestBytes) {
        if (chunkSize < 1 || chunkSize > MAX_CHUNK_SIZE) {
            throw new IllegalArgumentException(
                    "chunkSize must be within [1, " + MAX_CHUNK_SIZE + "], got " + chunkSize);
        }
        if (maxRequestBytes < 1 || maxRequestBytes > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException(
                    "maxRequestBytes must be within [1, " + MAX_REQUEST_BYTES + "], got " + maxRequestBytes);
        }
        this.httpClient = httpClient;
        this.apiKey = apiKey;
        this.chunkSize = chunkSize;
        this.maxRequestBytes = maxRequestBytes;
    }

    public Map<String, String> translate(List<String> texts, String targetLanguageCode) {
        List<String> fitting = texts.stream()
                .filter(text -> requestBody(List.of(text), targetLanguageCode).length <= maxRequestBytes)
                .toList();
        List<ChunkResult> results = chunkByLimits(fitting, targetLanguageCode).stream()
                .map(chunk -> attemptChunk(chunk, targetLanguageCode))
                .toList();
        return collectResults(results);
    }

    private byte[] requestBody(List<String> chunk, String targetLanguageCode) {
        return JSON.writeValueAsString(new TranslateRequest(chunk, targetLanguageCode, "text"))
                .getBytes(StandardCharsets.UTF_8);
    }

    // A text whose own serialized request already exceeds maxRequestBytes can never fit any chunk; `translate` filters
    // these out before chunking so one oversized input doesn't affect every other text's batching.
    private List<List<String>> chunkByLimits(List<String> texts, String targetLanguageCode) {
        List<List<String>> chunks = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String text : texts) {
            List<String> candidate = new ArrayList<>(current);
            candidate.add(text);
            if (!current.isEmpty()
                    && (current.size() >= chunkSize
                            || requestBody(candidate, targetLanguageCode).length > maxRequestBytes)) {
                chunks.add(current);
                candidate = new ArrayList<>(List.of(text));
            }
            current = candidate;
        }
        if (!current.isEmpty()) {
            chunks.add(current);
        }
        return chunks;
    }

    private ChunkResult attemptChunk(List<String> chunk, String targetLanguageCode) {
        try {
            return new ChunkResult(chunk, translateChunk(chunk, targetLanguageCode), null);
        } catch (RuntimeException e) {
            return new ChunkResult(chunk, List.of(), e);
        }
    }

    private List<String> translateChunk(List<String> chunk, String targetLanguageCode) {
        String raw = httpClient
                .post()
                .uri(ENDPOINT)
                .header("X-goog-api-key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody(chunk, targetLanguageCode))
                .retrieve()
                .body(String.class);
        List<String> translated = parse(raw);
        if (translated.size() != chunk.size()) {
            throw new UnexpectedResponseShapeException(
                    "Google returned " + translated.size() + " translations for " + chunk.size() + " inputs", null);
        }
        return translated;
    }

    private static List<String> parse(String raw) {
        GoogleTranslateEnvelope envelope;
        try {
            envelope = JSON.readValue(Optional.ofNullable(raw).orElse(""), GoogleTranslateEnvelope.class);
        } catch (JacksonException e) {
            throw new UnexpectedResponseShapeException("Google Translate response isn't valid JSON", e);
        }
        if (envelope == null || envelope.data() == null || envelope.data().translations() == null) {
            throw new UnexpectedResponseShapeException("Google Translate response has no data.translations", null);
        }
        List<String> translated = new ArrayList<>();
        for (GoogleTranslation translation : envelope.data().translations()) {
            if (translation == null || translation.translatedText() == null) {
                throw new UnexpectedResponseShapeException(
                        "Google Translate response item has no translatedText", null);
            }
            translated.add(translation.translatedText());
        }
        return translated;
    }

    // Deliberately not fail-fast: that would fail the whole batch, discarding earlier successes, on one bad chunk.
    private static Map<String, String> collectResults(List<ChunkResult> results) {
        Map<String, String> succeeded = new LinkedHashMap<>();
        for (ChunkResult result : results) {
            if (result.error() == null) {
                for (int i = 0; i < result.chunk().size(); i++) {
                    succeeded.put(result.chunk().get(i), result.translated().get(i));
                }
            }
        }
        Optional<RuntimeException> firstError =
                results.stream().map(ChunkResult::error).filter(e -> e != null).findFirst();
        if (firstError.isPresent() && succeeded.isEmpty()) {
            throw firstError.get();
        }
        return succeeded;
    }
}
