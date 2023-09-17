package co.elastic.apm.agent.httpclient;

import co.elastic.apm.base.AbstractInstrumentationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.kananindzya.elastic.apm.example.webserver.ExampleAlreadyInstrumentedHttpServer;
import com.kananindzya.elastic.apm.example.webserver.ExampleHttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JettyHttpClientInstrumentationIT extends AbstractInstrumentationTest {

    protected static Exception START_EXCEPTION;
    protected static int PORT = -1;
    protected static ExampleHttpServer Server;
    protected static HttpClient Client = HttpClient.newHttpClient();

    @BeforeAll
    public static void startServer() {
        Server = new ExampleAlreadyInstrumentedHttpServer();
        new Thread(() -> {
            try {
                Server.start();
            } catch (Exception e) {
                START_EXCEPTION = e;
            }
        }).start();
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            Server.blockUntilReady();
        });
        assertTrue(START_EXCEPTION == null);
        PORT = Server.getLocalPort();
    }

    @AfterAll
    public static void stopServer() throws IOException, InterruptedException {
        assertEquals(executeRequest("exit").getFirst(), 200);
        Server.stop();
        assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {Server.blockUntilStopped();});
    }

    @Test
    public void testSyncInstrumentation() throws IOException, InterruptedException, TimeoutException {
        Pair<Integer, String> statusCode = executeRequest("sync");
        assertEquals(statusCode.getFirst(), 200);
        assertEquals(statusCode.getSecond(), "sync:<A HREF=\"/sync\">sync</A><BR><A HREF=\"/async\">async</A><BR><A HREF=\"/exit\">exit</A><BR><A HREF=\"/\"></A><BR>");

        JsonNode transaction = ApmServer.getAndRemoveTransaction(0, 1000);
        assertNotNull(transaction);
    }

    private static Pair<Integer, String> executeRequest(String req) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + PORT + "/" + req))
                .GET()
                .build();

        HttpResponse<String> response = Client.send(request, HttpResponse.BodyHandlers.ofString());
        return new Pair<>(response.statusCode(), response.body());
    }

}
