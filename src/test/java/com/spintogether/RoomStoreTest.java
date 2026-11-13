package com.spintogether;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;

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

    @Test
    void removesRoomsAfterTheirReconnectWindowExpires() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:room-expiry;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "sa",
            ""
        );
        dataSource.setDriverClassName("org.h2.Driver");
        RoomStore store = new RoomStore(new JdbcTemplate(dataSource));
        store.createTableIfNeeded();

        Instant now = Instant.now();
        store.create("OLD123", "owner-token", "{}", now.minusSeconds(1));
        store.create("LIVE12", "owner-token", "{}", now.plusSeconds(60));

        assertThat(store.find("OLD123")).isEmpty();
        assertThat(store.deleteExpiredRooms(now)).isEqualTo(1);
        assertThat(store.find("LIVE12")).isPresent();

        assertThat(store.extendLifetime("LIVE12", now.plusSeconds(120))).isTrue();
        assertThat(store.deleteExpiredRooms(now.plusSeconds(61))).isZero();
        assertThat(store.find("LIVE12")).isPresent();
    }
}
