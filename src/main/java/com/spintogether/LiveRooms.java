package com.spintogether;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
@EnableWebSocket
public class LiveRooms implements WebSocketConfigurer {
    private final RoomStore roomStore;

    public LiveRooms(RoomStore roomStore) {
        this.roomStore = roomStore;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new Handler(roomStore), "/ws").setAllowedOriginPatterns("*");
    }

    static class Room {
        final String code;
        final String ownerToken;
        volatile String data;
        final Set<WebSocketSession> clients = ConcurrentHashMap.newKeySet();

        Room(RoomStore.RoomRecord record) {
            this.code = record.code();
            this.ownerToken = record.ownerToken();
            this.data = record.data();
        }
    }

    static class Handler extends TextWebSocketHandler {
        private static final Logger log = LoggerFactory.getLogger(Handler.class);
        private static final ObjectMapper json = new ObjectMapper();
        private static final DateTimeFormatter EUROPEAN_DATE = DateTimeFormatter.ofPattern("dd/MM/uuuu");

        private final RoomStore roomStore;
        private final Map<String, Room> activeRooms = new ConcurrentHashMap<>();

        Handler(RoomStore roomStore) {
            this.roomStore = roomStore;
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
            try {
                Map<?, ?> input = json.readValue(message.getPayload(), Map.class);
                String type = input.get("type") instanceof String submittedType ? submittedType : "";

                if ("leave".equals(type)) {
                    leave(session);
                    return;
                }
                if ("ping".equals(type)) {
                    send(session, Map.of("type", "pong"));
                    return;
                }
                if ("create".equals(type)) {
                    create(session);
                    return;
                }
                if ("import".equals(type)) {
                    importRoom(session, input);
                    return;
                }
                if ("join".equals(type)) {
                    join(session, input);
                    return;
                }

                Room room = (Room) session.getAttributes().get("room");
                if (room != null && !isOwner(session) && "rename".equals(type)) {
                    saveSpectatorName(session, input.get("name"));
                    presence(room);
                    return;
                }
                if (room != null && isOwner(session) && "spin".equals(type)) {
                    spin(session, room, input.get("spin"));
                    return;
                }
                if (room != null && isOwner(session) && "deleteHistoryEntries".equals(type)) {
                    deleteHistoryEntries(room, input.get("indices"));
                    broadcastState(room);
                    return;
                }
                if (room != null && isOwner(session) && "state".equals(type)) {
                    saveState(room, input.get("state"));
                    broadcastState(room);
                }
            } catch (Exception exception) {
                log.warn("Unable to process a room message", exception);
                send(session, Map.of(
                    "type", "error",
                    "message", "The room could not be saved right now. Please try again."
                ));
            }
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            try {
                leave(session);
            } catch (Exception exception) {
                log.debug("Unable to remove closed WebSocket session", exception);
            }
        }

        private void create(WebSocketSession session) throws Exception {
            for (int attempt = 0; attempt < 10; attempt++) {
                String code = code();
                try {
                    RoomStore.RoomRecord record = roomStore.create(code, UUID.randomUUID().toString(), "{}");
                    Room room = new Room(record);
                    leave(session);
                    activeRooms.put(code, room);
                    enter(session, room, true, null);
                    joined(session, room, true);
                    presence(room);
                    return;
                } catch (DuplicateKeyException ignored) {
                    // A code collision is unlikely; pick another one without exposing an internal error.
                }
            }
            send(session, Map.of("type", "error", "message", "Could not reserve a room code. Please try again."));
        }

        private void importRoom(WebSocketSession session, Map<?, ?> input) throws Exception {
            String code = validCode(input.get("code"));
            if (code == null) {
                send(session, Map.of("type", "error", "message", "The imported room needs a six-character room code."));
                return;
            }
            JsonNode state = json.valueToTree(input.get("state"));
            if (!state.isObject()) {
                send(session, Map.of("type", "error", "message", "That file does not contain a valid room state."));
                return;
            }
            if (activeRooms.containsKey(code) || roomStore.exists(code)) {
                send(session, Map.of("type", "error", "message", "That room code is already active."));
                return;
            }

            try {
                RoomStore.RoomRecord record = roomStore.create(
                    code,
                    UUID.randomUUID().toString(),
                    json.writeValueAsString(state)
                );
                Room room = new Room(record);
                leave(session);
                activeRooms.put(code, room);
                enter(session, room, true, null);
                joined(session, room, true);
                presence(room);
            } catch (DuplicateKeyException ignored) {
                send(session, Map.of("type", "error", "message", "That room code is already active."));
            }
        }

        private void join(WebSocketSession session, Map<?, ?> input) throws Exception {
            String code = validCode(input.get("code"));
            if (code == null) {
                send(session, Map.of("type", "error", "message", "Enter a valid six-character room code."));
                return;
            }
            Room room = findRoom(code);
            if (room == null) {
                send(session, Map.of("type", "error", "message", "Room not found."));
                return;
            }
            boolean owner = room.ownerToken.equals(input.get("ownerToken"));
            Room currentRoom = (Room) session.getAttributes().get("room");
            if (currentRoom != room) {
                leave(session);
            }
            enter(session, room, owner, input.get("name"));
            joined(session, room, owner);
            presence(room);
        }

        private Room findRoom(String code) {
            Room active = activeRooms.get(code);
            if (active != null) {
                return active;
            }
            return roomStore.find(code)
                .map(Room::new)
                .map(restored -> {
                    Room concurrentlyJoined = activeRooms.putIfAbsent(code, restored);
                    return concurrentlyJoined == null ? restored : concurrentlyJoined;
                })
                .orElse(null);
        }

        private void spin(WebSocketSession session, Room room, Object submittedSpin) throws Exception {
            JsonNode spin = json.valueToTree(submittedSpin);
            if (!spin.isObject()) {
                send(session, Map.of("type", "error", "message", "The spin could not be read. Please try again."));
                return;
            }
            synchronized (room) {
                String previousState = room.data;
                try {
                    applySpin(room, spin);
                    roomStore.save(room.code, room.data);
                } catch (Exception exception) {
                    room.data = previousState;
                    throw exception;
                }
            }
            broadcast(room, Map.of("type", "spin", "spin", submittedSpin));
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(4100);
                    broadcastState(room);
                } catch (Exception exception) {
                    log.debug("Could not send the spin result", exception);
                }
            });
        }

        private void saveState(Room room, Object submittedState) throws Exception {
            JsonNode state = json.valueToTree(submittedState);
            if (!state.isObject()) {
                throw new IllegalArgumentException("Room state must be a JSON object.");
            }
            String storedState = json.writeValueAsString(state);
            synchronized (room) {
                roomStore.save(room.code, storedState);
                room.data = storedState;
            }
        }

        private void deleteHistoryEntries(Room room, Object submittedIndices) throws Exception {
            synchronized (room) {
                String previousState = room.data;
                try {
                    if (deleteHistoryEntriesFromState(room, submittedIndices)) {
                        roomStore.save(room.code, room.data);
                    }
                } catch (Exception exception) {
                    room.data = previousState;
                    throw exception;
                }
            }
        }

        private static void enter(WebSocketSession session, Room room, boolean owner, Object submittedName) {
            room.clients.add(session);
            session.getAttributes().put("room", room);
            session.getAttributes().put("owner", owner);
            if (owner) {
                session.getAttributes().remove("name");
            } else {
                saveSpectatorName(session, submittedName);
            }
        }

        private static boolean isOwner(WebSocketSession session) {
            return Boolean.TRUE.equals(session.getAttributes().get("owner"));
        }

        private static void saveSpectatorName(WebSocketSession session, Object submittedName) {
            String name = submittedName == null ? "" : submittedName.toString().trim();
            if (name.length() > 60) {
                name = name.substring(0, 60);
            }
            if (name.isEmpty()) {
                session.getAttributes().remove("name");
            } else {
                session.getAttributes().put("name", name);
            }
        }

        private static void joined(WebSocketSession session, Room room, boolean owner) throws Exception {
            if (owner) {
                send(session, Map.of(
                    "type", "joined",
                    "code", room.code,
                    "owner", true,
                    "ownerToken", room.ownerToken,
                    "state", room.data
                ));
            } else {
                send(session, Map.of(
                    "type", "joined",
                    "code", room.code,
                    "owner", false,
                    "state", room.data
                ));
            }
        }

        private static void applySpin(Room room, JsonNode spin) throws Exception {
            ObjectNode state = roomState(room);
            ArrayNode categories = state.withArray("categories");
            for (JsonNode wheel : spin.withArray("wheels")) {
                int index = wheel.path("index").asInt(-1);
                if (index < 0 || index >= categories.size() || !(categories.get(index) instanceof ObjectNode category)) {
                    continue;
                }
                category.put("rotationDegrees", wheel.path("rotation").asDouble());
                category.put("lastResult", wheel.path("result").asText());
            }
            ObjectNode lastSpin = state.putObject("lastSpin");
            lastSpin.put("id", spin.path("id").asText());
            lastSpin.set("results", spin.path("results").deepCopy());
            ObjectNode historyEntry = state.withArray("history").addObject();
            historyEntry.put("date", EUROPEAN_DATE.format(LocalDate.now(ZoneId.of("Europe/Amsterdam"))));
            historyEntry.set("results", spin.path("results").deepCopy());
            room.data = json.writeValueAsString(state);
        }

        private static boolean deleteHistoryEntriesFromState(Room room, Object submittedIndices) throws Exception {
            if (!(submittedIndices instanceof List<?> submitted)) {
                return false;
            }
            Set<Integer> indices = new java.util.TreeSet<>((first, second) -> Integer.compare(second, first));
            for (Object submittedIndex : submitted) {
                if (submittedIndex instanceof Number number) {
                    indices.add(number.intValue());
                }
            }
            if (indices.isEmpty()) {
                return false;
            }
            ObjectNode state = roomState(room);
            ArrayNode history = state.withArray("history");
            boolean changed = false;
            for (int index : indices) {
                if (index >= 0 && index < history.size()) {
                    history.remove(index);
                    changed = true;
                }
            }
            if (changed) {
                room.data = json.writeValueAsString(state);
            }
            return changed;
        }

        private static ObjectNode roomState(Room room) throws Exception {
            JsonNode parsedState = json.readTree(room.data);
            return parsedState instanceof ObjectNode objectNode ? objectNode : json.createObjectNode();
        }

        private static void broadcastState(Room room) throws Exception {
            broadcast(room, Map.of("type", "state", "state", room.data));
        }

        private void leave(WebSocketSession session) throws Exception {
            Room room = (Room) session.getAttributes().remove("room");
            session.getAttributes().remove("name");
            session.getAttributes().remove("owner");
            if (room == null) {
                return;
            }
            room.clients.remove(session);
            if (room.clients.isEmpty()) {
                activeRooms.remove(room.code, room);
            } else {
                presence(room);
            }
        }

        private static void send(WebSocketSession session, Object data) throws Exception {
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json.writeValueAsString(data)));
                }
            }
        }

        private static void broadcast(Room room, Object data) throws Exception {
            for (WebSocketSession session : room.clients) {
                send(session, data);
            }
        }

        private static void presence(Room room) throws Exception {
            List<Map<String, String>> spectators = room.clients.stream()
                .filter(session -> !isOwner(session))
                .map(session -> {
                    Object savedName = session.getAttributes().get("name");
                    String name = savedName == null ? "" : savedName.toString().trim();
                    return Map.of("name", name.isEmpty() ? "Anonymous spectator" : name);
                })
                .toList();
            broadcast(room, Map.of("type", "presence", "spectators", spectators, "count", spectators.size()));
        }

        private static String validCode(Object submittedCode) {
            if (!(submittedCode instanceof String code)) {
                return null;
            }
            String normalized = code.trim().toUpperCase();
            return normalized.matches("[A-Z0-9]{6}") ? normalized : null;
        }

        private static String code() {
            String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
            StringBuilder code = new StringBuilder();
            for (int index = 0; index < 6; index++) {
                code.append(alphabet.charAt((int) (Math.random() * alphabet.length())));
            }
            return code.toString();
        }
    }
}
