package com.cloudmusic2ktv.dlna;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.junit.Test;
import org.w3c.dom.Document;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import java.io.IOException;
import java.net.InetAddress;
import java.net.HttpURLConnection;
import java.net.URL;

public class DlnaRendererTest {
    private static final String AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1";

    @Test public void relayStreamsRangeAndKeepsSourceQueryPrivate() throws Exception {
        try (MockWebServer source = new MockWebServer()) {
            source.enqueue(new MockResponse().setResponseCode(206).setHeader("Content-Type", "video/mp4")
                    .setHeader("Content-Length", "4").setHeader("Content-Range", "bytes 2-5/8")
                    .setHeader("Accept-Ranges", "bytes").setBody("cdef"));
            source.start();
            String secret = source.url("/video.mp4?signature=secret").toString();
            try (HttpRelay relay = new HttpRelay(InetAddress.getLoopbackAddress(), secret,
                    endpoint -> (HttpURLConnection) endpoint.openConnection(),
                    (method, status, bytes) -> { })) {
                HttpURLConnection client = (HttpURLConnection) new URL(relay.url()).openConnection();
                client.setRequestProperty("Range", "bytes=2-");
                assertEquals(206, client.getResponseCode());
                assertEquals("cdef", new String(client.getInputStream().readAllBytes(), StandardCharsets.US_ASCII));
                assertEquals("bytes 2-5/8", client.getHeaderField("Content-Range"));
                RecordedRequest request = source.takeRequest(3, TimeUnit.SECONDS);
                assertNotNull(request);
                assertEquals("/video.mp4?signature=secret", request.getPath());
                assertEquals("bytes=2-", request.getHeader("Range"));
            }
        }
    }

    @Test
    public void discoversServicesInDefaultAndPrefixedNamespaces() throws Exception {
        for (String prefix : Arrays.asList("", "upnp:")) {
            DlnaRenderer renderer = parse(description(prefix, "", AV_TRANSPORT, "/control"));
            assertNotNull(renderer);
            assertEquals("Test renderer", renderer.name);
        }
    }

    @Test
    public void ignoresDevicesWithoutAvTransport() throws Exception {
        assertNull(parse(description("", "", "urn:schemas-upnp-org:service:ContentDirectory:1", "/control")));
    }

    @Test
    public void sendsEscapedUrlThenPlayToUrlBaseControlEndpoint() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody(""));
            server.enqueue(new MockResponse().setBody(""));
            server.start();
            String base = server.url("/renderer/").toString();
            DlnaRenderer renderer = parse(description("", base, AV_TRANSPORT, "control"));
            String mediaUrl = "https://example.test/video.mp4?expires=123&signature=abc";
            renderer.play(mediaUrl);

            RecordedRequest setUriRequest = server.takeRequest(3, TimeUnit.SECONDS);
            RecordedRequest playRequest = server.takeRequest(3, TimeUnit.SECONDS);
            assertNotNull(setUriRequest);
            assertNotNull(playRequest);
            assertEquals("/renderer/control", setUriRequest.getPath());
            assertEquals("/renderer/control", playRequest.getPath());
            assertEquals("\"" + AV_TRANSPORT + "#SetAVTransportURI\"", setUriRequest.getHeader("SOAPACTION"));
            assertEquals("\"" + AV_TRANSPORT + "#Play\"", playRequest.getHeader("SOAPACTION"));
            Document setUri = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(setUriRequest.getBody().inputStream());
            assertEquals(mediaUrl, setUri.getElementsByTagName("CurrentURI").item(0).getTextContent());
            String metadata = setUri.getElementsByTagName("CurrentURIMetaData").item(0).getTextContent();
            Document didl = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(new ByteArrayInputStream(metadata.getBytes(StandardCharsets.UTF_8)));
            assertEquals(mediaUrl, didl.getElementsByTagName("res").item(0).getTextContent());
            assertEquals("http://schemas.xmlsoap.org/soap/encoding/",
                    setUri.getDocumentElement().getAttribute("s:encodingStyle"));
        }
    }

    @Test public void controlsAndQueriesRealDeviceState() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            for (int i = 0; i < 4; i++) server.enqueue(new MockResponse());
            server.enqueue(new MockResponse().setBody(envelope("<u:GetTransportInfoResponse xmlns:u=\"" + AV_TRANSPORT
                    + "\"><CurrentTransportState>PAUSED_PLAYBACK</CurrentTransportState>"
                    + "<CurrentTransportStatus>OK</CurrentTransportStatus></u:GetTransportInfoResponse>")));
            server.enqueue(new MockResponse().setBody(envelope("<GetPositionInfoResponse>"
                    + "<RelTime>00:01:23.45</RelTime><TrackDuration>01:02:03</TrackDuration></GetPositionInfoResponse>")));
            server.start();
            DlnaRenderer renderer = parse(description("", server.url("/").toString(), AV_TRANSPORT, "control"));
            renderer.pause(); renderer.resume(); renderer.seek(83); renderer.stop();
            DlnaRenderer.PlaybackInfo info = renderer.getPlaybackInfo();
            assertEquals("PAUSED_PLAYBACK", info.state);
            assertEquals(83, info.position); assertEquals(3723, info.duration);
            String[] actions = {"Pause", "Play", "Seek", "Stop", "GetTransportInfo", "GetPositionInfo"};
            for (String action : actions) {
                RecordedRequest request = server.takeRequest(3, TimeUnit.SECONDS);
                assertNotNull(request);
                assertEquals("\"" + AV_TRANSPORT + "#" + action + "\"", request.getHeader("SOAPACTION"));
                if (action.equals("Seek")) {
                    String body = request.getBody().readUtf8();
                    assertTrue(body.contains("<Unit>REL_TIME</Unit><Target>00:01:23</Target>"));
                }
            }
        }
    }

    @Test public void soapFaultPreventsPlayAndExplainsFailure() throws Exception {
        for (int status : new int[]{200, 500}) {
            try (MockWebServer server = new MockWebServer()) {
                server.enqueue(new MockResponse().setResponseCode(status).setBody(envelope(
                        "<s:Fault><detail><UPnPError><errorCode>714</errorCode>"
                        + "<errorDescription>Illegal MIME-type</errorDescription></UPnPError></detail></s:Fault>")));
                server.start();
                DlnaRenderer renderer = parse(description("", server.url("/").toString(), AV_TRANSPORT, "control"));
                try { renderer.play("https://example.test/video.mp4"); fail("Fault was accepted"); }
                catch (IOException error) {
                    assertTrue(error.getMessage().contains("714"));
                    assertTrue(error.getMessage().contains("Illegal MIME-type"));
                }
                assertEquals(1, server.getRequestCount());
            }
        }
    }

    @Test public void unknownTimeIsNotMistakenForZeroAndUrlsAreValidated() throws Exception {
        assertEquals(-1, DlnaRenderer.parseTime("NOT_IMPLEMENTED"));
        assertEquals(-1, DlnaRenderer.parseTime("00:99:00"));
        assertEquals(0, DlnaRenderer.parseTime("00:00:00"));
        DlnaRenderer.validateUrl("https://example.test/v.mp4?a=1&b=2");
        for (String value : new String[]{"file:///video.mp4", "https://", "hello", "http://example.test/a b"}) {
            try { DlnaRenderer.validateUrl(value); fail("Accepted invalid URL"); }
            catch (IOException expected) { /* Input rejected before any SOAP request. */ }
        }
    }

    private static String envelope(String body) {
        return "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>"
                + body + "</s:Body></s:Envelope>";
    }

    private static DlnaRenderer parse(String xml) throws Exception {
        return DlnaRenderer.parseDescription(
                "http://127.0.0.1/device/description.xml",
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))
        );
    }

    private static String description(String prefix, String base, String service, String control) {
        String namespace = prefix.isEmpty() ? "xmlns" : "xmlns:upnp";
        return "<" + prefix + "root " + namespace + "=\"urn:schemas-upnp-org:device-1-0\">"
                + element(prefix, "URLBase", base)
                + "<" + prefix + "device>"
                + element(prefix, "friendlyName", "Test renderer")
                + "<" + prefix + "serviceList><" + prefix + "service>"
                + element(prefix, "serviceType", service)
                + element(prefix, "controlURL", control)
                + "</" + prefix + "service></" + prefix + "serviceList>"
                + "</" + prefix + "device></" + prefix + "root>";
    }

    private static String element(String prefix, String name, String value) {
        return "<" + prefix + name + ">" + value + "</" + prefix + name + ">";
    }
}
