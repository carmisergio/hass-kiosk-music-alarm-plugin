// SPDX-License-Identifier: Apache-2.0
package com.carmisergio.kiosk.musicalarm;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Kiosk Satellite's alarm sounds folder, the one its Alarm tone setting lists.
 *
 * <p>KS finds it with Flutter's getExternalStorageDirectory(), which is
 * Context.getExternalFilesDir(null). Plugins get no Context, but Android
 * builds that directory from the same primary volume that
 * Environment.getExternalStorageDirectory() returns, plus
 * Android/data/&lt;package&gt;/files. The plugin runs as the app, so it may
 * write there without a permission.
 */
final class SoundsFolder {
    static final String PACKAGE = "me.jxl.kiosk_satellite";
    private static final String STATE = ".music-alarm-state";

    private final File files;

    SoundsFolder(File files) {
        this.files = files;
    }

    /** The folder on this device. Environment is looked up by name so the plugin also loads on a plain JVM. */
    static SoundsFolder onDevice() throws IOException {
        File storage;
        try {
            storage = (File) Class.forName("android.os.Environment").getMethod("getExternalStorageDirectory").invoke(null);
        } catch (ReflectiveOperationException | ClassCastException e) {
            throw new IOException("Android's external storage is not available", e);
        }
        if (storage == null) throw new IOException("Android's external storage is not available");
        return new SoundsFolder(new File(storage, "Android/data/" + PACKAGE + "/files"));
    }

    /** Null when [name] is a file name KS accepts as a tone and the plugin writes, else why not. */
    static String invalidName(String name) {
        if (name.isEmpty()) return "Enter a sound file name";
        if (name.contains("/") || name.contains("\\") || name.startsWith(".")) return "Enter a file name, not a path";
        if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".mp3") || name.length() <= 4) return "The sound file name must end in .mp3";
        if (name.length() > 120) return "The sound file name is too long";
        return null;
    }

    File sound(String name) throws IOException {
        // A missing files folder means KS moved its data, not that the plugin should invent one.
        if (!files.isDirectory()) throw new IOException("Kiosk Satellite's files folder is missing: " + files);
        File sounds = new File(files, "sounds");
        if (!sounds.isDirectory() && !sounds.mkdirs()) throw new IOException("Could not create " + sounds);
        return new File(sounds, name);
    }

    boolean exists(String name) {
        return new File(new File(files, "sounds"), name).isFile();
    }

    /** Writes the sound beside its final name, then renames it, so a ring mid-download plays the old song. */
    void replace(String name, Body body) throws IOException {
        File target = sound(name);
        // A leading dot and a non-sound extension keep KS from listing it as a tone.
        File part = new File(target.getParentFile(), "." + name + ".part");
        try {
            try (FileOutputStream out = new FileOutputStream(part)) {
                body.write(out);
                out.getFD().sync();
            }
            if (!part.renameTo(target)) throw new IOException("Could not replace " + target);
        } finally {
            if (part.exists() && !part.delete()) part.deleteOnExit();
        }
    }

    interface Body {
        void write(FileOutputStream out) throws IOException;
    }

    /** What the last refresh picked: its description, then recent track IDs, newest first. */
    List<String> readState() {
        List<String> lines = new ArrayList<>();
        File state = new File(new File(files, "sounds"), STATE);
        if (!state.isFile()) return lines;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(state), StandardCharsets.UTF_8))) {
            for (String line; (line = in.readLine()) != null; ) lines.add(line);
        } catch (IOException e) {
            lines.clear();
        }
        return lines;
    }

    void writeState(String current, List<String> recent) throws IOException {
        try (Writer out = new OutputStreamWriter(new FileOutputStream(sound(STATE)), StandardCharsets.UTF_8)) {
            out.write(current.replace('\n', ' '));
            for (String id : recent) out.write("\n" + id);
            out.write("\n");
        }
    }
}
