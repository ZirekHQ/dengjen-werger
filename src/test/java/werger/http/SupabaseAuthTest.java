package werger.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class SupabaseAuthTest {
    private static final String ISSUER = "https://test-project.supabase.co/auth/v1";

    @Autowired
    private MockMvc mvc;

    private HttpServer jwks;

    /** Stops the local key server if a test started one. */
    @AfterEach
    void stopKeyServer() {
        if (jwks != null) {
            jwks.stop(0);
        }
    }

    @Test
    void aRequestWithoutATokenIsUnauthorized() throws Exception {
        mvc.perform(get("/anything")).andExpect(status().isUnauthorized());
    }

    @Test
    void aRequestWithAValidTokenGetsPastSignIn() throws Exception {
        mvc.perform(get("/anything").with(jwt())).andExpect(status().isNotFound());
    }

    @Test
    void theHealthCheckNeedsNoToken() throws Exception {
        mvc.perform(get("/healthz")).andExpect(status().isOk());
    }

    @Test
    void thePageAndItsSignInSettingsNeedNoToken() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk());
        mvc.perform(get("/app.js")).andExpect(status().isOk());
        mvc.perform(get("/app.css")).andExpect(status().isOk());
        mvc.perform(get("/page-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supabaseUrl").value("https://test-project.supabase.co"))
                .andExpect(jsonPath("$.publishableKey").value("test-publishable-key"));
    }

    @Test
    void theIssuerIsTheProjectsAuthPath() {
        assertThat(SupabaseAuth.issuer(" https://abcd.supabase.co/ ")).isEqualTo("https://abcd.supabase.co/auth/v1");
    }

    @Test
    void aProjectUrlThatIsNotHttpsIsRefused() {
        assertThatThrownBy(() -> SupabaseAuth.issuer("http://abcd.supabase.co"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SupabaseAuth.issuer("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSignedInUsersTokenIsValid() {
        assertThat(SupabaseAuth.validator(ISSUER)
                        .validate(token(ISSUER, List.of("authenticated")))
                        .hasErrors())
                .isFalse();
    }

    @Test
    void aTokenFromAnotherProjectIsInvalid() {
        assertThat(SupabaseAuth.validator(ISSUER)
                        .validate(token("https://other.supabase.co/auth/v1", List.of("authenticated")))
                        .hasErrors())
                .isTrue();
    }

    @Test
    void aTokenNotIssuedToASignedInUserIsInvalid() {
        assertThat(SupabaseAuth.validator(ISSUER)
                        .validate(token(ISSUER, List.of("anon")))
                        .hasErrors())
                .isTrue();
        assertThat(SupabaseAuth.validator(ISSUER).validate(token(ISSUER, null)).hasErrors())
                .isTrue();
    }

    @Test
    void theDecoderAcceptsATokenSignedWithThePublishedEs256Key() throws Exception {
        ECKey key = new ECKeyGenerator(Curve.P_256).keyID("k1").generate();
        String issuer = serveKeys(new JWKSet(key.toPublicJWK()));

        Jwt decoded = SupabaseAuth.decoder(issuer).decode(sign(key, issuer, "authenticated"));

        assertThat(decoded.getSubject()).isEqualTo("user-1");
    }

    @Test
    void theDecoderRefusesATokenSignedWithAnUnpublishedKey() throws Exception {
        ECKey published = new ECKeyGenerator(Curve.P_256).keyID("k1").generate();
        ECKey other = new ECKeyGenerator(Curve.P_256).keyID("k1").generate();
        String issuer = serveKeys(new JWKSet(published.toPublicJWK()));
        JwtDecoder decoder = SupabaseAuth.decoder(issuer);
        String forged = sign(other, issuer, "authenticated");

        assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(BadJwtException.class);
    }

    @Test
    void theDecoderRefusesAnAnonymousToken() throws Exception {
        ECKey key = new ECKeyGenerator(Curve.P_256).keyID("k1").generate();
        String issuer = serveKeys(new JWKSet(key.toPublicJWK()));
        JwtDecoder decoder = SupabaseAuth.decoder(issuer);
        String anonymous = sign(key, issuer, "anon");

        assertThatThrownBy(() -> decoder.decode(anonymous)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    void theConfiguredDecoderIsBuiltFromTheProjectUrl() {
        assertThat(new SupabaseAuth().jwtDecoder("https://abcd.supabase.co")).isNotNull();
    }

    /**
     * Serves {@code keys} at a local issuer's JWKS path, so the decoder fetches real keys without reaching Supabase.
     *
     * @param keys the key set to publish
     * @return the local issuer
     * @throws IOException if the server cannot start
     */
    private String serveKeys(JWKSet keys) throws IOException {
        byte[] body = keys.toString().getBytes(StandardCharsets.UTF_8);
        jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwks.createContext("/auth/v1/.well-known/jwks.json", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        jwks.start();
        return "http://127.0.0.1:" + jwks.getAddress().getPort() + "/auth/v1";
    }

    /**
     * A token for user-1 signed with {@code key}, as Supabase would issue it.
     *
     * @param key the signing key
     * @param issuer the iss claim
     * @param audience the aud claim
     * @return the serialized token
     * @throws JOSEException if signing fails
     */
    private static String sign(ECKey key, String issuer, String audience) throws JOSEException {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("user-1")
                .issuer(issuer)
                .audience(audience)
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new ECDSASigner(key));
        return jwt.serialize();
    }

    /**
     * A decoded token with the given issuer and audience, valid for the next five minutes.
     *
     * @param issuer the iss claim
     * @param audience the aud claim, or null to leave it out
     * @return the token
     */
    private static Jwt token(String issuer, List<String> audience) {
        Jwt.Builder builder = Jwt.withTokenValue("t")
                .header("alg", "ES256")
                .subject("user-1")
                .issuer(issuer)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
        if (audience != null) {
            builder.audience(audience);
        }
        return builder.build();
    }
}
