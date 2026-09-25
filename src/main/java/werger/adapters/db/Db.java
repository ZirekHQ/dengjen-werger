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

    // Reads env vars only when called, so a missing var surfaces as a normal exception at the call site, not a fatal
    // ExceptionInInitializerError during class initialization.
    public static HikariDataSource pooledFromEnv() {
        return pooled(DbConfig.fromEnv(name -> Optional.ofNullable(System.getenv(name))));
    }
}
