package werger.http;

import java.net.URI;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Accepts only requests carrying a sign-in token that Supabase Auth issued for this project. Supabase signs with the
 * project's asymmetric keys and publishes them at its JWKS endpoint, so the backend never holds a signing secret.
 */
@Configuration
public class SupabaseAuth {
    /** The audience Supabase puts on a signed-in user's token; anonymous and service tokens carry other roles. */
    static final String AUDIENCE = "authenticated";

    /** Paths served without a token. */
    private static final String[] PUBLIC_PATHS = {"/healthz", "/", "/index.html", "/app.js", "/app.css", "/page-config"
    };

    /**
     * Every route needs a valid token except the health check, which the platform probes without one, and the page
     * itself with its sign-in settings, which a visitor loads before signing in.
     *
     * @param http the security builder Spring passes in
     * @return the filter chain
     * @throws Exception if the builder rejects the configuration
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(requests -> requests.requestMatchers(PUBLIC_PATHS)
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(server -> server.jwt(Customizer.withDefaults()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
    }

    /**
     * Verifies tokens against the project's published keys, issuer and audience.
     *
     * @param projectUrl the Supabase project URL, such as {@code https://abcd.supabase.co}
     * @return the decoder Spring Security uses for bearer tokens
     */
    @Bean
    JwtDecoder jwtDecoder(@Value("${werger.auth.supabase-url}") String projectUrl) {
        return decoder(issuer(projectUrl));
    }

    /**
     * A decoder that fetches keys from the issuer's JWKS endpoint, as Supabase Auth publishes them.
     *
     * @param issuer the project's Auth issuer
     * @return the decoder
     */
    static JwtDecoder decoder(String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(issuer + "/.well-known/jwks.json")
                .jwsAlgorithms(algorithms -> {
                    algorithms.add(SignatureAlgorithm.ES256);
                    algorithms.add(SignatureAlgorithm.RS256);
                })
                .build();
        decoder.setJwtValidator(validator(issuer));
        return decoder;
    }

    /**
     * The issuer Supabase Auth writes into its tokens for this project.
     *
     * @param projectUrl the Supabase project URL, with or without a trailing slash
     * @return the project's Auth issuer
     * @throws IllegalArgumentException if the URL is blank or not an absolute https URL
     */
    static String issuer(String projectUrl) {
        String trimmed = projectUrl(projectUrl);
        URI uri = URI.create(trimmed);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("werger.auth.supabase-url must be an https URL, got: " + projectUrl);
        }
        return trimmed + "/auth/v1";
    }

    /**
     * The project URL without surrounding spaces or trailing slashes, so paths can be appended to it.
     *
     * @param projectUrl the configured Supabase project URL
     * @return the URL as the issuer and the page expect it
     */
    static String projectUrl(String projectUrl) {
        String trimmed = projectUrl.strip();
        int end = trimmed.length();
        while (end > 0 && trimmed.charAt(end - 1) == '/') {
            end--;
        }
        return trimmed.substring(0, end);
    }

    /**
     * Checks expiry, the issuer, and that the token was issued to a signed-in user.
     *
     * @param issuer the project's Auth issuer
     * @return the combined validator
     */
    static OAuth2TokenValidator<Jwt> validator(String issuer) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<List<String>>(
                        JwtClaimNames.AUD, audience -> audience != null && audience.contains(AUDIENCE)));
    }
}
