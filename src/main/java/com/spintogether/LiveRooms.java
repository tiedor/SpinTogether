package com.spintogether;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.annotation.Configuration;
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
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new Handler(), "/ws").setAllowedOriginPatterns("*");
    }

    static class Room {
        final String code;
        final String ownerToken = UUID.randomUUID().toString();
        String data = "{}";
        final Set<WebSocketSession> clients = ConcurrentHashMap.newKeySet();

        Room(String code) {
            this.code = code;
        }
    }

    static class Handler extends TextWebSocketHandler {
        static final Map<String, Room> rooms = new ConcurrentHashMap<>();
        static final ObjectMapper json = new ObjectMapper();
        static final DateTimeFormatter EUROPEAN_DATE = DateTimeFormatter.ofPattern("dd/MM/uuuu");

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
            Map<?, ?> input = json.readValue(message.getPayload(), Map.class);
            String type = (String) input.get("type");

            if ("leave".equals(type)) {
                leave(session);
                return;
            }

            if ("create".equals(type)) {
                leave(session);
                String code = code();
                Room room = new Room(code);
                rooms.put(code, room);
                enter(session, room, true, null);
                joined(session, room, true);
                presence(room);
                return;
            }

            if ("import".equals(type)) {
                String code = String.valueOf(input.get("code")).toUpperCase();
                if (rooms.containsKey(code)) {
                    send(session, Map.of("type", "error", "message", "That room code is already active."));
                    return;
                }
                leave(session);
                Room room = new Room(code);
                room.data = json.writeValueAsString(input.get("state"));
                rooms.put(code, room);
                enter(session, room, true, null);
                joined(session, room, true);
                presence(room);
                return;
            }

            if ("join".equals(type)) {
                Room room = rooms.get(String.valueOf(input.get("code")).toUpperCase());
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
                return;
            }

            Room room = (Room) session.getAttributes().get("room");
            if (room != null && !isOwner(session) && "rename".equals(type)) {
                saveSpectatorName(session, input.get("name"));
                presence(room);
                return;
            }
            if (room != null && isOwner(session) && "spin".equals(type)) {
                JsonNode spin = json.valueToTree(input.get("spin"));
                applySpin(room, spin);
                broadcast(room, Map.of("type", "spin", "spin", input.get("spin")));
                Thread.ofVirtual().start(() -> {
                    try {
                        Thread.sleep(4100);
                        broadcast(room, Map.of("type", "state", "state", room.data));
                    } catch (Exception ignored) {
                    }
                });
                return;
            }
            if (room != null && isOwner(session) && "deleteHistoryEntries".equals(type)) {
                deleteHistoryEntries(room, input.get("indices"));
                broadcastState(room);
                return;
            }
            if (room != null && isOwner(session) && "state".equals(type)) {
                room.data = json.writeValueAsString(input.get("state"));
                broadcastState(room);
            }
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            try {
                leave(session);
            } catch (Exception ignored) {
            }
        }

        static void enter(WebSocketSession session, Room room, boolean owner, Object submittedName) {
            room.clients.add(session);
            session.getAttributes().put("room", room);
            session.getAttributes().put("owner", owner);
            if (owner) {
                session.getAttributes().remove("name");
            } else {
                saveSpectatorName(session, submittedName);
            }
        }

        static boolean isOwner(WebSocketSession session) {
            return Boolean.TRUE.equals(session.getAttributes().get("owner"));
        }

        static void saveSpectatorName(WebSocketSession session, Object submittedName) {
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

        static void joined(WebSocketSession session, Room room, boolean owner) throws Exception {
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

        static void applySpin(Room room, JsonNode spin) throws Exception {
            JsonNode parsedState = json.readTree(room.data);
            ObjectNode state = parsedState instanceof ObjectNode objectNode ? objectNode : json.createObjectNode();
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

        static void deleteHistoryEntries(Room room, Object submittedIndices) throws Exception {
            if (!(submittedIndices instanceof List<?> submitted)) {
                return;
            }
            Set<Integer> indices = new java.util.TreeSet<>((first, second) -> Integer.compare(second, first));
            for (Object submittedIndex : submitted) {
                if (submittedIndex instanceof Number number) {
                    indices.add(number.intValue());
                }
            }
            if (indices.isEmpty()) {
                return;
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
        }

        static ObjectNode roomState(Room room) throws Exception {
            JsonNode parsedState = json.readTree(room.data);
            return parsedState instanceof ObjectNode objectNode ? objectNode : json.createObjectNode();
        }

        static void broadcastState(Room room) throws Exception {
            broadcast(room, Map.of("type", "state", "state", room.data));
        }

        static void leave(WebSocketSession session) throws Exception {
            Room room = (Room) session.getAttributes().remove("room");
            session.getAttributes().remove("name");
            session.getAttributes().remove("owner");
            if (room == null) {
                return;
            }
            room.clients.remove(session);
            if (!room.clients.isEmpty()) {
                presence(room);
            }
        }

        static void send(WebSocketSession session, Object data) throws Exception {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(json.writeValueAsString(data)));
            }
        }

        static void broadcast(Room room, Object data) throws Exception {
            for (WebSocketSession session : room.clients) {
                send(session, data);
            }
        }

        static void presence(Room room) throws Exception {
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

        static String code() {
            String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
            StringBuilder code = new StringBuilder();
            for (int index = 0; index < 6; index++) {
                code.append(alphabet.charAt((int) (Math.random() * alphabet.length())));
            }
            return code.toString();
        }
    }
}
