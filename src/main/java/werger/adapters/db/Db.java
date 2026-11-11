package werger.adapters.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Optional;

public final class Db {
    private Db() {}

    public static HikariDataSource pooled(DbConfig config) {
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(config.jdbcUrl());
        hikari.setUsername(config.user());
        hikari.setPassword(config.password());
        hikari.setMaximumPoolSize(8);
        return new HikariDataSource(hikari);
    }

    /**
     * A pool that opens its first connection on first use, so the service starts, and answers its health check, even
     * while the database is unreachable.
     *
     * @param config where and as whom to connect
     * @return the pool
     */
    public static HikariDataSource lazyPooled(DbConfig config) {
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(config.jdbcUrl());
        pool.setUsername(config.user());
        pool.setPassword(config.password());
        pool.setMaximumPoolSize(8);
        return pool;
    }

    // Reads env vars only when called, so a missing var surfaces as a normal exception at the call site, not a fatal
    // ExceptionInInitializerError during class initialization.
    public static HikariDataSource pooledFromEnv() {
        return pooled(DbConfig.fromEnv(name -> Optional.ofNullable(System.getenv(name))));
    }
}
