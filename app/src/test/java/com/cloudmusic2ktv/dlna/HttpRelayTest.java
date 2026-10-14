package com.cloudmusic2ktv.dlna;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.Test;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class HttpRelayTest {
    private HttpRelay relay(String source) throws IOException {
        return new HttpRelay(InetAddress.getByName("127.0.0.1"), source,
                endpoint -> (HttpURLConnection) endpoint.openConnection(), null);
    }
    private HttpURLConnection client(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(2000); connection.setReadTimeout(2000);
        return connection;
    }

    @Test public void stoppingDoesNotWaitForAnUnresponsiveOrigin() throws Exception {
        try (MockWebServer source = new MockWebServer()) {
            source.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)); source.start();
            try (HttpRelay relay = relay(source.url("/stalled").toString())) {
                URL endpoint = new URL(relay.url());
                try (Socket socket = new Socket(endpoint.getHost(), endpoint.getPort())) {
                    socket.setSoTimeout(2000);
                    socket.getOutputStream().write(("GET " + endpoint.getPath()
                            + " HTTP/1.1\r\nHost: localhost\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    assertNotNull(source.takeRequest(2, TimeUnit.SECONDS));
                    long start = System.nanoTime();
                    relay.close();
                    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1000);
                    try { assertEquals(-1, socket.getInputStream().read()); } catch (SocketException expected) { }
                }
            }
        }
    }

    @Test public void headPreservesLengthAndDoesNotSendBody() throws Exception {
        try (MockWebServer source = new MockWebServer()) {
            source.enqueue(new MockResponse().setHeader("Content-Length", "1234").setHeader("Content-Type", "video/mp4"));
            source.start();
            try (HttpRelay relay = relay(source.url("/signed.mp4?token=private").toString())) {
                assertFalse(relay.url().contains("token"));
                HttpURLConnection request = client(relay.url()); request.setRequestMethod("HEAD");
                assertEquals(200, request.getResponseCode());
                assertEquals(1234, request.getContentLength());
                assertEquals(0, request.getInputStream().readAllBytes().length);
                assertEquals("HEAD", source.takeRequest(2, TimeUnit.SECONDS).getMethod());
                request.disconnect();
            }
        }
    }

    @Test public void followsRedirectsOnPhoneAndKeepsRange() throws Exception {
        try (MockWebServer source = new MockWebServer()) {
            source.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "/final?signature=a%2Bb&expires=1"));
            source.enqueue(new MockResponse().setResponseCode(206).setBody("last")
                    .setHeader("Content-Range", "bytes 4-7/8"));
            source.start();
            try (HttpRelay relay = relay(source.url("/start").toString())) {
                HttpURLConnection request = client(relay.url());
                request.setRequestProperty("Range", "bytes=-4");
                request.setRequestProperty("If-Range", "\"version1\"");
                assertEquals(206, request.getResponseCode());
                assertNull(request.getHeaderField("Location"));
                assertEquals("last", new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                source.takeRequest(2, TimeUnit.SECONDS);
                RecordedRequest follow = source.takeRequest(2, TimeUnit.SECONDS);
                assertEquals("/final?signature=a%2Bb&expires=1", follow.getPath());
                assertEquals("bytes=-4", follow.getHeader("Range"));
                assertEquals("\"version1\"", follow.getHeader("If-Range"));
                request.disconnect();
            }
        }
    }

    @Test public void preservesUpstreamFailuresAndIgnoredRange() throws Exception {
        try (MockWebServer source = new MockWebServer()) {
            source.enqueue(new MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */8"));
            source.enqueue(new MockResponse().setResponseCode(403).setBody("expired"));
            source.enqueue(new MockResponse().setBody("full"));
            source.start();
            try (HttpRelay relay = relay(source.url("/video").toString())) {
                HttpURLConnection request = client(relay.url()); request.setRequestProperty("Range", "bytes=100-");
                assertEquals(416, request.getResponseCode());
                assertEquals("bytes */8", request.getHeaderField("Content-Range")); request.disconnect();
                request = client(relay.url()); assertEquals(403, request.getResponseCode());
                assertEquals("expired", new String(request.getErrorStream().readAllBytes(), StandardCharsets.UTF_8)); request.disconnect();
                request = client(relay.url()); request.setRequestProperty("Range", "bytes=2-");
                assertEquals(200, request.getResponseCode());
                assertNull(request.getHeaderField("Content-Range"));
                assertEquals("full", new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8)); request.disconnect();
            }
        }
    }

    @Test public void rejectsUnknownPathsAndMethodsWithoutContactingSource() throws Exception {
        try (MockWebServer source = new MockWebServer()) {
            source.start();
            try (HttpRelay relay = relay(source.url("/video").toString())) {
                HttpURLConnection request = client(relay.url() + "?url=http://example.test");
                assertEquals(404, request.getResponseCode()); request.disconnect();
                request = client(relay.url()); request.setRequestMethod("POST");
                assertEquals(405, request.getResponseCode()); request.disconnect();
                assertEquals(0, source.getRequestCount());
            }
        }
    }

    @Test public void decodesChunkedFramingAndClosesIdleConnectionsOnStop() throws Exception {
        try (MockWebServer source = new MockWebServer()) {
            source.enqueue(new MockResponse().setChunkedBody("abcdefgh", 2)); source.start();
            HttpRelay relay = relay(source.url("/video").toString());
            String url = relay.url();
            try {
                HttpURLConnection request = client(url);
                assertEquals("abcdefgh", new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                assertNull(request.getHeaderField("Transfer-Encoding")); request.disconnect();
                URL endpoint = new URL(url);
                try (Socket idle = new Socket(endpoint.getHost(), endpoint.getPort())) {
                    idle.setSoTimeout(2000);
                    relay.close();
                    try { assertEquals(-1, idle.getInputStream().read()); } catch (SocketException expected) { }
                }
                try { client(url).getResponseCode(); fail("Listener still open"); } catch (ConnectException expected) { }
            } finally { relay.close(); }
        }
    }
}
