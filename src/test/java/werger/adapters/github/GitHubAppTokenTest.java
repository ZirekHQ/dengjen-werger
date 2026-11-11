package werger.adapters.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GitHubAppTokenTest {
    private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");
    private static final String TOKENS = "https://api.github.com/app/installations/42/access_tokens";
    private static final KeyPair KEYS = keys();

    private static KeyPair keys() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class MovableClock extends Clock {
        Instant now = NOW;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder().encodeToString(der) + "\n-----END " + type
                + "-----\n";
    }

    /** The PKCS#1 key inside Java's PKCS#8 encoding: after the version (3 bytes) and algorithm (15 bytes). */
    private static byte[] pkcs1() {
        byte[] pkcs8 = KEYS.getPrivate().getEncoded();
        int outerHeader = 2 + (pkcs8[1] & 0x7f);
        int octetAt = outerHeader + 3 + 15;
        int lengthOctets = pkcs8[octetAt + 1] & 0x7f;
        return Arrays.copyOfRange(pkcs8, octetAt + 2 + lengthOctets, pkcs8.length);
    }

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server =
            MockRestServiceServer.bindTo(builder).build();
    private final MovableClock clock = new MovableClock();

    private GitHubAppToken token() {
        return new GitHubAppToken(builder.build(), "1234", 42, pem("RSA PRIVATE KEY", pkcs1()), clock);
    }

    private void expectToken(String token, Instant expiresAt) {
        server.expect(once(), requestTo(TOKENS))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", org.hamcrest.Matchers.startsWith("Bearer ey")))
                .andRespond(withSuccess(
                        "{\"token\":\"" + token + "\",\"expires_at\":\"" + expiresAt + "\"}",
                        MediaType.APPLICATION_JSON));
    }

    @Test
    void aPkcs1KeyAsGitHubIssuesItIsWrappedIntoTheSameKeyJavaEncodes() {
        assertThat(GitHubAppToken.wrapPkcs1(pkcs1()))
                .isEqualTo(KEYS.getPrivate().getEncoded());
        assertThat(GitHubAppToken.privateKey(
                        pem("PRIVATE KEY", KEYS.getPrivate().getEncoded())))
                .isEqualTo(KEYS.getPrivate());
    }

    @Test
    void aShortKeyBodyGetsAOneByteLength() {
        assertThat(GitHubAppToken.wrapPkcs1(new byte[] {0x05}))
                .startsWith(0x30, 0x15, 0x02, 0x01, 0x00)
                .endsWith(0x04, 0x01, 0x05);
    }

    @Test
    void theJwtIsSignedWithTheAppKeyAndNamesTheApp() throws Exception {
        String[] parts = token().jwt().split("\\.");

        String claims = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertThat(claims)
                .isEqualTo("{\"iat\":" + (NOW.getEpochSecond() - 60) + ",\"exp\":" + (NOW.getEpochSecond() + 540)
                        + ",\"iss\":\"1234\"}");
        Signature rsa = Signature.getInstance("SHA256withRSA");
        rsa.initVerify(KEYS.getPublic());
        rsa.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(rsa.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
    }

    @Test
    void anInstallationTokenIsReusedUntilFiveMinutesBeforeItExpires() {
        GitHubAppToken token = token();
        expectToken("first", NOW.plus(Duration.ofHours(1)));

        assertThat(token.get()).isEqualTo("first");
        clock.now = NOW.plus(Duration.ofMinutes(54));
        assertThat(token.get()).isEqualTo("first");
        server.verify();

        server.reset();
        expectToken("second", NOW.plus(Duration.ofHours(2)));
        clock.now = NOW.plus(Duration.ofMinutes(55));
        assertThat(token.get()).isEqualTo("second");
        server.verify();
    }
}
