package werger;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import werger.adapters.db.Db;
import werger.adapters.db.DbConfig;
import werger.adapters.db.PronunciationFixRepo;
import werger.adapters.db.UserRepo;
import werger.service.PronunciationFixService;

/** The one place that picks concrete adapters for the services the HTTP layer calls. */
@Configuration
public class AppWiring {
    /**
     * The database pool, configured from {@code DB_*} settings.
     *
     * @param environment Spring's view of environment variables and properties
     * @return the pool, closed by Spring on shutdown
     */
    @Bean(destroyMethod = "close")
    HikariDataSource dataSource(Environment environment) {
        return Db.lazyPooled(DbConfig.fromEnv(name -> Optional.ofNullable(environment.getProperty(name))));
    }

    /**
     * JDBC access over the pool.
     *
     * @param pool the database pool
     * @return the template
     */
    @Bean
    JdbcTemplate jdbcTemplate(HikariDataSource pool) {
        return new JdbcTemplate(pool);
    }

    /**
     * Transactions over the pool.
     *
     * @param pool the database pool
     * @return the template
     */
    @Bean
    TransactionTemplate transactionTemplate(HikariDataSource pool) {
        return new TransactionTemplate(new DataSourceTransactionManager(pool));
    }

    /**
     * The wall clock, in UTC.
     *
     * @return the clock
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Users by sign-in subject.
     *
     * @param jdbc JDBC access
     * @return the repository
     */
    @Bean
    UserRepo userRepo(JdbcTemplate jdbc) {
        return new UserRepo(jdbc);
    }

    /**
     * Pronunciation fixes and their reviews.
     *
     * @param jdbc JDBC access
     * @return the repository
     */
    @Bean
    PronunciationFixRepo pronunciationFixRepo(JdbcTemplate jdbc) {
        return new PronunciationFixRepo(jdbc);
    }

    /**
     * Submitting and reviewing pronunciation fixes.
     *
     * @param fixes the fix repository
     * @param transactions transactions over the pool
     * @param clock the wall clock
     * @return the service
     */
    @Bean
    PronunciationFixService pronunciationFixService(
            PronunciationFixRepo fixes, TransactionTemplate transactions, Clock clock) {
        return new PronunciationFixService(fixes, transactions, clock);
    }
}
