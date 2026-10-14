package com.cloudmusic2ktv.dlna;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URI;
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
    public final String controlUrl;

    DlnaRenderer(String name, String location, String controlUrl) {
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
        validateUrl(mediaUrl);
        String path = new URL(mediaUrl).getPath().toLowerCase(Locale.ROOT);
        String mime = path.endsWith(".mp4") ? "video/mp4" : "video/*";
        String metadata = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\""
                + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
                + " xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">"
                + "<item id=\"0\" parentID=\"-1\" restricted=\"1\">"
                + "<dc:title>URL Video</dc:title><upnp:class>object.item.videoItem</upnp:class>"
                + "<res protocolInfo=\"http-get:*:" + mime + ":*\">"
                + escapeXml(mediaUrl) + "</res></item></DIDL-Lite>";
        soap("SetAVTransportURI",
                "<InstanceID>0</InstanceID>"
                        + "<CurrentURI>" + escapeXml(mediaUrl) + "</CurrentURI>"
                        + "<CurrentURIMetaData>" + escapeXml(metadata) + "</CurrentURIMetaData>");
        resume();
    }

    public static void validateUrl(String value) throws IOException {
        try {
            URI uri = new URI(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException();
            }
        } catch (Exception error) {
            throw new IOException("请输入完整的 HTTP/HTTPS 视频直链", error);
        }
    }

    public void resume() throws IOException {
        soap("Play", "<InstanceID>0</InstanceID><Speed>1</Speed>");
    }

    public void pause() throws IOException { soap("Pause", "<InstanceID>0</InstanceID>"); }

    public void stop() throws IOException { soap("Stop", "<InstanceID>0</InstanceID>"); }

    public void seek(long seconds) throws IOException {
        if (seconds < 0) throw new IOException("无效的播放位置");
        soap("Seek", "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>"
                + formatTime(seconds) + "</Target>");
    }

    public static String formatTime(long seconds) {
        if (seconds < 0) return "--:--";
        return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }

    static long parseTime(String value) {
        if (value == null || !value.matches("\\d+:\\d{2}:\\d{2}(\\.\\d+)?")) return -1;
        try {
            String[] parts = value.split(":");
            long hours = Long.parseLong(parts[0]);
            int minutes = Integer.parseInt(parts[1]);
            double seconds = Double.parseDouble(parts[2]);
            if (minutes >= 60 || seconds >= 60 || hours > Integer.MAX_VALUE / 3600) return -1;
            return hours * 3600 + minutes * 60 + (long) seconds;
        } catch (NumberFormatException error) { return -1; }
    }

    public static final class PlaybackInfo {
        public final String state, status;
        public final long position, duration;
        PlaybackInfo(String state, String status, long position, long duration) {
            this.state = state; this.status = status; this.position = position; this.duration = duration;
        }
    }

    public PlaybackInfo getPlaybackInfo() throws IOException {
        Element transport = soap("GetTransportInfo", "<InstanceID>0</InstanceID>");
        Element position = soap("GetPositionInfo", "<InstanceID>0</InstanceID>");
        if (transport == null || position == null || text(transport, "CurrentTransportState") == null) {
            throw new IOException("设备返回了不完整的播放状态");
        }
        return new PlaybackInfo(text(transport, "CurrentTransportState"),
                text(transport, "CurrentTransportStatus"), parseTime(text(position, "RelTime")),
                parseTime(text(position, "TrackDuration")));
    }

    private Element soap(String action, String body) throws IOException {
        String xml = "<?xml version=\"1.0\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\""
                + " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
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
            Element root = null;
            InputStream stream = status / 100 == 2 ? connection.getInputStream() : connection.getErrorStream();
            if (stream != null) {
                try (InputStream input = stream; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (bytes.size() + count > 1024 * 1024) throw new IOException("设备响应过大");
                        bytes.write(buffer, 0, count);
                    }
                    if (bytes.size() > 0) {
                        try {
                            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                            factory.setNamespaceAware(true);
                            DocumentBuilder builder = factory.newDocumentBuilder();
                            builder.setEntityResolver((publicId, systemId) -> { throw new SAXException("External entity"); });
                            Document doc = builder.parse(new ByteArrayInputStream(bytes.toByteArray()));
                            if (doc.getDoctype() != null) throw new SAXException("DOCTYPE");
                            root = doc.getDocumentElement();
                        } catch (ParserConfigurationException | SAXException error) {
                            throw new IOException("设备 " + action + " 响应无法解析（HTTP " + status + "）", error);
                        }
                    }
                }
            }
            if (root != null && root.getElementsByTagNameNS("*", "Fault").getLength() > 0) {
                throw new IOException("设备拒绝 " + action + "：" + text(root, "errorCode")
                        + " " + text(root, "errorDescription"));
            }
            if (status / 100 != 2) {
                throw new IOException("DLNA " + action + " failed (HTTP " + status + ")");
            }
            return root;
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
