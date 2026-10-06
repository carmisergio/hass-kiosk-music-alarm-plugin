// SPDX-License-Identifier: Apache-2.0
package com.carmisergio.kiosk.musicalarm;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/** The plugin's only network access, so tests can answer for Jellyfin. */
interface Http {
    /** Opens a GET. The caller closes the body. Non-2xx answers throw. */
    InputStream get(String url, Map<String, String> headers) throws IOException;

    /** Breaks off a request in progress, from another thread. */
    void abort();

    final class Urls implements Http {
        private static final int CONNECT_TIMEOUT_MS = 10_000;
        private static final int READ_TIMEOUT_MS = 30_000;
        private volatile HttpURLConnection current;

        @Override
        public InputStream get(String url, Map<String, String> headers) throws IOException {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            for (Map.Entry<String, String> header : headers.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            current = connection;
            int code = connection.getResponseCode();
            if (code / 100 != 2) {
                connection.disconnect();
                throw new IOException(code == 401 ? "Jellyfin refused the API key (401)" : "Jellyfin answered " + code);
            }
            return connection.getInputStream();
        }

        @Override
        public void abort() {
            HttpURLConnection connection = current;
            if (connection != null) connection.disconnect();
        }
    }
}
