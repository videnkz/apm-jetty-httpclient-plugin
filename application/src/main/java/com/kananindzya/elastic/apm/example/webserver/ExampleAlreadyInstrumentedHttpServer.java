package com.kananindzya.elastic.apm.example.webserver;


import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * This uses the HttpServer embedded in the JDK. This HTTP server is already
 * instrumented by the Elastic Java agent in the apm-jdk-httpserver-plugin at
 * https://github.com/elastic/apm-agent-java/tree/master/apm-agent-plugins/apm-jdk-httpserver-plugin
 * <p>
 * This class is only here as a reference for you to compare agent internal
 * instrumentation against the instrumentation implemented here for the
 * other `com.kananindzya.elastic.apm.example.webserver.ExampleHttpServer`
 */
public class ExampleAlreadyInstrumentedHttpServer implements ExampleHttpServer {
    private static volatile HttpServer TheServerInstance;
    private HttpServer thisServer;

    @Override
    public int getLocalPort() {
        return TheServerInstance == null ? -1 : TheServerInstance.getAddress().getPort();
    }

    @Override
    public synchronized void start() throws Exception {
        if (TheServerInstance != null) {
            throw new IOException("com.kananindzya.elastic.apm.example.webserver.ExampleHttpServer: Ooops, you can't start this instance more than once");
        }
        InetSocketAddress addr = new InetSocketAddress("0.0.0.0", 0);
        thisServer = HttpServer.create(addr, 10);

        HttpClient client = new HttpClient();
        client.start();

        MyHttpHandler[] handlers = new MyHttpHandler[]{
                new SyncHandler(client), new AsyncHandler(client), new ExitHandler(), //order matters
        };
        for (MyHttpHandler httpHandler : handlers) {
            thisServer.createContext(httpHandler.getContext(), httpHandler);
        }
        System.out.println("com.kananindzya.elastic.apm.example.webserver.ExampleAlreadyInstrumentedHttpServer: Starting new webservice on port " + thisServer.getAddress().getPort());
        thisServer.setExecutor(null);
        thisServer.start();
        TheServerInstance = thisServer;
    }

    public void stop() {
        thisServer.stop(1);
        try {
            Thread.sleep(2000L);
        } catch (InterruptedException e1) {
        }
        TheServerInstance = null;
    }

    @Override
    public void blockUntilReady() {
        while (TheServerInstance == null) {
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                // do nothing, just enter the next sleep
            }
        }
    }

    @Override
    public void blockUntilStopped() {
        while (TheServerInstance != null) {
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                // do nothing, just enter the next sleep
            }
        }
    }

    abstract static class MyHttpHandler implements HttpHandler {
        public abstract String getContext();

        public abstract void myHandle(HttpExchange t) throws Exception;

        public void handle(HttpExchange t) {
            try {
                myHandle(t);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

    }

    public static class SyncHandler extends MyHttpHandler {

        private final HttpClient httpClient;

        public SyncHandler(final HttpClient httpClient) {
            this.httpClient = httpClient;
        }

        @Override
        public String getContext() {
            return "/sync";
        }

        public void myHandle(HttpExchange t) throws IOException, ExecutionException, InterruptedException, TimeoutException {
            this.httpClient.GET("https://example.com");
            String response = "HelloWorld";
            t.sendResponseHeaders(200, response.length());
            OutputStream os = t.getResponseBody();
            os.write(response.getBytes());
            os.close();
        }
    }

    public static class AsyncHandler extends MyHttpHandler {

        private final HttpClient httpClient;

        public AsyncHandler(final HttpClient httpClient) {
            this.httpClient = httpClient;
        }

        @Override
        public String getContext() {
            return "/async";
        }

        public void myHandle(HttpExchange t) throws IOException, InterruptedException {
            CountDownLatch countDownLatch = new CountDownLatch(1);
            this.httpClient.newRequest("https://example.com")
                    .send(new Response.CompleteListener() {
                        @Override
                        public void onComplete(Result result) {
                            // ignore
                            countDownLatch.countDown();
                        }
                    });
            countDownLatch.await();
            String response = "HelloWorld";
            t.sendResponseHeaders(200, response.length());
            OutputStream os = t.getResponseBody();
            os.write(response.getBytes());
            os.close();
        }
    }

    public static class ExitHandler extends MyHttpHandler {

        private static final int STOP_TIME = 3;

        @Override
        public String getContext() {
            return "/exit";
        }

        @Override
        public void myHandle(HttpExchange t) throws IOException {
            String response = "Exit";
            t.sendResponseHeaders(200, response.length());
            OutputStream os = t.getResponseBody();
            os.write(response.getBytes());
            os.close();
            TheServerInstance.stop(STOP_TIME);
            TheServerInstance = null;
        }
    }

}
