package com.ilubox.descargapda.data;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;

/** No redirects, cleartext fallback, trust-all certificates or disabled hostname checks. */
public final class LanClient {
    public static String origin(String value) throws Exception {
        URL url = new URL(value.trim());
        if (!"https".equals(url.getProtocol()) || url.getHost().isEmpty() || url.getUserInfo() != null
                || url.getQuery() != null || url.getRef() != null || !(url.getPath().isEmpty() || "/".equals(url.getPath())))
            throw new IllegalArgumentException("Use https://servidor:puerto sin ruta ni credenciales");
        return "https://" + url.getAuthority();
    }
    public static JSONObject request(String server, String id, String token, String device, String action, byte[] body) throws Exception {
        if (!id.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("Asignación inválida");
        HttpsURLConnection connection = (HttpsURLConnection) new URL(origin(server) + "/api/sessions/" + id + "/" + action).openConnection();
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("claim".equals(action) ? "POST" : "PUT");
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("X-Ilubox-Device", device);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(body.length);
        try {
            try (java.io.OutputStream output = connection.getOutputStream()) { output.write(body); }
            int status = connection.getResponseCode();
            InputStream input = status == 200 ? connection.getInputStream() : connection.getErrorStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            if (input != null) try (InputStream stream = input) {
                byte[] bytes = new byte[8192]; int n;
                while ((n = stream.read(bytes)) != -1) {
                    if (buffer.size() + n > 4 * 1024 * 1024) throw new IllegalStateException("Respuesta del servidor demasiado grande");
                    buffer.write(bytes, 0, n);
                }
            }
            String response = buffer.toString(StandardCharsets.UTF_8.name());
            if (status != 200) {
                String message = "Servidor HTTP " + status;
                try { message += ": " + new JSONObject(response).optString("detail", "Revise la configuración"); } catch (Exception ignored) { }
                throw new IllegalStateException(message);
            }
            return new JSONObject(response);
        } finally { connection.disconnect(); }
    }
}
