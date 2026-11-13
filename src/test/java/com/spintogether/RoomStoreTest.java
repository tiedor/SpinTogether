package com.spintogether;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class RoomStoreTest {
    @Test
    void keepsARoomAndItsLatestStateAfterTheStoreIsRecreated() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:room-store;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "sa",
            ""
        );
        dataSource.setDriverClassName("org.h2.Driver");

        RoomStore firstStore = new RoomStore(new JdbcTemplate(dataSource));
        firstStore.createTableIfNeeded();
        firstStore.create("ABC123", "owner-token", "{\"categories\":[]}");
        firstStore.save("ABC123", "{\"categories\":[{\"name\":\"Dinner\"}],\"history\":[]}");

        RoomStore afterRestart = new RoomStore(new JdbcTemplate(dataSource));
        afterRestart.createTableIfNeeded();

        assertThat(afterRestart.find("ABC123"))
            .contains(new RoomStore.RoomRecord(
                "ABC123",
                "owner-token",
                "{\"categories\":[{\"name\":\"Dinner\"}],\"history\":[]}"));
        assertThat(afterRestart.exists("MISSING")).isFalse();
    }
}
