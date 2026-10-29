package se.fileconverter.chromium;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Minimal klient för Chrome DevTools Protocol över WebSocket. Kommandon skickas med ett id och
 * svaret matchas mot det; händelser delas ut till lyssnare per session ({@code flatten}-läge).
 */
final class CdpClient implements AutoCloseable {

    static final JsonMapper JSON = JsonMapper.builder().build();

    /** Fel som Chromium själv rapporterade för ett kommando. */
    static final class CdpException extends IOException {
        CdpException(String message) {
            super(message);
        }
    }

    private final WebSocket socket;
    private final AtomicInteger ids = new AtomicInteger();
    private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final List<BiConsumer<String, JsonNode>> listeners = new CopyOnWriteArrayList<>();
    private final CompletableFuture<Void> closed = new CompletableFuture<>();

    private CdpClient(WebSocket socket) {
        this.socket = socket;
    }

    static CdpClient connect(URI endpoint, Duration timeout) throws IOException {
        Listener listener = new Listener();
        try {
            WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
                    .connectTimeout(timeout)
                    .buildAsync(endpoint, listener)
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            CdpClient client = new CdpClient(socket);
            listener.client.complete(client);
            return client;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Avbruten under anslutning till Chromium", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("Kunde inte ansluta till Chromium: " + e.getMessage(), e);
        }
    }

    boolean isOpen() {
        return !closed.isDone();
    }

    /** Lyssnar på händelser: (sessionId eller null, meddelandet med method och params). */
    Runnable onEvent(BiConsumer<String, JsonNode> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    ObjectNode params() {
        return JSON.createObjectNode();
    }

    JsonNode send(String method, ObjectNode params, String sessionId, Duration timeout) throws IOException {
        try {
            return sendAsync(method, params, sessionId).get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Avbruten i väntan på Chromium", e);
        } catch (TimeoutException e) {
            throw new ChromiumTimeoutException(method);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
        }
    }

    CompletableFuture<JsonNode> sendAsync(String method, ObjectNode params, String sessionId) {
        int id = ids.incrementAndGet();
        ObjectNode message = JSON.createObjectNode();
        message.put("id", id);
        message.put("method", method);
        message.set("params", params == null ? JSON.createObjectNode() : params);
        if (sessionId != null) {
            message.put("sessionId", sessionId);
        }
        CompletableFuture<JsonNode> result = new CompletableFuture<>();
        if (closed.isDone()) {
            result.completeExceptionally(new IOException("Anslutningen till Chromium är stängd"));
            return result;
        }
        pending.put(id, result);
        synchronized (socket) {
            socket.sendText(message.toString(), true).exceptionally(error -> {
                pending.remove(id);
                result.completeExceptionally(new IOException("Kunde inte skicka till Chromium", error));
                return null;
            }).join();
        }
        return result;
    }

    private void receive(String text) {
        JsonNode message = JSON.readTree(text);
        if (message.has("id")) {
            CompletableFuture<JsonNode> result = pending.remove(message.get("id").asInt());
            if (result == null) {
                return;
            }
            if (message.has("error")) {
                result.completeExceptionally(new CdpException(message.get("error").path("message").asString()));
            } else {
                result.complete(message.path("result"));
            }
            return;
        }
        String sessionId = message.has("sessionId") ? message.get("sessionId").asString() : null;
        for (BiConsumer<String, JsonNode> listener : listeners) {
            listener.accept(sessionId, message);
        }
    }

    private void closedRemotely(Throwable cause) {
        closed.complete(null);
        IOException error = new IOException("Anslutningen till Chromium bröts", cause);
        pending.values().forEach(result -> result.completeExceptionally(error));
        pending.clear();
    }

    @Override
    public void close() {
        if (!closed.isDone()) {
            closed.complete(null);
            socket.abort();
        }
    }

    /** Tidsgränsen för ett kommando eller en väntan passerades. */
    static final class ChromiumTimeoutException extends IOException {
        ChromiumTimeoutException(String what) {
            super("Chromium svarade inte i tid (" + what + ")");
        }
    }

    private static final class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();
        /** Sätts när anslutningen är klar; meddelanden som kommer innan dess väntar på den. */
        private final CompletableFuture<CdpClient> client = new CompletableFuture<>();

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String text = buffer.toString();
                buffer.setLength(0);
                client.thenAccept(connected -> connected.receive(text));
            }
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            client.thenAccept(connected -> connected.closedRemotely(null));
            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            client.thenAccept(connected -> connected.closedRemotely(error));
        }
    }
}
