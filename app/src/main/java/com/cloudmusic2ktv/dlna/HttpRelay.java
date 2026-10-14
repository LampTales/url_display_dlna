package com.cloudmusic2ktv.dlna;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** A bounded, streaming HTTP endpoint for exactly one media URL. No disk cache or URL query API. */
final class HttpRelay implements AutoCloseable {
    interface Connections { HttpURLConnection open(URL url) throws IOException; }
    interface Observer { void completed(String method, int status, long bytes); }
    private final URL source;
    private final Connections connections;
    private final Observer observer;
    private final ServerSocket server;
    private final String path;
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private final Set<HttpURLConnection> upstreams = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), r -> new Thread(r, "media-relay-transfer"));
    private volatile boolean closed;

    HttpRelay(InetAddress address, String mediaUrl, Connections connections, Observer observer) throws IOException {
        DlnaRenderer.validateUrl(mediaUrl);
        source = new URL(mediaUrl);
        this.connections = connections;
        this.observer = observer;
        String extension = source.getPath().toLowerCase(Locale.ROOT);
        extension = extension.endsWith(".mp4") ? ".mp4" : extension.endsWith(".avi") ? ".avi" : "";
        path = "/video/" + UUID.randomUUID().toString() + extension;
        server = new ServerSocket();
        try { server.bind(new InetSocketAddress(address, 0)); }
        catch (IOException e) { server.close(); workers.shutdownNow(); throw e; }
        new Thread(this::accept, "media-relay-listener").start();
    }

    String url() { return "http://" + server.getInetAddress().getHostAddress() + ":" + server.getLocalPort() + path; }

    private void accept() {
        while (!closed) {
            try {
                Socket client = server.accept();
                clients.add(client);
                if (closed) { closeClient(client); break; }
                try { workers.execute(() -> serve(client)); }
                catch (RejectedExecutionException e) { closeClient(client); }
            } catch (IOException e) { if (!closed) close(); }
        }
    }

    private void serve(Socket client) {
        HttpURLConnection upstream = null;
        int status = 502;
        long copied = 0;
        String method = "UNKNOWN";
        boolean headersSent = false;
        try {
            client.setSoTimeout(10000);
            BufferedInputStream input = new BufferedInputStream(client.getInputStream());
            OutputStream output = client.getOutputStream();
            String header = readHeader(input);
            String[] lines = header.split("\r\n");
            String[] request = lines[0].split(" ");
            if (request.length != 3) { status = 400; reply(output, status); return; }
            method = request[0];
            if (!path.equals(request[1])) { status = 404; reply(output, status); return; }
            if (!method.equals("GET") && !method.equals("HEAD")) { status = 405; reply(output, status); return; }
            Map<String, String> forwarded = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon < 1) { status = 400; reply(output, status); return; }
                String key = lines[i].substring(0, colon).toLowerCase(Locale.ROOT);
                if (key.equals("range") || key.equals("if-range")) {
                    if (forwarded.put(key, lines[i].substring(colon + 1).trim()) != null) {
                        status = 400; reply(output, status); return;
                    }
                }
            }
            upstream = open(method, forwarded);
            status = upstream.getResponseCode();
            StringBuilder response = new StringBuilder("HTTP/1.1 ").append(status).append(" Relay\r\n")
                    .append("Connection: close\r\n");
            for (String key : new String[]{"Content-Type", "Content-Length", "Content-Range", "Accept-Ranges",
                    "Content-Encoding", "ETag", "Last-Modified"}) {
                String value = upstream.getHeaderField(key);
                if (value != null && value.indexOf('\r') < 0 && value.indexOf('\n') < 0)
                    response.append(key).append(": ").append(value).append("\r\n");
            }
            // HttpURLConnection decodes chunked transfer framing; a missing length is close-delimited.
            output.write(response.append("\r\n").toString().getBytes(StandardCharsets.ISO_8859_1));
            headersSent = true;
            if (method.equals("GET") && status != 204 && status != 304) {
                InputStream body = status >= 400 ? upstream.getErrorStream() : upstream.getInputStream();
                if (body != null) try (InputStream media = body) {
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while (!closed && (count = media.read(buffer)) != -1) {
                        output.write(buffer, 0, count); copied += count;
                    }
                }
            }
            output.flush();
        } catch (IOException | IllegalArgumentException e) {
            if (!headersSent) try { reply(client.getOutputStream(), status = 502); } catch (IOException ignored) { }
        } finally {
            if (upstream != null) { upstreams.remove(upstream); upstream.disconnect(); }
            closeClient(client);
            if (observer != null) observer.completed(method, status, copied);
        }
    }

    private HttpURLConnection open(String method, Map<String, String> headers) throws IOException {
        URL current = source;
        for (int redirects = 0; redirects <= 5; redirects++) {
            DlnaRenderer.validateUrl(current.toExternalForm());
            HttpURLConnection connection = connections.open(current);
            upstreams.add(connection);
            try {
                if (closed) throw new IOException("Relay stopped");
                connection.setConnectTimeout(10000); connection.setReadTimeout(20000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod(method);
                connection.setRequestProperty("Accept-Encoding", "identity");
                for (Map.Entry<String, String> entry : headers.entrySet())
                    connection.setRequestProperty(entry.getKey(), entry.getValue());
                int status = connection.getResponseCode();
                if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) return connection;
                String next = connection.getHeaderField("Location");
                if (next == null) throw new IOException("Missing redirect location");
                current = new URL(current, next);
            } catch (IOException | RuntimeException e) {
                upstreams.remove(connection); connection.disconnect(); throw e;
            }
            upstreams.remove(connection); connection.disconnect();
        }
        throw new IOException("Too many redirects");
    }

    private static String readHeader(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int tail = 0;
        while (bytes.size() < 16384) {
            int value = input.read();
            if (value < 0) throw new EOFException();
            bytes.write(value); tail = (tail << 8) | value;
            if (tail == 0x0d0a0d0a) return bytes.toString("ISO-8859-1");
        }
        throw new IOException("Request header too large");
    }

    private static void reply(OutputStream output, int status) throws IOException {
        output.write(("HTTP/1.1 " + status + " Relay\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
    }

    private void closeClient(Socket client) {
        clients.remove(client);
        try { client.close(); } catch (IOException ignored) { }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try { server.close(); } catch (IOException ignored) { }
        for (Socket client : clients) closeClient(client);
        workers.shutdownNow();
        // Some HTTP implementations wait for a blocked read during disconnect. Never block
        // the Activity/service main thread on an unreachable origin while stopping casting.
        new Thread(() -> {
            for (HttpURLConnection connection : upstreams) connection.disconnect();
        }, "media-relay-close").start();
    }
}
