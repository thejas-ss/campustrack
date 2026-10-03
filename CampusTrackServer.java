import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** CampusTrack is a small full-stack lost-and-found application using only JDK 17+. */
public class CampusTrackServer {
    // ArrayList is our in-memory data structure. It keeps objects in insertion order.
    private static final List<Item> items = Collections.synchronizedList(new ArrayList<>());
    private static int nextId = 1;
    private static final Path ROOT = Paths.get(".").toAbsolutePath().normalize();

    /** OOP model: one Item object represents one noticeboard post. */
    static class Item {
        int id; String name, category, location, contact, status, timestamp;
        Item(int id, String name, String category, String location, String contact, String status) {
            this.id = id; this.name = name; this.category = category; this.location = location;
            this.contact = contact; this.status = status; this.timestamp = Instant.now().toString();
        }
        String toJson() { return "{\"id\":" + id + ",\"name\":\"" + json(name) + "\",\"category\":\"" + json(category) + "\",\"location\":\"" + json(location) + "\",\"contact\":\"" + json(contact) + "\",\"status\":\"" + status + "\",\"timestamp\":\"" + timestamp + "\"}"; }
    }

    public static void main(String[] args) throws Exception {
        seedData();
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        // NOTIFICATIONS: browsers "poll" this endpoint every few seconds to ask
        // "has anyone posted something newer than the last id I saw?"
        server.createContext("/api/notifications", new NotificationHandler());
        server.createContext("/api/items/claim", new ClaimHandler());
        server.createContext("/api/items", new ItemsHandler());
        server.createContext("/", new StaticHandler());
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("CampusTrack running at http://localhost:8080");
    }

    private static void seedData() {
        items.add(new Item(nextId++, "Blue Lanyard", "ID/Keys", "Central Library", "security@campus.edu", "LOST"));
        items.add(new Item(nextId++, "Casio Calculator", "Electronics", "Block B, Room 204", "counter@campus.edu", "FOUND"));
        items.add(new Item(nextId++, "Data Structures Notes", "Books", "Cafeteria", "library@campus.edu", "LOST"));
    }

    static class ItemsHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            if (preflight(exchange)) return;
      if ("GET".equals(exchange.getRequestMethod())) send(exchange, 200, listJson(), "application/json");
            // The new id is sent back so the poster's own browser can skip notifying itself.
            else if ("POST".equals(exchange.getRequestMethod())) { Item item = createItem(readBody(exchange)); items.add(item); send(exchange, 201, "{\"message\":\"Item added\",\"id\":" + item.id + "}", "application/json"); }
            else send(exchange, 405, "Method not allowed", "text/plain");
        }
    }
    static class ClaimHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            if (preflight(exchange)) return;
      if (!"POST".equals(exchange.getRequestMethod())) { send(exchange, 405, "Method not allowed", "text/plain"); return; }
            int id = Integer.parseInt(value(readBody(exchange), "id"));
            synchronized (items) { for (Item item : items) if (item.id == id) { item.status = "CLAIMED"; send(exchange, 200, "{\"message\":\"Item claimed\"}", "application/json"); return; } }
            send(exchange, 404, "{\"error\":\"Item not found\"}", "application/json");
        }
    }
    /**
     * NOTIFICATION FEED (short polling)
     * GET /api/notifications?since=5  ->  {"latestId":7,"items":[ {item 6}, {item 7} ]}
     *
     * Why polling? HttpServer has no built-in WebSocket support, so the simplest
     * real-time technique is: each browser remembers the highest id it has seen and
     * repeatedly asks the server for anything newer. Because ids auto-increment,
     * "id > since" is exactly the set of notices posted after the client last checked.
     */
    static class NotificationHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            if (preflight(exchange)) return;
      if (!"GET".equals(exchange.getRequestMethod())) { send(exchange, 405, "Method not allowed", "text/plain"); return; }

            // Read the query string manually, e.g. "since=5". Missing/invalid -> 0 (send everything).
            int since = 0;
            String query = exchange.getRequestURI().getQuery();
            if (query != null) {
                for (String pair : query.split("&")) {
                    String[] kv = pair.split("=", 2);
                    if (kv.length == 2 && kv[0].equals("since")) {
                        try { since = Integer.parseInt(URLDecoder.decode(kv[1], StandardCharsets.UTF_8)); }
                        catch (NumberFormatException ignored) { since = 0; }
                    }
                }
            }

            // Linear search through the ArrayList: O(n), perfectly fine for a campus-sized list.
            StringBuilder out = new StringBuilder("{\"latestId\":");
            int latestId = 0;
            StringBuilder newItems = new StringBuilder("[");
            synchronized (items) {
                for (Item item : items) {
                    latestId = Math.max(latestId, item.id);
                    if (item.id > since) {
                        if (newItems.length() > 1) newItems.append(',');
                        newItems.append(item.toJson());
                    }
                }
            }
            out.append(latestId).append(",\"items\":").append(newItems).append("]}");
            send(exchange, 200, out.toString(), "application/json");
        }
    }

    static class StaticHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath(); if (path.equals("/")) path = "/index.html";
            Path file = ROOT.resolve(path.substring(1)).normalize();
            if (!file.startsWith(ROOT) || !Files.exists(file) || Files.isDirectory(file)) { send(exchange, 404, "Not found", "text/plain"); return; }
            String type = path.endsWith(".css") ? "text/css" : path.endsWith(".js") ? "text/javascript" : "text/html";
            send(exchange, 200, Files.readString(file), type);
        }
    }

    // synchronized: with 8 threads, two posts at once could otherwise get the same nextId,
    // and duplicate ids would break the "id > since" notification check.
    private static synchronized Item createItem(String body) { return new Item(nextId++, value(body,"name"), value(body,"category"), value(body,"location"), value(body,"contact"), value(body,"status")); }
    private static String listJson() { StringBuilder out = new StringBuilder("["); synchronized(items) { for (int i=0;i<items.size();i++) { if(i>0) out.append(','); out.append(items.get(i).toJson()); } } return out.append(']').toString(); }
    // This intentionally small parser is suitable for the known flat form payload; no external JSON library is used.
    private static String value(String json, String key) { Matcher m = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*(?:\\\"([^\\\"]*)\\\"|(\\d+))").matcher(json); return m.find() ? (m.group(1) != null ? m.group(1) : m.group(2)) : ""; }
    private static String readBody(HttpExchange e) throws IOException { return new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8); }
    private static String json(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"); }
    // CORS headers let a page opened from another origin (file:// or Live Server) call this API.
  private static void cors(HttpExchange e) {
    e.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
    e.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    e.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
  }
  // Browsers send an OPTIONS "preflight" before a JSON POST. Answer it with 204 No Content.
  private static boolean preflight(HttpExchange e) throws IOException {
    if (!"OPTIONS".equals(e.getRequestMethod())) return false;
    cors(e); e.sendResponseHeaders(204, -1); e.close(); return true;
  }
  private static void send(HttpExchange e, int code, String body, String type) throws IOException { byte[] data=body.getBytes(StandardCharsets.UTF_8); e.getResponseHeaders().set("Content-Type", type+"; charset=utf-8"); cors(e); e.sendResponseHeaders(code,data.length); try(OutputStream out=e.getResponseBody()){out.write(data);} }
}
