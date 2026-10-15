package com.cloudmusic2ktv.dlna;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.*;
import java.util.*;

/** Opens a seekable local content URI for the relay without copying it into app storage. */
final class LocalMediaSource implements HttpRelay.Source {
    private final ContentResolver resolver;
    private final Uri uri;
    private final String mime;
    private final long size;

    LocalMediaSource(ContentResolver resolver, Uri uri, String mime) throws IOException {
        this.resolver = resolver; this.uri = uri;
        this.mime = mime == null || mime.isEmpty() ? "video/mp4" : mime;
        try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "r")) {
            if (descriptor == null || descriptor.getStatSize() < 0)
                throw new IOException("本地视频不支持随机读取");
            size = descriptor.getStatSize();
        }
    }

    @Override public HttpRelay.Response open(String method, Map<String, String> requestHeaders) throws IOException {
        long start = 0, end = size - 1; boolean partial = false;
        String range = requestHeaders.get("range");
        if (range != null) {
            long[] bounds = parseRange(range, size);
            if (bounds == null) return response(416, null, 0, null);
            start = bounds[0]; end = bounds[1]; partial = true;
        }
        ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "r");
        if (descriptor == null) throw new IOException("无法打开本地视频");
        FileInputStream file = null;
        try {
            file = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
            file.getChannel().position(start);
            long length = end - start + 1;
            InputStream body = method.equals("HEAD") ? null : new LimitedInputStream(file, length);
            if (body == null) file.close();
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", mime); headers.put("Accept-Ranges", "bytes");
            headers.put("Content-Length", Long.toString(length));
            if (partial) headers.put("Content-Range", "bytes " + start + "-" + end + "/" + size);
            return new HttpRelay.Response(partial ? 206 : 200, headers, body,
                    body == null ? null : body::close);
        } catch (IOException | RuntimeException e) {
            if (file != null) try { file.close(); } catch (IOException ignored) { }
            else try { descriptor.close(); } catch (IOException ignored) { }
            throw e;
        }
    }

    private HttpRelay.Response response(int status, String ignored, long ignored2, InputStream ignored3) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Range", "bytes */" + size); headers.put("Content-Length", "0");
        return new HttpRelay.Response(status, headers, null, null);
    }

    private static long[] parseRange(String value, long size) {
        if (!value.startsWith("bytes=") || value.indexOf(',') >= 0 || size <= 0) return null;
        String range = value.substring(6).trim(); int dash = range.indexOf('-');
        if (dash < 0) return null;
        try {
            String left = range.substring(0, dash).trim(), right = range.substring(dash + 1).trim();
            long start, end;
            if (left.isEmpty()) {
                long suffix = Long.parseLong(right); if (suffix <= 0) return null;
                start = suffix >= size ? 0 : size - suffix; end = size - 1;
            } else {
                start = Long.parseLong(left); if (start < 0 || start >= size) return null;
                end = right.isEmpty() ? size - 1 : Long.parseLong(right);
                if (end < start) return null; end = Math.min(end, size - 1);
            }
            return new long[]{start, end};
        } catch (NumberFormatException e) { return null; }
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private long remaining;
        LimitedInputStream(InputStream input, long length) { super(input); remaining = length; }
        @Override public int read() throws IOException { if (remaining == 0) return -1; int value = in.read(); if (value >= 0) remaining--; return value; }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining == 0) return -1; int count = in.read(buffer, offset, (int)Math.min(length, remaining)); if (count > 0) remaining -= count; return count;
        }
    }
}
