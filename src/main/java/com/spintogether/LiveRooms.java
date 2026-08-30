package com.spintogether;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
@EnableWebSocket
public class LiveRooms implements WebSocketConfigurer {
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new Handler(), "/ws").setAllowedOriginPatterns("*");
    }

    static class Room {
        String code;
        String owner;
        String data = "{}";
        Set<WebSocketSession> clients = ConcurrentHashMap.newKeySet();

        Room(String code, String owner) {
            this.code = code;
            this.owner = owner;
        }
    }

    static class Handler extends TextWebSocketHandler {
        static final Map<String, Room> rooms = new ConcurrentHashMap<>();
        static final ObjectMapper json = new ObjectMapper();

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
                Room room = new Room(code, session.getId());
                room.clients.add(session);
                session.getAttributes().put("room", room);
                send(session, Map.of("type", "joined", "code", code, "owner", true, "state", room.data));
                presence(room);
                return;
            }

            if ("import".equals(type)) {
                String code = (String) input.get("code");
                if (rooms.containsKey(code)) {
                    send(session, Map.of("type", "error", "message", "That room code is already active."));
                    return;
                }
                leave(session);
                Room room = new Room(code, session.getId());
                room.data = json.writeValueAsString(input.get("state"));
                rooms.put(code, room);
                room.clients.add(session);
                session.getAttributes().put("room", room);
                broadcast(room, Map.of("type", "joined", "code", code, "owner", true, "state", room.data));
                presence(room);
                return;
            }

            if ("join".equals(type)) {
                Room room = rooms.get(input.get("code"));
                if (room == null) {
                    send(session, Map.of("type", "error", "message", "Room not found."));
                    return;
                }
                Room currentRoom = (Room) session.getAttributes().get("room");
                if (currentRoom != room) {
                    leave(session);
                }
                room.clients.add(session);
                session.getAttributes().put("room", room);
                Object name = input.get("name");
                if (name == null) {
                    session.getAttributes().remove("name");
                } else {
                    session.getAttributes().put("name", name);
                }
                send(session, Map.of("type", "joined", "code", room.code, "owner", false, "state", room.data));
                presence(room);
                return;
            }

            Room room = (Room) session.getAttributes().get("room");
            if (room != null && room.owner.equals(session.getId()) && "spin".equals(type)) {
                broadcast(room, Map.of("type", "spin", "spin", input.get("spin")));
                return;
            }
            if (room != null && room.owner.equals(session.getId()) && "state".equals(type)) {
                room.data = json.writeValueAsString(input.get("state"));
                broadcast(room, Map.of("type", "state", "state", room.data));
            }
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            try {
                leave(session);
            } catch (Exception ignored) {
            }
        }

        static void leave(WebSocketSession session) throws Exception {
            Room room = (Room) session.getAttributes().remove("room");
            session.getAttributes().remove("name");
            if (room == null) {
                return;
            }
            room.clients.remove(session);
            if (room.clients.isEmpty()) {
                rooms.remove(room.code, room);
            } else {
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
                .filter(session -> !room.owner.equals(session.getId()))
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
