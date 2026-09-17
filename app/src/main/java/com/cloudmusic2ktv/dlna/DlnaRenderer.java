package com.cloudmusic2ktv.dlna;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.ParserConfigurationException;

public final class DlnaRenderer {
    private static final Logger LOGGER = Logger.getLogger("UrlDisplayDlna");
    private static final String AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1";
    private static final int HTTP_TIMEOUT_MS = 3000;

    public final String name;
    public final String location;
    private final String controlUrl;

    private DlnaRenderer(String name, String location, String controlUrl) {
        this.name = name;
        this.location = location;
        this.controlUrl = controlUrl;
    }

    public static List<DlnaRenderer> discover(int timeoutMs) throws IOException {
        List<DlnaRenderer> renderers = new ArrayList<>();
        String searchRequest = "M-SEARCH * HTTP/1.1\r\n"
                + "HOST: 239.255.255.250:1900\r\n"
                + "MAN: \"ssdp:discover\"\r\n"
                + "MX: 2\r\n"
                + "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n";
        byte[] requestBytes = searchRequest.getBytes(StandardCharsets.US_ASCII);
        Set<String> locations = new HashSet<>();

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.send(new DatagramPacket(
                    requestBytes,
                    requestBytes.length,
                    InetAddress.getByName("239.255.255.250"),
                    1900
            ));
            long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
            while (System.nanoTime() < deadline) {
                long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
                socket.setSoTimeout((int) Math.max(1, remainingMs));
                try {
                    byte[] responseBytes = new byte[4096];
                    DatagramPacket response = new DatagramPacket(responseBytes, responseBytes.length);
                    socket.receive(response);
                    String headers = new String(
                            response.getData(), 0, response.getLength(), StandardCharsets.ISO_8859_1
                    );
                    String location = header(headers, "location");
                    if (location != null) {
                        locations.add(location);
                    }
                } catch (SocketTimeoutException error) {
                    break;
                }
            }
        }

        // Fetch descriptions after discovery, so slow HTTP responses do not
        // prevent us from collecting other devices' SSDP replies.
        for (String location : locations) {
            try {
                DlnaRenderer renderer = fromDescription(location);
                if (renderer != null) {
                    renderers.add(renderer);
                }
            } catch (IOException error) {
                LOGGER.log(Level.WARNING, "Unable to read a renderer description", error);
            }
        }
        return renderers;
    }

    private static DlnaRenderer fromDescription(String location) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(location).openConnection();
        connection.setConnectTimeout(HTTP_TIMEOUT_MS);
        connection.setReadTimeout(HTTP_TIMEOUT_MS);
        try (InputStream input = connection.getInputStream()) {
            return parseDescription(location, input);
        } finally {
            connection.disconnect();
        }
    }

    static DlnaRenderer parseDescription(String location, InputStream input) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // UPnP descriptions use XML namespaces. Without this flag the
            // getElementsByTagNameNS queries silently miss AVTransport services.
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> {
                throw new SAXException("External entities are not supported in DLNA descriptions");
            });
            Document document = builder.parse(input);
            Element root = document.getDocumentElement();
            if (document.getDoctype() != null) {
                throw new IOException("DLNA device descriptions must not contain a DOCTYPE");
            }
            String name = text(root, "friendlyName");
            String urlBase = text(root, "URLBase");
            URL base = new URL(location);
            if (urlBase != null && !urlBase.isEmpty()) {
                base = new URL(base, urlBase);
            }
            NodeList services = root.getElementsByTagNameNS("*", "service");
            for (int index = 0; index < services.getLength(); index++) {
                Element service = (Element) services.item(index);
                if (AV_TRANSPORT.equals(text(service, "serviceType"))) {
                    String controlPath = text(service, "controlURL");
                    if (controlPath != null && !controlPath.isEmpty()) {
                        return new DlnaRenderer(name, location, new URL(base, controlPath).toString());
                    }
                }
            }
            return null;
        } catch (ParserConfigurationException | SAXException error) {
            throw new IOException("无法解析 DLNA 设备描述", error);
        }
    }

    private static String text(Element element, String tag) {
        NodeList matches = element.getElementsByTagNameNS("*", tag);
        return matches.getLength() == 0 ? null : matches.item(0).getTextContent().trim();
    }

    private static String header(String headers, String key) {
        for (String line : headers.split("\\r?\\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith(key + ":")) {
                return line.substring(key.length() + 1).trim();
            }
        }
        return null;
    }

    public void play(String mediaUrl) throws IOException {
        soap("SetAVTransportURI",
                "<InstanceID>0</InstanceID>"
                        + "<CurrentURI>" + escapeXml(mediaUrl) + "</CurrentURI>"
                        + "<CurrentURIMetaData></CurrentURIMetaData>");
        soap("Play", "<InstanceID>0</InstanceID><Speed>1</Speed>");
    }

    private void soap(String action, String body) throws IOException {
        String xml = "<?xml version=\"1.0\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<s:Body><u:" + action + " xmlns:u=\"" + AV_TRANSPORT + "\">"
                + body
                + "</u:" + action + "></s:Body></s:Envelope>";
        HttpURLConnection connection = (HttpURLConnection) new URL(controlUrl).openConnection();
        connection.setConnectTimeout(HTTP_TIMEOUT_MS);
        connection.setReadTimeout(HTTP_TIMEOUT_MS);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
        connection.setRequestProperty("SOAPACTION", "\"" + AV_TRANSPORT + "#" + action + "\"");
        try {
            try (OutputStream output = connection.getOutputStream()) {
                output.write(xml.getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            if (status / 100 != 2) {
                throw new IOException("DLNA " + action + " failed (HTTP " + status + ")");
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    @Override
    public String toString() {
        return name == null ? location : name;
    }
}
