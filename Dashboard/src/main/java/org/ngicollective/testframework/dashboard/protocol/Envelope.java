package org.ngicollective.testframework.dashboard.protocol;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The one message shape on the wire: {@code {"namespace": ..., "type": ..., "payload": {...}}}.
 *
 * <p>Mirrors the envelope the SDK's own {@code CoreRobotWebServer} uses, so the emulator-hosted
 * backend can speak the identical protocol later without a translation layer.</p>
 *
 * <p>Namespaces are {@code opmode}, {@code telemetry}, {@code gamepad} and {@code device}. A request
 * that fails is answered in its own namespace with type {@code error} and a {@code message}.</p>
 */
public final class Envelope {

    public final String namespace;
    public final String type;
    public final JsonElement payload;

    public Envelope(String namespace, String type, JsonElement payload) {
        this.namespace = namespace;
        this.type = type;
        this.payload = payload;
    }

    public static Envelope error(String namespace, String message) {
        JsonObject payload = new JsonObject();
        payload.addProperty("message", message);
        return new Envelope(namespace, "error", payload);
    }

    @Override
    public String toString() {
        return namespace + "/" + type;
    }
}
