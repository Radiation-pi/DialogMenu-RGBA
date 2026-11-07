package dev.dialogmenu.editor;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** JSON messages and bounded request tracking shared by all loader adapters. */
public final class EditorProtocol {
    private final EditorWire.Receiver receiver = new EditorWire.Receiver();
    private final Map<Long, CompletableResult> pending = new HashMap<>();
    private long nextRequest = 1;
    private java.util.function.Consumer<JsonObject> events = event -> {};

    public interface CompletableResult { void complete(JsonObject response); }
    public synchronized void onEvent(java.util.function.Consumer<JsonObject> handler) { events = handler; }
    public synchronized boolean waiting() { return !pending.isEmpty(); }
    public synchronized long request(String operation, JsonObject fields, CompletableResult callback) {
        long id = nextRequest++;
        fields.addProperty("request", id);
        fields.addProperty("op", operation);
        pending.put(id, callback);
        return id;
    }
    public synchronized void receive(byte[] frame, long now) throws IOException {
        byte[] message = receiver.accept(frame, now);
        if (message == null) return;
        JsonObject response = JsonParser.parseString(new String(message, StandardCharsets.UTF_8)).getAsJsonObject();
        if (response.has("event")) { events.accept(response); return; }
        if (!response.has("request")) throw new IOException("Editor response is missing its request ID");
        CompletableResult callback = pending.remove(response.get("request").getAsLong());
        if (callback != null) callback.complete(response);
    }
    public synchronized void forgetAll() {
        pending.clear(); receiver.clear();
    }
    public static List<byte[]> frames(JsonObject message) throws IOException {
        return EditorWire.frames(message.toString().getBytes(StandardCharsets.UTF_8));
    }
}
