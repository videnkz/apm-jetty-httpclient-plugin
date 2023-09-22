package com.kananindzya.elastic.apm.example.webserver;

public interface ExampleHttpServer {
    public void blockUntilReady();

    public void blockUntilStopped();

    public void stop();

    public void start() throws Exception;

    public int getLocalPort();
}
