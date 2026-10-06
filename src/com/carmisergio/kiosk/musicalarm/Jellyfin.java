// SPDX-License-Identifier: Apache-2.0
package com.carmisergio.kiosk.musicalarm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** The few Jellyfin calls the plugin needs: users, playlists, their tracks and an MP3 stream. */
final class Jellyfin {
    private static final Pattern ITEM_ID = Pattern.compile("[0-9a-fA-F]{32}|[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    private static final int MAX_JSON_BYTES = 8 * 1024 * 1024;

    static final class Track {
        final String id;
        final String title;
        final String artist;

        Track(String id, String title, String artist) {
            this.id = id;
            this.title = title;
            this.artist = artist;
        }

        /** "Artist – Title", or the title alone. */
        String describe() {
            String name = title == null || title.isEmpty() ? id : title;
            return artist == null || artist.isEmpty() ? name : artist + " – " + name;
        }
    }

    private final String base;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private final Http http;

    Jellyfin(String serverUrl, String apiKey, String version, Http http) {
        this.base = normalize(serverUrl);
        this.http = http;
        headers.put("Accept", "application/json");
        headers.put("Authorization", "MediaBrowser Client=\"Kiosk Music Alarm\", Device=\"Kiosk Satellite\", "
            + "DeviceId=\"kiosk-music-alarm\", Version=\"" + version + "\", Token=\"" + apiKey.replace("\"", "") + "\"");
    }

    static String normalize(String serverUrl) {
        String url = serverUrl.trim();
        if (!url.matches("(?i)https?://.*")) url = "http://" + url;
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    static boolean isItemId(String value) {
        return ITEM_ID.matcher(value).matches();
    }

    /** The ID of the user called [name], or null for no user. */
    String userId(String name) throws IOException {
        if (name.isEmpty()) return null;
        if (isItemId(name)) return name;
        List<Object> users = Json.array(json("/Users"));
        if (users != null) {
            for (Object user : users) {
                Map<String, Object> entry = Json.object(user);
                if (name.equalsIgnoreCase(Json.string(entry, "Name"))) return Json.string(entry, "Id");
            }
        }
        throw new IOException("No Jellyfin user is called \"" + name + "\"");
    }

    /** A playlist ID as given, or the one playlist whose name matches. */
    String playlistId(String userId, String playlist) throws IOException {
        if (isItemId(playlist)) return playlist;
        List<Object> items = items(json("/Items?includeItemTypes=Playlist&recursive=true&searchTerm="
            + encode(playlist) + user(userId)));
        String only = null;
        int matches = 0;
        for (Object item : items) {
            Map<String, Object> entry = Json.object(item);
            if (playlist.equalsIgnoreCase(Json.string(entry, "Name"))) return Json.string(entry, "Id");
            only = Json.string(entry, "Id");
            matches++;
        }
        if (matches == 1) return only;
        throw new IOException(matches == 0
            ? "No playlist is called \"" + playlist + "\""
            : matches + " playlists match \"" + playlist + "\". Enter the full name or its ID");
    }

    /** Up to [limit] of the playlist's tracks in random order. */
    List<Track> randomTracks(String userId, String playlistId, int limit) throws IOException {
        List<Track> tracks = tracks(items(json("/Items?parentId=" + encode(playlistId)
            + "&includeItemTypes=Audio&recursive=true&sortBy=Random&limit=" + limit + user(userId))));
        if (!tracks.isEmpty()) return tracks;
        // Not every server lists a playlist's entries as its children; the
        // playlist endpoint always does, in playlist order.
        tracks = tracks(items(json("/Playlists/" + encode(playlistId) + "/Items?" + user(userId).replaceFirst("^&", ""))));
        Collections.shuffle(tracks);
        return tracks.size() > limit ? new ArrayList<>(tracks.subList(0, limit)) : tracks;
    }

    /** Streams the track, transcoded to MP3 by the server, into [out]. */
    long download(Track track, OutputStream out) throws IOException {
        long total = 0;
        try (InputStream in = http.get(base + "/Audio/" + encode(track.id)
            + "/stream.mp3?audioCodec=mp3&audioBitRate=192000&static=false", headers)) {
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = in.read(buffer)) != -1; ) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Interrupted");
                out.write(buffer, 0, read);
                total += read;
            }
        }
        if (total == 0) throw new IOException("Jellyfin sent an empty file");
        return total;
    }

    private Object json(String path) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try (InputStream in = http.get(base + path, headers)) {
            byte[] buffer = new byte[16 * 1024];
            for (int read; (read = in.read(buffer)) != -1; ) {
                body.write(buffer, 0, read);
                if (body.size() > MAX_JSON_BYTES) throw new IOException("Jellyfin's answer is too large");
            }
        }
        try {
            return Json.parse(new String(body.toByteArray(), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new IOException("Jellyfin did not answer with JSON. Check the server address", e);
        }
    }

    private static List<Object> items(Object answer) {
        List<Object> items = Json.array(Json.object(answer) == null ? null : Json.object(answer).get("Items"));
        return items == null ? Collections.emptyList() : items;
    }

    private static List<Track> tracks(List<Object> items) {
        List<Track> tracks = new ArrayList<>();
        for (Object item : items) {
            Map<String, Object> entry = Json.object(item);
            String id = Json.string(entry, "Id");
            if (id == null || !"Audio".equals(Json.string(entry, "Type"))) continue;
            String artist = Json.string(entry, "AlbumArtist");
            List<Object> artists = Json.array(entry.get("Artists"));
            if (artists != null && !artists.isEmpty() && artists.get(0) instanceof String) artist = (String) artists.get(0);
            tracks.add(new Track(id, Json.string(entry, "Name"), artist));
        }
        return tracks;
    }

    private static String user(String userId) {
        return userId == null ? "" : "&userId=" + encode(userId);
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }
}
