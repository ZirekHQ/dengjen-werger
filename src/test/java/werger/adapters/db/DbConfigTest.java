package werger.adapters.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DbConfigTest {
    private static final Map<String, String> ALL_PRESENT = Map.of(
            "DB_HOST", "localhost",
            "DB_PORT", "5432",
            "DB_USER", "user",
            "DB_PASSWORD", "pass",
            "DB_NAME", "db");

    private static DbConfig fromMap(Map<String, String> env) {
        return DbConfig.fromEnv(name -> Optional.ofNullable(env.get(name)));
    }

    private static Map<String, String> with(String key, String value) {
        Map<String, String> env = new HashMap<>(ALL_PRESENT);
        if (value == null) {
            env.remove(key);
        } else {
            env.put(key, value);
        }
        return env;
    }

    @Test
    void fromEnvFailsWithNoSuchElementWhenARequiredVarIsMissing() {
        assertThatExceptionOfType(NoSuchElementException.class).isThrownBy(() -> fromMap(Map.of()));
    }

    @Test
    void fromEnvSucceedsAndParsesAllFieldsWhenEveryVarIsPresent() {
        assertThat(fromMap(ALL_PRESENT)).isEqualTo(new DbConfig("localhost", 5432, "user", "pass", "db"));
    }

    @Test
    void fromEnvDefaultsDbPortTo6543WhenUnset() {
        assertThat(fromMap(with("DB_PORT", null)).port()).isEqualTo(6543);
    }

    @Test
    void fromEnvFailsOnANonNumericDbPort() {
        assertThatExceptionOfType(NumberFormatException.class)
                .isThrownBy(() -> fromMap(with("DB_PORT", "not-a-number")));
    }

    @Test
    void jdbcUrlDisablesServerSidePreparedStatementsForSupavisor() {
        assertThat(fromMap(ALL_PRESENT).jdbcUrl()).isEqualTo("jdbc:postgresql://localhost:5432/db?prepareThreshold=0");
    }
}
