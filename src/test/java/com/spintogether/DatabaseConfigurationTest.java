package com.spintogether;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseConfigurationTest {
    @Test
    void convertsARenderStylePostgresUrlIntoJdbcSettings() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("DATABASE_URL", "postgresql://spin_user:secret%40value@database.internal:5432/spin_db?sslmode=require");

        HikariDataSource dataSource = (HikariDataSource) new DatabaseConfiguration().dataSource(environment);

        assertThat(dataSource.getJdbcUrl())
            .isEqualTo("jdbc:postgresql://database.internal:5432/spin_db?sslmode=require");
        assertThat(dataSource.getUsername()).isEqualTo("spin_user");
        assertThat(dataSource.getPassword()).isEqualTo("secret@value");
        assertThat(dataSource.getMaximumPoolSize()).isEqualTo(4);
        dataSource.close();
    }
}
