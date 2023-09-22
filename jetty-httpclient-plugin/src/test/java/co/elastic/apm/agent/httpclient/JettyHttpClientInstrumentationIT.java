package co.elastic.apm.agent.httpclient;

import co.elastic.apm.base.AbstractInstrumentationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.kananindzya.elastic.apm.example.webserver.ExampleAlreadyInstrumentedHttpServer;
import com.kananindzya.elastic.apm.example.webserver.ExampleHttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

    @BeforeEach
    public void startServer() {
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

    @AfterEach
    public void stopServer() throws IOException, InterruptedException, ExecutionException {
        assertEquals(executeRequest("exit").getFirst(), 200);
        Server.stop();
        assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
            Server.blockUntilStopped();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"sync", "async"})
    public void testAsyncInstrumentation(String requestPath) throws IOException, InterruptedException, TimeoutException, ExecutionException {
        Pair<Integer, String> statusCode = executeRequest(requestPath);
        assertEquals(statusCode.getFirst(), 200);
        assertEquals(statusCode.getSecond(), "HelloWorld");

        assertTransactionAndSpan(1000);
    }

    private void assertTransactionAndSpan(long timeoutInMillis) throws TimeoutException {
        JsonNode transaction = ApmServer.getAndRemoveTransaction(0, timeoutInMillis);
        assertNotNull(transaction, "http jdk server instrumentation should creates transaction");

        JsonNode jettyRequestSpan = ApmServer.getAndRemoveSpan(0, 1000);
        assertNotNull(jettyRequestSpan, "Span should be exist");
        assertEquals("GET example.com", jettyRequestSpan.get("name").textValue(), "Span name should be set properly");
        assertEquals("success", jettyRequestSpan.get("outcome").textValue(), "Span outcome should be success");
        JsonNode spanContext = jettyRequestSpan.get("context");
        assertEquals("http", spanContext.get("service").get("target").get("type").textValue(), "Service's target type should be `http` type");
        JsonNode spanDestination = spanContext.get("destination");
        assertEquals("example.com", spanDestination.get("address").textValue(), "Address should contain called domain");
        assertEquals(443, spanDestination.get("port").intValue(), "`Port` field should captured properly.");
    }

    private static Pair<Integer, String> executeRequest(String req) throws IOException, InterruptedException, ExecutionException {
        System.out.println("Trying to get request " + req);
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        Future<Pair<Integer, String>> future = executorService.submit(() -> {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + PORT + "/" + req))
                    .GET()
                    .build();

            HttpResponse<String> response = Client.send(request, HttpResponse.BodyHandlers.ofString());
            return new Pair<>(response.statusCode(), response.body());
        });
        return future.get();
    }

}
