package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.HttpsURLConnection;

/** Optional personal integration. Credentials are imported once, never logged or bundled. */
final class UnsplashWallpaper {
    private final long interval;
    private final boolean allowed;
    private final SharedPreferences state;
    private final File directory;
    private final String key;
    private final String collections;

    static final class Photo {
        final Bitmap bitmap;
        final String id;
        final String photographer;
        final String link;

        Photo(Bitmap bitmap, JSONObject data) throws Exception {
            this.bitmap = bitmap;
            id = data.getString("id");
            photographer = data.getString("photographer");
            link = data.getString("link");
        }
    }

    UnsplashWallpaper(Context context, Config config) {
        interval = config.wallpaperRotationMinutes * 60_000L;
        allowed = config.wallpaperEnabled && !context.getSharedPreferences(
                Config.SETTINGS_NAME, Context.MODE_PRIVATE).contains(Config.WALLPAPER_CHOICE_KEY);
        state = context.getSharedPreferences("unsplash", Context.MODE_PRIVATE);
        directory = new File(context.getFilesDir(), "unsplash");
        directory.mkdirs();
        // ADB cannot write release-app private storage. Import a staging file and remove it.
        File external = context.getExternalFilesDir(null);
        File staging = external == null ? null : new File(external, "unsplash-setup.json");
        if (staging != null && staging.isFile()) {
            try (InputStream input = new java.io.FileInputStream(staging)) {
                JSONObject setup = new JSONObject(new String(read(input, 8192), StandardCharsets.UTF_8));
                String access = setup.getString("access_key");
                String ids = setup.getString("collections");
                if (!access.matches("[A-Za-z0-9_-]{10,200}")
                        || !ids.matches("[A-Za-z0-9_-]+(,[A-Za-z0-9_-]+)*")) {
                    throw new IllegalArgumentException();
                }
                if (state.edit().putString("access_key", access).putString("collections", ids)
                        .putLong("changed", 0).putLong("retry", 0).commit()) {
                    if (!staging.delete()) Log.w("TvHome.Unsplash", "Remove leftover setup file");
                }
            } catch (Exception failure) {
                Log.w("TvHome.Unsplash", "Could not import wallpaper setup");
            }
        }
        key = state.getString("access_key", "");
        collections = state.getString("collections", "");
    }

    boolean enabled() { return allowed && !key.isEmpty() && !collections.isEmpty(); }

    private volatile boolean requested;

    void next() { requested = true; }

    boolean due() {
        long now = System.currentTimeMillis();
        long retry = state.getLong("retry", 0);
        return requested || (retry <= now || retry - now > 24 * 60 * 60_000L)
                && WallpaperRotation.due(state.getLong("changed", 0), now, interval);
    }

    // Called only by WallpaperPlayer's worker thread. Keep the previous photo on any failure.
    Photo load(int width, int height) {
        if (due()) {
            requested = false;
            state.edit().putLong("retry", System.currentTimeMillis() + 5 * 60_000L).commit();
            try {
                JSONObject response = json("https://api.unsplash.com/photos/random?orientation=landscape"
                        + "&content_filter=high&collections=" + Uri.encode(collections));
                String id = response.getString("id");
                if (!id.matches("[A-Za-z0-9_-]{1,100}")) throw new IllegalArgumentException();
                Uri raw = Uri.parse(response.getJSONObject("urls").getString("raw"));
                Uri.Builder resized = raw.buildUpon().clearQuery();
                for (String name : raw.getQueryParameterNames()) {
                    if (name.equals("w") || name.equals("h") || name.equals("fit")
                            || name.equals("fm") || name.equals("q")) continue;
                    for (String value : raw.getQueryParameters(name)) resized.appendQueryParameter(name, value);
                }
                String url = resized.appendQueryParameter("w", String.valueOf(Math.min(1920, width)))
                        .appendQueryParameter("h", String.valueOf(Math.min(1080, height)))
                        .appendQueryParameter("fit", "crop").appendQueryParameter("fm", "jpg")
                        .appendQueryParameter("q", "80").build().toString();
                byte[] bytes = request(url, "images.unsplash.com", 6 * 1024 * 1024, false);
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                        || bounds.outWidth > 3840 || bounds.outHeight > 2160) throw new IllegalArgumentException();
                // Wallpaper selection is a download event, using the API-provided tracking URL.
                json(response.getJSONObject("links").getString("download_location"));
                JSONObject user = response.getJSONObject("user");
                String username = user.getString("username");
                if (!username.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
                JSONObject metadata = new JSONObject().put("id", id)
                        .put("photographer", user.getString("name"))
                        .put("link", "https://unsplash.com/@" + username
                                + "?utm_source=cleanyourtv&utm_medium=referral");
                write(new File(directory, id + ".jpg"), bytes);
                write(new File(directory, "current.json"), metadata.toString().getBytes(StandardCharsets.UTF_8));
                state.edit().putLong("changed", System.currentTimeMillis()).putLong("retry", 0).commit();
                File[] old = directory.listFiles();
                if (old != null) for (File file : old) {
                    if (file.getName().endsWith(".jpg") && !file.getName().equals(id + ".jpg")) file.delete();
                }
            } catch (Exception failure) {
                // Never log exception messages: request URLs or credentials may be embedded.
                Log.w("TvHome.Unsplash", "Refresh unavailable; keeping previous wallpaper");
            }
        }
        try (InputStream input = new AtomicFile(new File(directory, "current.json")).openRead()) {
            JSONObject metadata = new JSONObject(new String(read(input, 8192), StandardCharsets.UTF_8));
            String id = metadata.getString("id");
            if (!id.matches("[A-Za-z0-9_-]{1,100}")) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            options.inMutable = true;
            Bitmap bitmap = BitmapFactory.decodeFile(new File(directory, id + ".jpg").getPath(), options);
            return bitmap == null ? null : new Photo(bitmap, metadata);
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }

    private JSONObject json(String url) throws Exception {
        return new JSONObject(new String(request(url, "api.unsplash.com", 256 * 1024, true), StandardCharsets.UTF_8));
    }

    private byte[] request(String url, String host, int limit, boolean authenticate) throws Exception {
        java.net.URL target = new java.net.URL(url);
        if (!"https".equals(target.getProtocol()) || !host.equals(target.getHost())
                || target.getUserInfo() != null || (target.getPort() != -1 && target.getPort() != 443)) {
            throw new IllegalArgumentException();
        }
        HttpsURLConnection connection = (HttpsURLConnection) target.openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setInstanceFollowRedirects(false);
        if (authenticate) {
            connection.setRequestProperty("Authorization", "Client-ID " + key);
            connection.setRequestProperty("Accept-Version", "v1");
        }
        try {
            int status = connection.getResponseCode();
            if (status == 401 || status == 403 || status == 429) {
                state.edit().putLong("retry", System.currentTimeMillis() + 60 * 60_000L).commit();
            }
            if (status != 200) throw new java.io.IOException();
            try (InputStream input = connection.getInputStream()) { return read(input, limit); }
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] read(InputStream input, int limit) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int size;
        while ((size = input.read(buffer)) != -1) {
            if (output.size() + size > limit) throw new java.io.IOException();
            output.write(buffer, 0, size);
        }
        return output.toByteArray();
    }

    private static void write(File file, byte[] bytes) throws Exception {
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream output = atomic.startWrite();
        try {
            output.write(bytes);
            atomic.finishWrite(output);
        } catch (Exception failure) {
            atomic.failWrite(output);
            throw failure;
        }
    }
}
