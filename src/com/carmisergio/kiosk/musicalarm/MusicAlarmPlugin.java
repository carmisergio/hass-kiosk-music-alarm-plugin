// SPDX-License-Identifier: Apache-2.0
package com.carmisergio.kiosk.musicalarm;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Keeps a random song from a Jellyfin playlist in KS's alarm sounds folder.
 *
 * <p>A new song is picked every night, from the Pick a new song now command,
 * when the Jellyfin settings or the file name change, and on start when the
 * file is missing. All network and file work runs on one plugin-owned thread,
 * so callbacks return at once and refreshes never overlap.
 */
public final class MusicAlarmPlugin implements KioskPlugin {
    static final String VERSION = "0.1.0";
    static final String SENSOR = "wake_song";
    private static final int RECENT = 10;
    private static final int CANDIDATES = 25;

    interface Folders {
        SoundsFolder get() throws IOException;
    }

    private final Http http;
    private final Folders folders;
    private final long settleMs;

    private volatile PluginHost host;
    private volatile Config config;
    private ScheduledExecutorService worker;
    private ScheduledFuture<?> pending;
    private ScheduledFuture<?> nightly;

    // Only touched on the worker thread.
    private String current;
    private final List<String> recent = new ArrayList<>();

    public MusicAlarmPlugin() {
        this(new Http.Urls(), SoundsFolder::onDevice, 3000);
    }

    MusicAlarmPlugin(Http http, Folders folders, long settleMs) {
        this.http = http;
        this.folders = folders;
        this.settleMs = settleMs;
    }

    @Override
    public synchronized void start(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        config = Config.from(settings);
        worker = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "music-alarm");
            thread.setDaemon(true);
            return thread;
        });
        worker.execute(this::resume);
        scheduleNightly();
    }

    @Override
    public synchronized void configure(Map<String, Object> settings) {
        Config next = Config.from(settings);
        Config previous = config;
        config = next;
        if (worker == null) return;
        if (previous == null || previous.refreshHour != next.refreshHour) scheduleNightly();
        // Each edited field saves on its own, so wait for a pause before asking Jellyfin.
        if (previous == null || !previous.source().equals(next.source())) refreshAfter(settleMs);
        else if (!next.configured()) status(next.missing(), false);
    }

    @Override
    public synchronized void execute(String command, Map<String, Object> arguments) {
        if (!"refresh".equals(command)) throw new IllegalArgumentException("Unknown command: " + command);
        if (worker != null) refreshAfter(0);
    }

    @Override
    public void onEvent(String event, Map<String, Object> payload) {
    }

    @Override
    public void stop() throws InterruptedException {
        ScheduledExecutorService stopping;
        synchronized (this) {
            host = null;
            stopping = worker;
            worker = null;
            pending = null;
            nightly = null;
        }
        if (stopping == null) return;
        stopping.shutdownNow();
        // A blocked socket read ignores interruption; disconnecting ends it.
        http.abort();
        // Outside the lock, so a finishing refresh can still reach it.
        stopping.awaitTermination(2, TimeUnit.SECONDS);
    }

    private void refreshAfter(long delayMs) {
        if (pending != null) pending.cancel(false);
        pending = worker.schedule(this::refresh, delayMs, TimeUnit.MILLISECONDS);
    }

    private void scheduleNightly() {
        if (nightly != null) nightly.cancel(false);
        Calendar now = Calendar.getInstance();
        Calendar next = (Calendar) now.clone();
        next.set(Calendar.HOUR_OF_DAY, config.refreshHour);
        next.set(Calendar.MINUTE, 0);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        if (!next.after(now)) next.add(Calendar.DAY_OF_MONTH, 1);
        nightly = worker.schedule(this::nightlyRefresh, next.getTimeInMillis() - now.getTimeInMillis(), TimeUnit.MILLISECONDS);
    }

    private void nightlyRefresh() {
        refresh();
        synchronized (this) {
            if (worker != null) scheduleNightly();
        }
    }

    /** Worker: show what the last session picked, and fetch a song if there is none. */
    private void resume() {
        Config c = config;
        try {
            SoundsFolder folder = folders.get();
            List<String> state = folder.readState();
            if (!state.isEmpty()) {
                current = state.get(0).isEmpty() ? null : state.get(0);
                recent.addAll(state.subList(1, state.size()));
            }
            publish();
            if (c.configured() && SoundsFolder.invalidName(c.fileName) == null && !folder.exists(c.fileName)) refresh();
            else if (!c.configured()) status(c.missing(), false);
            else showCurrent(c);
        } catch (IOException e) {
            publish();
            status(e.getMessage(), true);
        }
    }

    /** Worker: pick a song and put it in place of the old one. */
    private void refresh() {
        Config c = config;
        if (host == null) return;
        if (!c.configured()) {
            status(c.missing(), false);
            return;
        }
        String invalid = SoundsFolder.invalidName(c.fileName);
        if (invalid != null) {
            status(invalid + ".", true);
            return;
        }
        try {
            status("Picking a new song…", false);
            SoundsFolder folder = folders.get();
            // Fail on a missing folder before asking Jellyfin for anything.
            folder.sound(c.fileName);
            Jellyfin jellyfin = new Jellyfin(c.serverUrl, c.apiKey, VERSION, http);
            String userId = jellyfin.userId(c.user);
            String playlistId = jellyfin.playlistId(userId, c.playlist);
            List<Jellyfin.Track> tracks = jellyfin.randomTracks(userId, playlistId, CANDIDATES);
            if (tracks.isEmpty()) throw new IOException("The playlist has no songs");
            Jellyfin.Track pick = choose(tracks, c.avoidRepeats ? recent : Collections.<String>emptyList());
            folder.replace(c.fileName, out -> jellyfin.download(pick, out));
            current = pick.describe();
            recent.remove(pick.id);
            recent.add(0, pick.id);
            while (recent.size() > RECENT) recent.remove(recent.size() - 1);
            try {
                folder.writeState(current, recent);
            } catch (IOException e) {
                log("Could not save the plugin state: " + e.getMessage());
            }
            publish();
            showCurrent(c);
        } catch (IOException | RuntimeException e) {
            if (Thread.currentThread().isInterrupted() || host == null) return;
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            status("Could not pick a new song: " + message + "."
                + (current == null ? "" : "\nThe alarm still plays " + current + "."), true);
        }
    }

    static Jellyfin.Track choose(List<Jellyfin.Track> tracks, List<String> recent) {
        for (Jellyfin.Track track : tracks) {
            if (!recent.contains(track.id)) return track;
        }
        // Everything was played lately: the one played longest ago.
        Jellyfin.Track oldest = tracks.get(0);
        for (Jellyfin.Track track : tracks) {
            if (recent.indexOf(track.id) > recent.indexOf(oldest.id)) oldest = track;
        }
        return oldest;
    }

    private void showCurrent(Config c) {
        status((current == null ? "Ready" : "Wake song: " + current)
            + String.format(Locale.ROOT, "\nSaved as %s. Next song at %02d:00.", c.fileName, c.refreshHour), false);
    }

    private void publish() {
        PluginHost h = host;
        if (h == null) return;
        try {
            h.publishTextSensor(SENSOR, "Wake song", current);
        } catch (RuntimeException ignored) {
            // The session ended while the worker was finishing.
        }
    }

    private void status(String message, boolean error) {
        PluginHost h = host;
        if (h == null) return;
        try {
            h.status(message.length() > 1000 ? message.substring(0, 999) + "…" : message, error);
        } catch (RuntimeException ignored) {
            // The session ended while the worker was finishing.
        }
    }

    private void log(String message) {
        PluginHost h = host;
        if (h == null) return;
        try {
            h.log(message);
        } catch (RuntimeException ignored) {
            // The session ended while the worker was finishing.
        }
    }

    /** One validated snapshot of the settings. */
    static final class Config {
        final String serverUrl;
        final String apiKey;
        final String user;
        final String playlist;
        final String fileName;
        final boolean avoidRepeats;
        final int refreshHour;

        private Config(Map<String, Object> settings) {
            serverUrl = text(settings, "serverUrl");
            apiKey = text(settings, "apiKey");
            user = text(settings, "user");
            playlist = text(settings, "playlist");
            fileName = text(settings, "fileName");
            avoidRepeats = !Boolean.FALSE.equals(settings.get("avoidRepeats"));
            Object hour = settings.get("refreshHour");
            refreshHour = hour instanceof Number ? Math.max(0, Math.min(23, ((Number) hour).intValue())) : 3;
        }

        static Config from(Map<String, Object> settings) {
            return new Config(settings);
        }

        boolean configured() {
            return !serverUrl.isEmpty() && !apiKey.isEmpty() && !playlist.isEmpty();
        }

        String missing() {
            List<String> names = new ArrayList<>();
            if (serverUrl.isEmpty()) names.add("server address");
            if (apiKey.isEmpty()) names.add("API key");
            if (playlist.isEmpty()) names.add("playlist");
            StringBuilder text = new StringBuilder("Set the Jellyfin ");
            for (int i = 0; i < names.size(); i++) text.append(i == 0 ? "" : ", ").append(names.get(i));
            return text.append(" to start.").toString();
        }

        /** The settings a refresh depends on. */
        String source() {
            return serverUrl + '\n' + apiKey + '\n' + user + '\n' + playlist + '\n' + fileName;
        }

        private static String text(Map<String, Object> settings, String key) {
            Object value = settings.get(key);
            return value == null ? "" : value.toString().trim();
        }
    }
}
