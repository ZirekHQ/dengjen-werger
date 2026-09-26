package werger.adapters.db;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Function;

public record DbConfig(String host, int port, String user, String password, String database) {
    private static String required(Function<String, Optional<String>> lookup, String name) {
        return lookup.apply(name).orElseThrow(() -> new NoSuchElementException("Missing required env var: " + name));
    }

    /** Reads connection settings through {@code lookup} so tests never have to touch the real environment. */
    public static DbConfig fromEnv(Function<String, Optional<String>> lookup) {
        return new DbConfig(
                required(lookup, "DB_HOST"),
                Integer.parseInt(lookup.apply("DB_PORT").orElse("6543")),
                required(lookup, "DB_USER"),
                required(lookup, "DB_PASSWORD"),
                required(lookup, "DB_NAME"));
    }

    /**
     * {@code prepareThreshold=0} stops pgjdbc from ever promoting a statement to a named server-side prepared
     * statement. Supavisor's transaction pooler (port 6543) hands out a backend connection per transaction, not per
     * session, so a named statement prepared on one backend can be missing or collide on the next.
     */
    public String jdbcUrl() {
        // An IPv6 literal needs brackets to be parsed as a host, and pgJDBC URL-decodes the database name.
        String urlHost = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        String urlDatabase = URLEncoder.encode(database, StandardCharsets.UTF_8);
        return "jdbc:postgresql://" + urlHost + ":" + port + "/" + urlDatabase + "?prepareThreshold=0";
    }
}
