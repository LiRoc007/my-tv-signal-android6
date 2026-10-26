package com.gathin.tvhome;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Copies a theme staged in /data/local/tmp into the app's own storage.
 * The names are listed in cyt-list.txt. Opening those paths does not require listing the directory,
 * which the shell owns and this app cannot read.
 */
final class ThemeImport {
    private static final File TMP = new File("/data/local/tmp");

    private ThemeImport() {}

    static void bringIn(Context ctx) {
        File list = new File(TMP, "cyt-list.txt");
        File config = new File(TMP, "cyt-config.json");
        if (!list.canRead() || !config.canRead()) return;
        File dest = new File(ctx.getFilesDir(), "theme");
        File walls = new File(dest, "wallpapers");
        if (!walls.isDirectory() && !walls.mkdirs()) return;
        copy(config, new File(dest, "config.json"));
        File captions = new File(TMP, "cyt-captions.txt");
        if (captions.canRead()) copy(captions, new File(walls, "captions.txt"));
        try (java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(
                new FileInputStream(list), java.nio.charset.StandardCharsets.UTF_8))) {
            String name;
            while ((name = in.readLine()) != null) {
                name = name.trim();
                if (name.isEmpty() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) continue;
                File src = new File(TMP, "cyt-" + name);
                if (src.canRead()) copy(src, new File(walls, name));
            }
        } catch (IOException ignored) {
        }
    }

    private static void copy(File src, File dest) {
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (IOException ignored) {
        }
    }
}
