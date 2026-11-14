package org.aadi.triptourworld.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** Tiny in-process HTTP server used to test the Groq / tool / LangSmith clients without the internet. */
public final class FakeHttpServer implements AutoCloseable {
    public record Req(String method, String path, String query, String body, String authorization, String apiKeyHeader) {}
    public record Resp(int status, String body) {}

    private final HttpServer server;
    public final List<Req> requests = new CopyOnWriteArrayList<>();

    public FakeHttpServer(Function<Req, Resp> handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", (HttpExchange ex) -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Req req = new Req(ex.getRequestMethod(), ex.getRequestURI().getPath(), ex.getRequestURI().getRawQuery(), body,
                    ex.getRequestHeaders().getFirst("Authorization"), ex.getRequestHeaders().getFirst("x-api-key"));
            requests.add(req);
            Resp r = handler.apply(req);
            byte[] out = r.body().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(r.status(), out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<Req> received() {
        return new ArrayList<>(requests);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
