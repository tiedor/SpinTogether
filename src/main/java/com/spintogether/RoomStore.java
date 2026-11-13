package com.spintogether;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class RoomStore {
    static final Duration RECONNECT_WINDOW = Duration.ofMinutes(15);

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
        jdbc.execute("ALTER TABLE live_rooms ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP WITH TIME ZONE");
        jdbc.update(
            "UPDATE live_rooms SET expires_at = ? WHERE expires_at IS NULL",
            Timestamp.from(Instant.now().plus(RECONNECT_WINDOW))
        );
    }

    public RoomRecord create(String code, String ownerToken, String data) {
        return create(code, ownerToken, data, Instant.now().plus(RECONNECT_WINDOW));
    }

    RoomRecord create(String code, String ownerToken, String data, Instant expiresAt) {
        jdbc.update(
            "INSERT INTO live_rooms (code, owner_token, data, expires_at) VALUES (?, ?, ?, ?)",
            code,
            ownerToken,
            data,
            Timestamp.from(expiresAt)
        );
        return new RoomRecord(code, ownerToken, data);
    }

    public Optional<RoomRecord> find(String code) {
        List<RoomRecord> matches = jdbc.query(
            "SELECT code, owner_token, data FROM live_rooms WHERE code = ? AND expires_at > ?",
            (resultSet, rowNumber) -> new RoomRecord(
                resultSet.getString("code"),
                resultSet.getString("owner_token"),
                resultSet.getString("data")
            ),
            code,
            Timestamp.from(Instant.now())
        );
        return matches.stream().findFirst();
    }

    public boolean exists(String code) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM live_rooms WHERE code = ? AND expires_at > ?",
            Integer.class,
            code,
            Timestamp.from(Instant.now())
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

    public boolean extendLifetime(String code) {
        return extendLifetime(code, Instant.now().plus(RECONNECT_WINDOW));
    }

    boolean extendLifetime(String code, Instant expiresAt) {
        return jdbc.update(
            "UPDATE live_rooms SET expires_at = ? WHERE code = ?",
            Timestamp.from(expiresAt),
            code
        ) == 1;
    }

    public int deleteExpiredRooms() {
        return deleteExpiredRooms(Instant.now());
    }

    int deleteExpiredRooms(Instant now) {
        return jdbc.update(
            "DELETE FROM live_rooms WHERE expires_at <= ?",
            Timestamp.from(now)
        );
    }

    public record RoomRecord(String code, String ownerToken, String data) {
    }
}
