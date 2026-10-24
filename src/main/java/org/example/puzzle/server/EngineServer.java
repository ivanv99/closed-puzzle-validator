package org.example.puzzle.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.example.puzzle.engine.EngineException;
import org.example.puzzle.engine.GameEngine;
import org.example.puzzle.engine.StepResult;
import org.example.puzzle.model.ClosedPuzzle;
import org.example.puzzle.validator.PuzzleValidator;
import org.example.puzzle.validator.ValidationReport;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

/**
 * Local HTTP/JSON front-end of the {@link GameEngine}, used by the Unity
 * player (or any other client). Listens on 127.0.0.1 only.
 *
 * <pre>
 *   GET    /health                       liveness check
 *   GET    /examples                     names of the bundled case studies
 *   POST   /validate                     body: model → validation report
 *   POST   /games                        body: model → new session (201)
 *   POST   /games?example=memory-game    new session from a bundled case study (201)
 *   GET    /games/{id}                   current state
 *   POST   /games/{id}/actions           body: {"action": "ClickCard", "params": {"target": "O01"}}
 *   POST   /games/{id}/time              body: {"ms": 1500} — advances the logical clock
 *   DELETE /games/{id}                   ends the session (204)
 *   POST   /shutdown                     stops the server
 * </pre>
 *
 * A rejected action is a normal game outcome (200 with {@code accepted:false});
 * HTTP errors are reserved for malformed requests (400), unknown sessions or
 * routes (404) and invalid models (422).
 */
public final class EngineServer {

    static final List<String> EXAMPLES = List.of("matching-stars", "memory-game", "fifteen-puzzle", "lights-out");
    static final int DEFAULT_PORT = 8765;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> PARAMS = new TypeReference<>() {
    };

    private final HttpServer http;
    private final PuzzleValidator validator;
    private final Map<String, GameEngine> games = new ConcurrentHashMap<>();
    private final CountDownLatch stopped = new CountDownLatch(1);

    private record Response(int status, Object body) {
    }

    private static final class HttpError extends RuntimeException {
        final int status;

        HttpError(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private EngineServer(HttpServer http) throws IOException {
        this.http = http;
        this.validator = new PuzzleValidator();
    }

    /** Starts the server on the loopback interface; port 0 picks a free port. */
    public static EngineServer start(int port) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        EngineServer server = new EngineServer(http);
        http.createContext("/", server::handle);
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        http.start();
        return server;
    }

    public int port() {
        return http.getAddress().getPort();
    }

    public void stop() {
        http.stop(0);
        stopped.countDown();
    }

    public void awaitStop() throws InterruptedException {
        stopped.await();
    }

    /**
     * {@code serve [--port N] [--parent-pid PID]}. Prints {@code LISTENING <port>}
     * on stdout once ready. With {@code --parent-pid} the server stops when that
     * process (e.g. the Unity player) exits, so no orphaned JVM is left behind.
     */
    public static void main(String[] args) throws Exception {
        int port = DEFAULT_PORT;
        Long parentPid = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--parent-pid" -> parentPid = Long.parseLong(args[++i]);
                default -> {
                    System.err.println("Usage: serve [--port N] [--parent-pid PID]");
                    System.exit(64);
                }
            }
        }

        EngineServer server = start(port);
        if (parentPid != null) {
            ProcessHandle.of(parentPid).ifPresentOrElse(
                    parent -> parent.onExit().thenRun(server::stop),
                    server::stop);
        }
        System.out.println("LISTENING " + server.port());
        System.out.flush();
        server.awaitStop();
    }

    /* ----- request handling ---------------------------------------------- */

    private void handle(HttpExchange ex) throws IOException {
        Response response;
        try {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            response = route(ex.getRequestMethod(), ex.getRequestURI().getPath(), ex.getRequestURI().getRawQuery(), body);
        } catch (HttpError e) {
            response = error(e.status, e.getMessage());
        } catch (JacksonException e) {
            response = error(400, "Malformed JSON: " + e.getOriginalMessage());
        } catch (RuntimeException e) {
            response = error(500, e.toString());
        }
        try {
            if (response.body() == null) {
                ex.sendResponseHeaders(response.status(), -1);
            } else {
                byte[] bytes = MAPPER.writeValueAsBytes(response.body());
                ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                ex.sendResponseHeaders(response.status(), bytes.length);
                ex.getResponseBody().write(bytes);
            }
        } finally {
            ex.close();
        }
    }

    private Response route(String method, String path, String query, String body) throws IOException {
        String[] seg = Arrays.stream(path.split("/")).filter(s -> !s.isEmpty()).toArray(String[]::new);
        String id = seg.length >= 2 && seg[0].equals("games") ? seg[1] : null;
        String shape = id == null ? String.join("/", seg) : "games/{id}" + (seg.length > 2 ? "/" + seg[2] : "");
        if (seg.length > 3) shape = "?";

        return switch (method + " " + shape) {
            case "GET health" -> ok(Map.of("status", "ok"));
            case "GET examples" -> ok(EXAMPLES);
            case "POST validate" -> ok(report(validator.validate(parse(body))));
            case "POST games" -> createGame(query, body);
            case "GET games/{id}" -> {
                GameEngine g = game(id);
                synchronized (g) {
                    yield ok(view(id, g));
                }
            }
            case "DELETE games/{id}" -> {
                if (games.remove(id) == null) throw new HttpError(404, "Unknown session '" + id + "'.");
                yield new Response(204, null);
            }
            case "POST games/{id}/actions" -> perform(id, parse(body));
            case "POST games/{id}/time" -> advanceTime(id, parse(body));
            case "POST shutdown" -> {
                Thread.ofVirtual().start(this::stop);
                yield ok(Map.of("status", "stopping"));
            }
            default -> throw new HttpError(404, "Unknown route " + method + " " + path);
        };
    }

    private Response createGame(String query, String body) throws IOException {
        String example = queryParam(query, "example");
        JsonNode tree = example != null ? loadExample(example) : parse(body);

        ValidationReport report = validator.validate(tree);
        if (!report.isValid()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "Invalid puzzle model.");
            err.put("issues", report.issues());
            return new Response(422, err);
        }
        GameEngine engine;
        try {
            engine = new GameEngine(MAPPER.treeToValue(tree, ClosedPuzzle.class));
        } catch (EngineException e) {
            return error(422, "The model cannot be executed: " + e.getMessage());
        }

        String id = UUID.randomUUID().toString();
        games.put(id, engine);
        Map<String, Object> view = view(id, engine);
        view.put("warnings", report.issues());
        view.put("model", tree);
        return new Response(201, view);
    }

    private Response perform(String id, JsonNode req) {
        String action = req.path("action").asString("");
        if (action.isEmpty()) throw new HttpError(400, "Missing 'action'.");
        Map<String, String> params = req.hasNonNull("params") ? MAPPER.convertValue(req.get("params"), PARAMS) : Map.of();

        GameEngine g = game(id);
        synchronized (g) {
            return ok(stepView(id, g, g.perform(action, params)));
        }
    }

    private Response advanceTime(String id, JsonNode req) {
        JsonNode ms = req.get("ms");
        if (ms == null || !ms.canConvertToLong() || ms.asLong() < 0) {
            throw new HttpError(400, "'ms' must be a non-negative integer.");
        }
        GameEngine g = game(id);
        synchronized (g) {
            return ok(stepView(id, g, g.advanceTime(ms.asLong())));
        }
    }

    /* ----- views --------------------------------------------------------- */

    private static Map<String, Object> view(String id, GameEngine g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", id);
        m.put("status", status(g));
        m.put("state", g.state().snapshot());
        return m;
    }

    private static Map<String, Object> stepView(String id, GameEngine g, StepResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", id);
        m.put("accepted", r.accepted());
        m.put("message", r.message());
        m.put("rules", r.rules());
        m.put("events", r.events());
        m.put("sounds", r.sounds());
        m.put("status", status(g));
        m.put("state", g.state().snapshot());
        return m;
    }

    private static Map<String, Object> status(GameEngine g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("finished", g.isFinished());
        m.put("result", g.result());
        m.put("clock", g.clock());
        m.put("nextTimerInMs", g.nextTimerInMs());
        return m;
    }

    private static Map<String, Object> report(ValidationReport r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("valid", r.isValid());
        m.put("errors", r.errorCount());
        m.put("warnings", r.warningCount());
        m.put("issues", r.issues());
        return m;
    }

    /* ----- helpers ------------------------------------------------------- */

    private GameEngine game(String id) {
        GameEngine g = games.get(id);
        if (g == null) throw new HttpError(404, "Unknown session '" + id + "'.");
        return g;
    }

    private static JsonNode parse(String body) {
        if (body.isBlank()) throw new HttpError(400, "Request body is empty.");
        return MAPPER.readTree(body);
    }

    private static JsonNode loadExample(String name) throws IOException {
        if (!EXAMPLES.contains(name)) throw new HttpError(404, "Unknown example '" + name + "'. Known: " + EXAMPLES);
        try (InputStream in = EngineServer.class.getResourceAsStream("/examples/" + name + ".json")) {
            if (in == null) throw new HttpError(500, "Example '" + name + "' is missing from the build.");
            return MAPPER.readTree(in);
        }
    }

    private static String queryParam(String query, String name) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            if (key.equals(name)) return eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
        }
        return null;
    }

    private static Response ok(Object body) {
        return new Response(200, body);
    }

    private static Response error(int status, String message) {
        Map<String, Object> m = new HashMap<>();
        m.put("error", message);
        return new Response(status, m);
    }
}
