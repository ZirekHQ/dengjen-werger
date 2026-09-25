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
    // test may run without them locally or on a fork PR. A fork PR's ${{ secrets.X }}
    // resolves to "", not an unset var, so this checks non-empty rather than presence.
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
            Callable<Integer> query = () -> jdbc.queryForObject("select 1", Integer.class);
            List<Future<Integer>> results = executor.invokeAll(
                    IntStream.range(0, 20).mapToObj(i -> query).toList());
            for (Future<Integer> result : results) {
                assertThat(result.get()).isEqualTo(1);
            }
        }
    }
}
