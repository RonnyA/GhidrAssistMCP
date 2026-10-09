package ghidrassistmcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Checks the preserved HTTP+SSE handshake independently of Streamable HTTP. */
class LegacySseIntegrationTest {
    @Test
    void legacyClientInitializesAndDiscoversExplicitTools() throws Exception {
        GhidrAssistMCPBackend backend = new GhidrAssistMCPBackend();
        GhidrAssistMCPServer server = new GhidrAssistMCPServer("127.0.0.1", 0, backend);
        ExecutorService events = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "legacy-sse-test-reader");
            thread.setDaemon(true);
            return thread;
        });
        InputStream stream = null;
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        try {
            server.start();
            URI origin = URI.create("http://127.0.0.1:" + server.getBoundPort());
            HttpResponse<InputStream> connected = client.send(HttpRequest.newBuilder(origin.resolve("/sse"))
                .timeout(Duration.ofSeconds(10)).header("Accept", "text/event-stream").GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, connected.statusCode());
            stream = connected.body();
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            URI messages = origin.resolve(readData(events, reader));
            ObjectMapper mapper = new ObjectMapper();
            post(client, messages, mapper, Map.of("jsonrpc", "2.0", "id", 1,
                "method", "initialize", "params", Map.of("protocolVersion", "2024-11-05",
                    "capabilities", Map.of(), "clientInfo", Map.of("name", "legacy-test", "version", "1.0"))));
            JsonNode initialized = mapper.readTree(readData(events, reader));
            assertEquals(1, initialized.path("id").asInt());
            assertEquals("2024-11-05", initialized.path("result").path("protocolVersion").asText());
            post(client, messages, mapper, Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));
            post(client, messages, mapper, Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/list"));
            JsonNode tools = mapper.readTree(readData(events, reader));
            assertEquals(2, tools.path("id").asInt());
            assertTrue(tools.path("result").path("tools").findValuesAsText("name").contains("get_pseudocode"));
        } finally {
            if (stream != null) stream.close();
            client.shutdownNow();
            events.shutdownNow();
            try { server.stop(); } finally { backend.shutdown(); }
        }
    }

    private static void post(HttpClient client, URI endpoint, ObjectMapper mapper,
            Map<String, ?> message) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(message))).build(),
            HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() == 200 || response.statusCode() == 202, response.body());
    }

    private static String readData(ExecutorService events, BufferedReader reader) throws Exception {
        return events.submit(() -> {
            for (String line; (line = reader.readLine()) != null;) {
                if (line.startsWith("data:")) return line.substring(5).trim();
            }
            throw new IllegalStateException("SSE response closed before the expected event");
        }).get(10, TimeUnit.SECONDS);
    }
}
