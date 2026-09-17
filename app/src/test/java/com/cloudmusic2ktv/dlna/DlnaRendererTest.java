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

public class DlnaRendererTest {
    private static final String AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1";

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
        }
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
