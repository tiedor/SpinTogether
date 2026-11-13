package com.spintogether;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class RoomStore {
    private final JdbcTemplate jdbc;

    public RoomStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void createTableIfNeeded() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS live_rooms (
                code VARCHAR(6) PRIMARY KEY,
                owner_token VARCHAR(64) NOT NULL,
                data TEXT NOT NULL,
                created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """);
    }

    public RoomRecord create(String code, String ownerToken, String data) {
        jdbc.update(
            "INSERT INTO live_rooms (code, owner_token, data) VALUES (?, ?, ?)",
            code,
            ownerToken,
            data
        );
        return new RoomRecord(code, ownerToken, data);
    }

    public Optional<RoomRecord> find(String code) {
        List<RoomRecord> matches = jdbc.query(
            "SELECT code, owner_token, data FROM live_rooms WHERE code = ?",
            (resultSet, rowNumber) -> new RoomRecord(
                resultSet.getString("code"),
                resultSet.getString("owner_token"),
                resultSet.getString("data")
            ),
            code
        );
        return matches.stream().findFirst();
    }

    public boolean exists(String code) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM live_rooms WHERE code = ?",
            Integer.class,
            code
        );
        return count != null && count > 0;
    }

    public void save(String code, String data) {
        int changed = jdbc.update(
            "UPDATE live_rooms SET data = ?, updated_at = CURRENT_TIMESTAMP WHERE code = ?",
            data,
            code
        );
        if (changed != 1) {
            throw new IllegalStateException("Room " + code + " no longer exists in the database.");
        }
    }

    public record RoomRecord(String code, String ownerToken, String data) {
    }
}
