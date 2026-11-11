package werger.adapters.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Signs in as one installation of a GitHub App, so commits and pull requests are made by the App's bot rather than a
 * person. An installation token lasts an hour; it is reused until five minutes before it expires, then minted again
 * from a JWT signed with the App's private key.
 */
public class GitHubAppToken implements Supplier<String> {
    private static final Duration RENEW_BEFORE_EXPIRY = Duration.ofMinutes(5);
    private static final Duration LAST_USABLE_BEFORE_EXPIRY = Duration.ofMinutes(1);
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();
    // AlgorithmIdentifier { rsaEncryption, NULL }, the fixed prefix PKCS#8 puts around an RSA key.
    private static final byte[] RSA_ALGORITHM = {
        0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00
    };

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Installation(
            String token, @JsonProperty("expires_at") String expiresAt) {}

    private record Cached(String token, Instant expiresAt) {}

    private final RestClient http;
    private final String appId;
    private final long installationId;
    private final PrivateKey key;
    private final Clock clock;
    private Optional<Cached> cached = Optional.empty();

    /** Signs in as installation {@code installationId} of App {@code appId}, keeping time by {@code clock}. */
    public GitHubAppToken(RestClient http, String appId, long installationId, String privateKeyPem, Clock clock) {
        this.http = http;
        this.appId = appId;
        this.installationId = installationId;
        this.key = privateKey(privateKeyPem);
        this.clock = clock;
    }

    /** A short-lived installation token, reused while it has more than five minutes left. */
    @Override
    public synchronized String get() {
        Instant now = clock.instant();
        Cached current = cached.filter(token -> token.expiresAt().isAfter(now.plus(RENEW_BEFORE_EXPIRY)))
                .orElseGet(this::renew);
        cached = Optional.of(current);
        return current.token();
    }

    /**
     * Renewing early leaves room to fail: while GitHub cannot mint a new token, the old one is used for as long as one
     * request can still finish with it.
     */
    private Cached renew() {
        try {
            return mint();
        } catch (RestClientException e) {
            // Read the clock again: the failed call itself may have taken long enough to use up the old token.
            Instant failedAt = clock.instant();
            return cached.filter(token -> token.expiresAt().isAfter(failedAt.plus(LAST_USABLE_BEFORE_EXPIRY)))
                    .orElseThrow(() -> e);
        }
    }

    /** Exchanges a signed JWT for a new installation token. */
    private Cached mint() {
        Installation installation = http.post()
                .uri("https://api.github.com/app/installations/" + installationId + "/access_tokens")
                .headers(h -> {
                    h.setBearerAuth(jwt());
                    h.setAccept(List.of(MediaType.parseMediaType("application/vnd.github+json")));
                    h.set("X-GitHub-Api-Version", "2022-11-28");
                })
                .retrieve()
                .body(Installation.class);
        Objects.requireNonNull(installation);
        return new Cached(installation.token(), Instant.parse(installation.expiresAt()));
    }

    /** GitHub caps the JWT at ten minutes and accepts one issued a minute early, which absorbs clock drift. */
    String jwt() {
        long now = clock.instant().getEpochSecond();
        String header = encode("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        String claims = encode("{\"iat\":" + (now - 60) + ",\"exp\":" + (now + 540) + ",\"iss\":\"" + appId + "\"}");
        String signed = header + "." + claims;
        try {
            Signature rsa = Signature.getInstance("SHA256withRSA");
            rsa.initSign(key);
            rsa.update(signed.getBytes(StandardCharsets.US_ASCII));
            return signed + "." + BASE64URL.encodeToString(rsa.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot sign the GitHub App JWT", e);
        }
    }

    /** Base64url without padding, as JWT segments are written. */
    private static String encode(String json) {
        return BASE64URL.encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Reads the App's key as GitHub hands it out (PKCS#1, {@code BEGIN RSA PRIVATE KEY}) or as PKCS#8. Java reads only
     * PKCS#8, so a PKCS#1 key is wrapped first rather than asking for an openssl conversion.
     */
    static PrivateKey privateKey(String pem) {
        byte[] der = Base64.getMimeDecoder().decode(pem.replaceAll("-----[A-Z ]+-----", ""));
        byte[] pkcs8 = pem.contains("BEGIN RSA PRIVATE KEY") ? wrapPkcs1(der) : der;
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("the GitHub App private key is not an RSA key", e);
        }
    }

    /** PrivateKeyInfo ::= SEQUENCE { INTEGER 0, AlgorithmIdentifier, OCTET STRING containing the PKCS#1 key }. */
    static byte[] wrapPkcs1(byte[] pkcs1) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(new byte[] {0x02, 0x01, 0x00});
        body.writeBytes(RSA_ALGORITHM);
        body.writeBytes(tagged(0x04, pkcs1));
        return tagged(0x30, body.toByteArray());
    }

    /** A DER element: tag, length in short or long form, then content. */
    private static byte[] tagged(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int length = content.length;
        if (length < 0x80) {
            out.write(length);
        } else {
            int octets = (Integer.SIZE - Integer.numberOfLeadingZeros(length) + 7) / 8;
            out.write(0x80 | octets);
            for (int shift = (octets - 1) * 8; shift >= 0; shift -= 8) {
                out.write(length >>> shift);
            }
        }
        out.writeBytes(content);
        return out.toByteArray();
    }
}
