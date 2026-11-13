package com.spintogether;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@Configuration
public class DatabaseConfiguration {
    @Bean
    DataSource dataSource(Environment environment) {
        String databaseUrl = firstNonBlank(
            environment.getProperty("JDBC_DATABASE_URL"),
            environment.getProperty("DATABASE_URL")
        );

        if (StringUtils.hasText(databaseUrl)) {
            return postgresDataSource(databaseUrl, environment);
        }

        if (StringUtils.hasText(environment.getProperty("RENDER_SERVICE_ID"))) {
            throw new IllegalStateException(
                "DATABASE_URL must be connected to the Render Postgres database before SpinTogether can start."
            );
        }

        return dataSource(
            "org.h2.Driver",
            "jdbc:h2:file:./data/spintogether;MODE=PostgreSQL;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1",
            "sa",
            ""
        );
    }

    private DataSource postgresDataSource(String databaseUrl, Environment environment) {
        String url = databaseUrl.trim();
        if (url.startsWith("jdbc:postgresql:")) {
            return dataSource(
                "org.postgresql.Driver",
                url,
                environment.getProperty("JDBC_DATABASE_USERNAME", ""),
                environment.getProperty("JDBC_DATABASE_PASSWORD", "")
            );
        }

        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("DATABASE_URL is not a valid Postgres connection URL.", exception);
        }
        if (!"postgres".equals(uri.getScheme()) && !"postgresql".equals(uri.getScheme())) {
            throw new IllegalStateException("DATABASE_URL must use the postgres:// or postgresql:// scheme.");
        }
        if (!StringUtils.hasText(uri.getHost()) || !StringUtils.hasText(uri.getRawPath())) {
            throw new IllegalStateException("DATABASE_URL is missing a database host or name.");
        }

        StringBuilder jdbcUrl = new StringBuilder("jdbc:postgresql://")
            .append(uri.getHost());
        if (uri.getPort() != -1) {
            jdbcUrl.append(':').append(uri.getPort());
        }
        jdbcUrl.append(uri.getRawPath());
        if (StringUtils.hasText(uri.getRawQuery())) {
            jdbcUrl.append('?').append(uri.getRawQuery());
        }

        String username = "";
        String password = "";
        if (StringUtils.hasText(uri.getRawUserInfo())) {
            String[] credentials = uri.getRawUserInfo().split(":", 2);
            username = decode(credentials[0]);
            if (credentials.length == 2) {
                password = decode(credentials[1]);
            }
        }

        return dataSource("org.postgresql.Driver", jdbcUrl.toString(), username, password);
    }

    private DataSource dataSource(String driverClassName, String jdbcUrl, String username, String password) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setDriverClassName(driverClassName);
        dataSource.setJdbcUrl(jdbcUrl);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setMaximumPoolSize(4);
        dataSource.setMinimumIdle(0);
        dataSource.setConnectionTimeout(5_000);
        dataSource.setValidationTimeout(2_000);
        return dataSource;
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private String firstNonBlank(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }
}
