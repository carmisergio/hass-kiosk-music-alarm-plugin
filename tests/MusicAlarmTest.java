// SPDX-License-Identifier: Apache-2.0
package com.carmisergio.kiosk.musicalarm;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import me.jxl.kiosk.plugins.PluginHost;

/** Exercises the plugin against a fake Jellyfin and a temporary files folder. */
public final class MusicAlarmTest {
    static final String PLAYLIST_ID = "0123456789abcdef0123456789abcdef";
    static final String USER_ID = "fedcba9876543210fedcba9876543210";

    /** Answers by the first route whose key the URL contains. */
    static final class FakeJellyfin implements Http {
        final Map<String, String> routes = new LinkedHashMap<>();
        final List<String> requests = Collections.synchronizedList(new ArrayList<String>());
        volatile String authorization;

        public InputStream get(String url, Map<String, String> headers) throws IOException {
            requests.add(url);
            authorization = headers.get("Authorization");
            for (Map.Entry<String, String> route : routes.entrySet()) {
                if (url.contains(route.getKey())) {
                    return new ByteArrayInputStream(route.getValue().getBytes(StandardCharsets.UTF_8));
                }
            }
            throw new IOException("Jellyfin answered 404");
        }

        public void abort() {}
    }

    static final class Host implements PluginHost {
        volatile String status;
        volatile boolean error;
        volatile String sensor;
        volatile int sensorPublications;
        public void showWindow(String title, String message, String buttonLabel) { throw new AssertionError("no window"); }
        public void hideWindow() {}
        public void log(String message) {}
        public void status(String message, boolean error) { this.status = message; this.error = error; }
        public void publishTextSensor(String key, String name, String state) {
            assert MusicAlarmPlugin.SENSOR.equals(key) && "Wake song".equals(name);
            sensor = state;
            sensorPublications++;
        }
    }

    static String track(String id, String name, String artist) {
        return "{\"Id\":\"" + id + "\",\"Name\":\"" + name + "\",\"Type\":\"Audio\",\"Artists\":[\"" + artist + "\"]}";
    }

    static FakeJellyfin jellyfin() {
        FakeJellyfin fake = new FakeJellyfin();
        fake.routes.put("/Users", "[{\"Name\":\"Sergio\",\"Id\":\"" + USER_ID + "\"}]");
        fake.routes.put("includeItemTypes=Playlist", "{\"Items\":[{\"Name\":\"Morning Mix\",\"Id\":\"aaaa\"},"
            + "{\"Name\":\"Morning\",\"Id\":\"" + PLAYLIST_ID + "\"}],\"TotalRecordCount\":2}");
        fake.routes.put("parentId=" + PLAYLIST_ID, "{\"Items\":[" + track("t1", "Here Comes the Sun", "The Beatles") + ","
            + track("t2", "Good Day Sunshine", "The Beatles") + "]}");
        fake.routes.put("/Audio/t1/stream.mp3", "song one");
        fake.routes.put("/Audio/t2/stream.mp3", "song two");
        return fake;
    }

    static Map<String, Object> settings() {
        Map<String, Object> settings = new HashMap<>();
        settings.put("serverUrl", "jellyfin:8096/");
        settings.put("apiKey", "secret");
        settings.put("user", "sergio");
        settings.put("playlist", "Morning");
        settings.put("fileName", "wake.mp3");
        settings.put("avoidRepeats", true);
        settings.put("refreshHour", 3.0);
        return settings;
    }

    static File kioskFiles() throws IOException {
        File files = File.createTempFile("ks-files", "");
        assert files.delete() && files.mkdirs();
        files.deleteOnExit();
        return files;
    }

    static String read(File file) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[1024];
            for (int n; (n = in.read(buffer)) != -1; ) out.write(buffer, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        assert condition.getAsBoolean() : "Timed out waiting for " + what;
    }

    public static void main(String[] args) throws Exception {
        json();
        names();
        choose();
        client();
        playlistFallback();
        plugin();
        unconfigured();
        missingFilesFolder();
        System.out.println("MusicAlarmTest passed");
    }

    static void json() {
        Map<String, Object> value = Json.object(Json.parse(" {\"a\": [1, -2.5e1, true, null], \"b\": \"x\\\"\\u00e9\\n\"} "));
        assert Arrays.asList(1.0, -25.0, true, null).equals(value.get("a"));
        assert "x\"é\n".equals(value.get("b"));
        for (String bad : new String[] {"", "{", "[1,]", "{\"a\" 1}", "<html>", "\"open"}) {
            try {
                Json.parse(bad);
                throw new AssertionError("parsed " + bad);
            } catch (IllegalArgumentException expected) {
                // Rejected.
            }
        }
    }

    static void names() {
        assert SoundsFolder.invalidName("wake.mp3") == null && SoundsFolder.invalidName("Wake Up.MP3") == null;
        for (String bad : new String[] {"", ".mp3", "wake", "wake.ogg", "a/wake.mp3", "..\\wake.mp3", ".wake.mp3"}) {
            assert SoundsFolder.invalidName(bad) != null : bad;
        }
        assert "http://jellyfin:8096".equals(Jellyfin.normalize(" jellyfin:8096// "));
        assert "https://music.example".equals(Jellyfin.normalize("https://music.example/"));
        assert Jellyfin.isItemId(PLAYLIST_ID) && Jellyfin.isItemId("01234567-89ab-cdef-0123-456789abcdef");
        assert !Jellyfin.isItemId("Morning");
    }

    static void choose() {
        List<Jellyfin.Track> tracks = Arrays.asList(new Jellyfin.Track("a", "A", ""), new Jellyfin.Track("b", "B", ""));
        assert "b".equals(MusicAlarmPlugin.choose(tracks, Arrays.asList("a")).id);
        assert "a".equals(MusicAlarmPlugin.choose(tracks, Arrays.asList("b", "a")).id) : "falls back to the oldest";
        assert "a".equals(MusicAlarmPlugin.choose(tracks, Collections.<String>emptyList()).id);
    }

    static void client() throws Exception {
        FakeJellyfin fake = jellyfin();
        Jellyfin client = new Jellyfin("jellyfin:8096", "se\"cret", "1.0.0", fake);
        String user = client.userId("SERGIO");
        assert USER_ID.equals(user);
        assert client.userId("") == null;
        assert PLAYLIST_ID.equals(client.playlistId(user, "morning")) : "exact name wins over a partial match";
        assert PLAYLIST_ID.equals(client.playlistId(user, PLAYLIST_ID));
        assert fake.authorization.contains("Token=\"secret\"") : "quotes are stripped from the key";
        try {
            client.userId("nobody");
            throw new AssertionError("found nobody");
        } catch (IOException expected) {
            assert expected.getMessage().contains("nobody");
        }
        List<Jellyfin.Track> tracks = client.randomTracks(user, PLAYLIST_ID, 25);
        assert tracks.size() == 2 && "The Beatles – Here Comes the Sun".equals(tracks.get(0).describe());
        String request = fake.requests.get(fake.requests.size() - 1);
        assert request.startsWith("http://jellyfin:8096/Items?parentId=" + PLAYLIST_ID) && request.contains("sortBy=Random")
            && request.contains("userId=" + USER_ID) : request;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assert client.download(tracks.get(0), out) == 8 && "song one".equals(out.toString("UTF-8"));
    }

    static void playlistFallback() throws Exception {
        FakeJellyfin fake = jellyfin();
        fake.routes.put("parentId=" + PLAYLIST_ID, "{\"Items\":[]}");
        fake.routes.put("/Playlists/" + PLAYLIST_ID + "/Items", "{\"Items\":[" + track("t2", "Good Day Sunshine", "The Beatles") + "]}");
        List<Jellyfin.Track> tracks = new Jellyfin("jellyfin:8096", "k", "1.0.0", fake).randomTracks(null, PLAYLIST_ID, 25);
        assert tracks.size() == 1 && "t2".equals(tracks.get(0).id);
    }

    static void plugin() throws Exception {
        File files = kioskFiles();
        FakeJellyfin fake = jellyfin();
        Host host = new Host();
        MusicAlarmPlugin plugin = new MusicAlarmPlugin(fake, () -> new SoundsFolder(files), 50);
        File wake = new File(files, "sounds/wake.mp3");
        try {
            plugin.start(host, settings());
            await("the first song", wake::isFile);
            await("the status", () -> host.status != null && host.status.startsWith("Wake song: "));
            assert "song one".equals(read(wake));
            assert "The Beatles – Here Comes the Sun".equals(host.sensor) && !host.error;
            assert host.status.contains("Next song at 03:00") : host.status;
            assert !new File(files, "sounds/.wake.mp3.part").exists();

            // Avoid repeats: the next pick skips the song just played.
            plugin.execute("refresh", Collections.<String, Object>emptyMap());
            await("the second song", () -> "The Beatles – Good Day Sunshine".equals(host.sensor));
            assert "song two".equals(read(wake));

            // A failed refresh keeps the old file and says so.
            Map<String, Object> broken = settings();
            broken.put("playlist", "Evening");
            fake.routes.put("includeItemTypes=Playlist", "{\"Items\":[]}");
            plugin.configure(broken);
            await("the error", () -> host.error);
            assert host.status.contains("No playlist is called \"Evening\"") && host.status.contains("still plays") : host.status;
            assert "song two".equals(read(wake));

            // Unchanged settings do not ask Jellyfin again.
            int requests = fake.requests.size();
            plugin.configure(broken);
            Thread.sleep(200);
            assert fake.requests.size() == requests;
        } finally {
            plugin.stop();
        }

        // A new session remembers the song and does not fetch again while the file exists.
        Host next = new Host();
        int requests = fake.requests.size();
        MusicAlarmPlugin again = new MusicAlarmPlugin(fake, () -> new SoundsFolder(files), 50);
        try {
            again.start(next, settings());
            await("the remembered song", () -> "The Beatles – Good Day Sunshine".equals(next.sensor));
            await("the status", () -> next.status != null);
            assert fake.requests.size() == requests : "fetched although the sound exists";
        } finally {
            again.stop();
        }
    }

    static void unconfigured() throws Exception {
        File files = kioskFiles();
        Host host = new Host();
        Map<String, Object> settings = settings();
        settings.put("apiKey", "");
        settings.put("playlist", " ");
        MusicAlarmPlugin plugin = new MusicAlarmPlugin(jellyfin(), () -> new SoundsFolder(files), 50);
        try {
            plugin.start(host, settings);
            await("the hint", () -> host.status != null);
            assert "Set the Jellyfin API key, playlist to start.".equals(host.status) && !host.error : host.status;
            assert host.sensorPublications == 1 && host.sensor == null;

            Map<String, Object> badName = settings();
            badName.put("fileName", "wake.ogg");
            plugin.configure(badName);
            await("the file name error", () -> host.error);
            assert host.status.contains(".mp3");
        } finally {
            plugin.stop();
        }
    }

    static void missingFilesFolder() throws Exception {
        File files = new File(kioskFiles(), "missing");
        Host host = new Host();
        MusicAlarmPlugin plugin = new MusicAlarmPlugin(jellyfin(), () -> new SoundsFolder(files), 50);
        try {
            plugin.start(host, settings());
            await("the error", () -> host.error);
            assert host.status.contains("files folder is missing") : host.status;
            assert !files.exists() : "created a folder Kiosk Satellite does not use";
        } finally {
            plugin.stop();
        }
    }
}
