package org.example.puzzle.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Talks to a real {@link EngineServer} over HTTP, the same way the Unity
 * client does.
 */
class EngineServerTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient client = HttpClient.newHttpClient();
    private EngineServer server;

    @BeforeEach
    void start() throws IOException {
        server = EngineServer.start(0);
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void health() throws Exception {
        Reply r = send("GET", "/health", null);
        assertEquals(200, r.status);
        assertEquals("ok", r.json.path("status").asString());
    }

    @Test
    void memoryGameOverHttp() throws Exception {
        Reply created = send("POST", "/games?example=memory-game&random=false", null);
        assertEquals(201, created.status);
        String id = created.json.path("sessionId").asString();
        assertEquals("memory-3x4", created.json.path("model").path("id").asString());
        assertEquals("P11", created.json.path("state").path("objects").path("O01").path("position").asString());
        assertFalse(created.json.path("state").path("objects").path("O01").path("visible").asBoolean());

        send("POST", "/games/" + id + "/actions", "{\"action\":\"ClickCard\",\"params\":{\"target\":\"O01\"}}");
        Reply second = send("POST", "/games/" + id + "/actions", "{\"action\":\"ClickCard\",\"params\":{\"target\":\"O03\"}}");
        assertTrue(second.json.path("accepted").asBoolean());
        assertEquals(1500, second.json.path("status").path("nextTimerInMs").asLong());

        Reply third = send("POST", "/games/" + id + "/actions", "{\"action\":\"ClickCard\",\"params\":{\"target\":\"O05\"}}");
        assertEquals(200, third.status, "a rejected move is a game outcome, not an HTTP error");
        assertFalse(third.json.path("accepted").asBoolean());
        assertFalse(third.json.path("message").asString().isEmpty());

        Reply tick = send("POST", "/games/" + id + "/time", "{\"ms\":1500}");
        assertFalse(tick.json.path("state").path("objects").path("O01").path("visible").asBoolean());
        assertTrue(tick.json.path("status").path("nextTimerInMs").isNull());

        assertEquals(204, send("DELETE", "/games/" + id, null).status);
        assertEquals(404, send("GET", "/games/" + id, null).status);
    }

    @Test
    void gameFromPostedModelReachesTheEnd() throws Exception {
        String model = new String(getClass().getResourceAsStream("/examples/matching-stars.json").readAllBytes());
        String id = send("POST", "/games?random=false", model).json.path("sessionId").asString();

        send("POST", "/games/" + id + "/actions", "{\"action\":\"Move\",\"params\":{\"obj\":\"O1\",\"dst\":\"P1\"}}");
        send("POST", "/games/" + id + "/actions", "{\"action\":\"Move\",\"params\":{\"obj\":\"O2\",\"dst\":\"P2\"}}");
        Reply last = send("POST", "/games/" + id + "/actions", "{\"action\":\"Move\",\"params\":{\"src\":\"P6\",\"dst\":\"P3\"}}");

        assertTrue(last.json.path("status").path("finished").asBoolean());
        assertTrue(last.json.path("status").path("result").asBoolean());
    }

    @Test
    void startIsRandomByDefaultAndReproducibleWithSeed() throws Exception {
        Reply random = send("POST", "/games?example=fifteen-puzzle", null);
        assertTrue(random.json.path("seed").canConvertToLong());

        long seed = random.json.path("seed").asLong();
        Reply replay = send("POST", "/games?example=fifteen-puzzle&seed=" + seed, null);
        assertEquals(seed, replay.json.path("seed").asLong());
        assertEquals(random.json.path("state"), replay.json.path("state"));

        assertTrue(send("POST", "/games?example=fifteen-puzzle&random=false", null).json.path("seed").isNull());
        assertEquals(400, send("POST", "/games?example=fifteen-puzzle&seed=abc", null).status);
    }

    @Test
    void invalidModelIsRejectedWithIssues() throws Exception {
        Reply r = send("POST", "/games", "{\"id\":\"broken\"}");

        assertEquals(422, r.status);
        assertTrue(r.json.path("issues").isArray() && !r.json.path("issues").isEmpty());
    }

    @Test
    void validateEndpointReportsWithoutStartingAGame() throws Exception {
        String model = new String(getClass().getResourceAsStream("/examples/lights-out.json").readAllBytes());
        Reply r = send("POST", "/validate", model);

        assertEquals(200, r.status);
        assertTrue(r.json.path("valid").asBoolean());
    }

    @Test
    void badRequests() throws Exception {
        assertEquals(400, send("POST", "/games", "{not json").status);
        assertEquals(404, send("POST", "/games?example=sokoban", null).status);
        assertEquals(404, send("POST", "/games/nope/actions", "{\"action\":\"X\"}").status);
        assertEquals(404, send("GET", "/nowhere", null).status);

        String id = send("POST", "/games?example=lights-out", null).json.path("sessionId").asString();
        assertEquals(400, send("POST", "/games/" + id + "/actions", "{}").status);
        assertEquals(400, send("POST", "/games/" + id + "/time", "{\"ms\":-5}").status);
    }

    /* ----- helpers ------------------------------------------------------- */

    private record Reply(int status, JsonNode json) {
    }

    private Reply send(String method, String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode json = response.body().isEmpty() ? MAPPER.missingNode() : MAPPER.readTree(response.body());
        return new Reply(response.statusCode(), json);
    }
}
