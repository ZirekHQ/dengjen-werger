package werger.adapters.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class DbPoolingIT {
    // Skips instead of failing when Postgres creds are absent — this integration
    // test may run without them locally or on a fork PR. On a fork PR, GitHub
    // passes repository secrets as empty strings rather than leaving the variables
    // unset, so this checks for a non-empty value rather than mere presence.
    private static boolean hasCredentials() {
        return List.of("DB_HOST", "DB_USER", "DB_PASSWORD", "DB_NAME").stream().allMatch(name -> {
            String value = System.getenv(name);
            return value != null && !value.isEmpty();
        });
    }

    @Test
    void runningTheSameQueryManyTimesOverASmallPoolDoesntHitAStalePreparedStatement() throws Exception {
        assumeTrue(
                hasCredentials(), "set DB_HOST, DB_PORT, DB_USER, DB_PASSWORD, DB_NAME to run against real Supabase");
        try (HikariDataSource pool = Db.pooledFromEnv();
                ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            JdbcTemplate jdbc = new JdbcTemplate(pool);
            // A parameterized query goes through a PreparedStatement, and pgJDBC would promote it to a named
            // server-side statement after five executions on one connection without prepareThreshold=0.
            Callable<Integer> query = () -> {
                int last = 0;
                for (int run = 0; run < 10; run++) {
                    last = jdbc.queryForObject("select ?::int", Integer.class, 1);
                }
                return last;
            };
            List<Future<Integer>> results = executor.invokeAll(
                    IntStream.range(0, 20).mapToObj(i -> query).toList());
            for (Future<Integer> result : results) {
                assertThat(result.get()).isEqualTo(1);
            }
        }
    }
}
