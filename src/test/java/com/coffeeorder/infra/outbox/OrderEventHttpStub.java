package com.coffeeorder.infra.outbox;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class OrderEventHttpStub implements AutoCloseable {

    private final HttpServer server;
    private final AtomicInteger responseStatus = new AtomicInteger(202);
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final Set<String> appliedEventIds = ConcurrentHashMap.newKeySet();
    private final AtomicReference<CountDownLatch> requestEntered = new AtomicReference<>();
    private final AtomicReference<CountDownLatch> responseRelease = new AtomicReference<>();

    OrderEventHttpStub() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/order-events", this::handle);
            server.start();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    void respondWith(int status) {
        responseStatus.set(status);
    }

    List<Request> requests() {
        return List.copyOf(requests);
    }

    int appliedCount() {
        return appliedEventIds.size();
    }

    void blockResponses(CountDownLatch entered, CountDownLatch release) {
        requestEntered.set(entered);
        responseRelease.set(release);
    }

    void reset() {
        responseStatus.set(202);
        requests.clear();
        appliedEventIds.clear();
        requestEntered.set(null);
        responseRelease.set(null);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String eventId = exchange.getRequestHeaders().getFirst("X-Event-Id");
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new Request(eventId, body));
        CountDownLatch entered = requestEntered.get();
        CountDownLatch release = responseRelease.get();
        if (entered != null && release != null) {
            entered.countDown();
            await(release);
        }
        int status = responseStatus.get();
        if (status >= 200 && status < 300) {
            appliedEventIds.add(eventId);
        }
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("HTTP stub release timeout");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    record Request(String eventId, String body) {}
}
