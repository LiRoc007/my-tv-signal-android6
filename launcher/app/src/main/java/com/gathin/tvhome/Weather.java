package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * A short line under the date: temperature and conditions from Open-Meteo.
 * The TV's own location setting supplies the coordinates. Nothing is logged from it.
 */
final class Weather {
    private static final long FRESH_MS = 30L * 60L * 1000L;
    private static final String PREFS = "weather_cache";

    private Weather() {}

    static void show(TextView view) {
        Context app = view.getContext().getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String cached = prefs.getString("text", null);
        long at = prefs.getLong("at", 0);
        if (cached != null && !cached.isEmpty()) {
            view.setText(cached);
            view.setVisibility(View.VISIBLE);
        }
        if (cached != null && System.currentTimeMillis() - at < FRESH_MS) return;
        new Thread(() -> {
            String text = fetch(app);
            if (text == null || text.isEmpty()) return;
            prefs.edit().putString("text", text).putLong("at", System.currentTimeMillis()).apply();
            view.post(() -> {
                view.setText(text);
                view.setVisibility(View.VISIBLE);
            });
        }, "weather").start();
    }

    private static String fetch(Context app) {
        double[] where = location(app);
        if (where == null) return null;
        HttpURLConnection conn = null;
        try {
            String spec = "https://api.open-meteo.com/v1/forecast?latitude=" + where[0]
                    + "&longitude=" + where[1]
                    + "&current=temperature_2m,weather_code&timezone=auto";
            conn = (HttpURLConnection) new URL(spec).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            if (conn instanceof HttpsURLConnection) {
                try {
                    ((HttpsURLConnection) conn).setSSLSocketFactory(sockets(app));
                } catch (Exception ignored) {
                    // Keep the platform socket if the bundled roots cannot be loaded.
                }
            }
            conn.setRequestProperty("Accept", "application/json");
            int status = conn.getResponseCode();
            if (status != 200) {
                Log.w("Weather", "http " + status);
                return null;
            }
            StringBuilder body = new StringBuilder();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) body.append(line);
            }
            JSONObject current = new JSONObject(body.toString()).getJSONObject("current");
            int degrees = (int) Math.round(current.getDouble("temperature_2m"));
            String condition = condition(current.optInt("weather_code", -1),
                    Locale.getDefault().getLanguage().startsWith("zh"));
            return condition.isEmpty() ? degrees + "°" : degrees + "° " + condition;
        } catch (Exception e) {
            Log.w("Weather", e.getClass().getSimpleName());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Latitude and longitude from the TV's location setting, or null. */
    private static double[] location(Context app) {
        for (String key : new String[] {"mitv_user_location", "mitv_location"}) {
            String raw = Settings.System.getString(app.getContentResolver(), key);
            if (raw == null || raw.isEmpty() || "null".equals(raw)) continue;
            try {
                JSONObject o = new JSONObject(raw);
                double lat = Double.parseDouble(o.getString("latitude"));
                double lon = Double.parseDouble(o.getString("longitude"));
                if (lat < -90 || lat > 90 || lon < -180 || lon > 180) continue;
                return new double[] {lat, lon};
            } catch (Exception ignored) {
                // The next key may be the one this firmware fills in.
            }
        }
        Log.w("Weather", "no-location");
        return null;
    }

    /** Android 6 does not trust the public roots this weather service uses. */
    private static SSLSocketFactory sockets(Context app) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        for (String name : new String[] {"isrgrootx1.pem", "isrg-root-x2.pem"}) {
            try (InputStream in = app.getAssets().open(name)) {
                ks.setCertificateEntry(name, cf.generateCertificate(in));
            }
        }
        javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext sc = SSLContext.getInstance("TLSv1.2");
        sc.init(null, tmf.getTrustManagers(), null);
        return new Tls12(sc.getSocketFactory());
    }

    /** Android 6 on some boards leaves TLS 1.2 disabled on the default socket. */
    private static final class Tls12 extends SSLSocketFactory {
        private final SSLSocketFactory delegate;

        Tls12(SSLSocketFactory delegate) {
            this.delegate = delegate;
        }

        private Socket enable(Socket socket) {
            if (socket instanceof SSLSocket) {
                ((SSLSocket) socket).setEnabledProtocols(new String[] {"TLSv1.2"});
            }
            return socket;
        }

        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        @Override public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws java.io.IOException {
            return enable(delegate.createSocket(s, host, port, autoClose));
        }
        @Override public Socket createSocket(String host, int port) throws java.io.IOException {
            return enable(delegate.createSocket(host, port));
        }
        @Override public Socket createSocket(String host, int port, java.net.InetAddress local, int localPort) throws java.io.IOException {
            return enable(delegate.createSocket(host, port, local, localPort));
        }
        @Override public Socket createSocket(java.net.InetAddress host, int port) throws java.io.IOException {
            return enable(delegate.createSocket(host, port));
        }
        @Override public Socket createSocket(java.net.InetAddress host, int port, java.net.InetAddress local, int localPort) throws java.io.IOException {
            return enable(delegate.createSocket(host, port, local, localPort));
        }
    }

    private static String condition(int code, boolean zh) {
        if (code == 0) return zh ? "晴" : "Clear";
        if (code >= 1 && code <= 3) return zh ? "多云" : "Cloudy";
        if (code == 45 || code == 48) return zh ? "雾" : "Fog";
        if (code >= 51 && code <= 67) return zh ? "雨" : "Rain";
        if (code >= 71 && code <= 77) return zh ? "雪" : "Snow";
        if (code >= 80 && code <= 82) return zh ? "阵雨" : "Showers";
        if (code >= 95 && code <= 99) return zh ? "雷雨" : "Storm";
        return "";
    }
}
