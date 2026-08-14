/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.afwsamples.testdpc.parentalcontrol;

import android.text.TextUtils;
import android.util.Log;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * A deliberately small HTTP/1.1 server, built straight on {@link ServerSocket} so that the app
 * gains no third party dependency.
 *
 * <p>{@code GET /} returns a small control page so the whole thing can be driven from a phone
 * browser, which cannot set an auth header. The contract with the client is unusual and
 * intentional: {@code POST /network/off} answers 200 and only then, a second later, pulls the
 * network down. The client waits five seconds and
 * probes {@code GET /ping}: silence means the kill switch worked, a reply means it did not. That
 * is the only honest way to confirm the result, since a successful call destroys the channel it
 * was made over.
 */
public class RemoteControlServer {

  private static final String TAG = "RemoteControlServer";
  private static final Charset UTF_8 = Charset.forName("UTF-8");

  /** Delay between answering the caller and actually cutting the network. */
  static final long KILL_DELAY_MILLIS = 1000L;

  private static final String APPLICATION_JSON = "application/json; charset=utf-8";
  private static final String TEXT_HTML = "text/html; charset=utf-8";
  private static final String TEXT_PLAIN = "text/plain; charset=utf-8";

  private static final int SOCKET_TIMEOUT_MILLIS = 15000;
  private static final int MAX_REQUEST_LINE_LENGTH = 8192;
  private static final int MAX_BODY_BYTES = 64 * 1024;

  /** Operations the server exposes; implemented by the owning service. */
  public interface Actions {
    /** Engages the kill switch after {@link #KILL_DELAY_MILLIS}. */
    void scheduleNetworkBlock(int minutes);

    void releaseNetworkBlock();

    void resetScreenTime();

    /** Switches the quiet hours window on or off; the times themselves are set in the app. */
    void setQuietHoursEnabled(boolean enabled);

    JSONObject buildStatus() throws JSONException;

    /** The shared secret required on requests, or empty to leave the server open. */
    String getAuthToken();

    /** The single-page control UI served at "/". */
    String getControlPageHtml();
  }

  private final Actions mActions;
  private volatile ServerSocket mServerSocket;
  private volatile boolean mRunning;
  private Thread mAcceptThread;
  private volatile String mLastError;

  public RemoteControlServer(Actions actions) {
    mActions = actions;
  }

  public boolean isRunning() {
    return mRunning;
  }

  public String getLastError() {
    return mLastError;
  }

  public int getPort() {
    ServerSocket socket = mServerSocket;
    return socket == null ? -1 : socket.getLocalPort();
  }

  public synchronized void start(int port) {
    if (mRunning) {
      return;
    }
    try {
      ServerSocket socket = new ServerSocket();
      socket.setReuseAddress(true);
      socket.bind(new java.net.InetSocketAddress((java.net.InetAddress) null, port));
      mServerSocket = socket;
      mRunning = true;
      mLastError = null;
    } catch (IOException e) {
      mLastError = e.getMessage();
      Log.e(TAG, "Could not bind port " + port, e);
      return;
    }
    mAcceptThread = new Thread(this::acceptLoop, "RemoteControlServer");
    mAcceptThread.setDaemon(true);
    mAcceptThread.start();
    Log.i(TAG, "Listening on port " + getPort());
  }

  public synchronized void stop() {
    mRunning = false;
    ServerSocket socket = mServerSocket;
    mServerSocket = null;
    if (socket != null) {
      try {
        socket.close();
      } catch (IOException e) {
        Log.w(TAG, "Closing the listening socket failed", e);
      }
    }
    if (mAcceptThread != null) {
      mAcceptThread.interrupt();
      mAcceptThread = null;
    }
  }

  private void acceptLoop() {
    while (mRunning) {
      ServerSocket serverSocket = mServerSocket;
      if (serverSocket == null) {
        break;
      }
      try {
        Socket client = serverSocket.accept();
        client.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
        // One connection at a time is plenty for a handful of manual requests, and it keeps the
        // ordering between "answer" and "cut the network" easy to reason about.
        handleConnection(client);
      } catch (IOException e) {
        if (mRunning) {
          Log.w(TAG, "accept() failed", e);
        }
      } catch (RuntimeException e) {
        Log.e(TAG, "Unexpected failure while serving a request", e);
      }
    }
    Log.i(TAG, "Accept loop finished");
  }

  private void handleConnection(Socket client) {
    try (Socket socket = client) {
      InputStream in = socket.getInputStream();
      OutputStream out = socket.getOutputStream();
      Request request = readRequest(in);
      if (request == null) {
        writeResponse(out, 400, "Bad Request", errorBody("Malformed request"));
        return;
      }
      route(request, out);
      out.flush();
    } catch (SocketTimeoutException e) {
      Log.d(TAG, "Client timed out");
    } catch (IOException e) {
      Log.w(TAG, "I/O failure while serving a request", e);
    }
  }

  private void route(Request request, OutputStream out) throws IOException {
    String path = request.path;
    if ("/ping".equals(path)) {
      // Left unauthenticated on purpose: it is the reachability probe, and a 401 would be just as
      // reachable as a 200 while making the client harder to reason about.
      writeResponse(out, 200, "OK", "pong\n".getBytes(UTF_8), TEXT_PLAIN);
      return;
    }

    if (!isAuthorised(request)) {
      writeResponse(out, 401, "Unauthorized", errorBody("Missing or wrong token"));
      return;
    }

    try {
      if ("/".equals(path) || "/index.html".equals(path)) {
        // A phone browser cannot set a header, so the page is the interface: it carries whatever
        // token is configured in its own script and drives the endpoints below with fetch().
        writeResponse(
            out, 200, "OK", mActions.getControlPageHtml().getBytes(UTF_8), TEXT_HTML);
        return;
      }
      if ("/status".equals(path)) {
        writeJson(out, 200, "OK", mActions.buildStatus());
        return;
      }
      if ("/network/off".equals(path)) {
        if (!"POST".equals(request.method)) {
          writeResponse(out, 405, "Method Not Allowed", errorBody("Use POST"));
          return;
        }
        int minutes = parseMinutes(request);
        if (minutes < 0) {
          writeResponse(out, 400, "Bad Request", errorBody("minutes must be a positive integer"));
          return;
        }
        JSONObject body = new JSONObject();
        body.put("ok", true);
        body.put("action", "network_off");
        body.put("minutes", minutes);
        body.put("killDelayMillis", KILL_DELAY_MILLIS);
        body.put(
            "verify",
            "wait 5s then GET /ping - no answer means the network really went down");
        writeJson(out, 200, "OK", body);
        // Answer first, flush, and only then arm the cut, exactly as the client protocol expects.
        out.flush();
        mActions.scheduleNetworkBlock(minutes);
        return;
      }
      if ("/network/on".equals(path)) {
        if (!"POST".equals(request.method)) {
          writeResponse(out, 405, "Method Not Allowed", errorBody("Use POST"));
          return;
        }
        mActions.releaseNetworkBlock();
        JSONObject body = new JSONObject();
        body.put("ok", true);
        body.put("action", "network_on");
        writeJson(out, 200, "OK", body);
        return;
      }
      if ("/screentime/quiethours".equals(path)) {
        if (!"POST".equals(request.method)) {
          writeResponse(out, 405, "Method Not Allowed", errorBody("Use POST"));
          return;
        }
        Boolean enabled = parseBoolean(request);
        if (enabled == null) {
          writeResponse(out, 400, "Bad Request", errorBody("enabled must be true or false"));
          return;
        }
        mActions.setQuietHoursEnabled(enabled);
        JSONObject body = new JSONObject();
        body.put("ok", true);
        body.put("action", "quiet_hours");
        body.put("enabled", enabled);
        writeJson(out, 200, "OK", body);
        return;
      }
      if ("/screentime/reset".equals(path)) {
        if (!"POST".equals(request.method)) {
          writeResponse(out, 405, "Method Not Allowed", errorBody("Use POST"));
          return;
        }
        mActions.resetScreenTime();
        JSONObject body = new JSONObject();
        body.put("ok", true);
        body.put("action", "screentime_reset");
        writeJson(out, 200, "OK", body);
        return;
      }
      writeResponse(out, 404, "Not Found", errorBody("Unknown endpoint " + path));
    } catch (JSONException e) {
      Log.e(TAG, "Could not build a response", e);
      writeResponse(out, 500, "Internal Server Error", errorBody("Could not build response"));
    }
  }

  private boolean isAuthorised(Request request) {
    String expected = mActions.getAuthToken();
    if (TextUtils.isEmpty(expected)) {
      return true;
    }
    String provided = request.headers.get("x-auth-token");
    if (provided == null) {
      provided = request.query.get("token");
    }
    return expected.equals(provided);
  }

  /** @return the requested flag, or null when absent or unparseable. */
  private static Boolean parseBoolean(Request request) {
    String raw = request.query.get("enabled");
    if (raw == null) {
      raw = request.formValue("enabled");
    }
    if (raw == null) {
      return null;
    }
    raw = raw.trim();
    if ("true".equalsIgnoreCase(raw) || "1".equals(raw)) {
      return Boolean.TRUE;
    }
    if ("false".equalsIgnoreCase(raw) || "0".equals(raw)) {
      return Boolean.FALSE;
    }
    return null;
  }

  /** @return the requested duration in minutes, 0 for indefinite, or -1 when malformed. */
  private static int parseMinutes(Request request) {
    String raw = request.query.get("minutes");
    if (raw == null) {
      raw = request.formValue("minutes");
    }
    if (TextUtils.isEmpty(raw)) {
      return 0;
    }
    try {
      int minutes = Integer.parseInt(raw.trim());
      return minutes < 0 ? -1 : minutes;
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  // ------------------------------------------------------------------
  // Minimal HTTP plumbing
  // ------------------------------------------------------------------

  private static final class Request {
    String method = "";
    String path = "";
    final Map<String, String> headers = new HashMap<>();
    final Map<String, String> query = new HashMap<>();
    String body = "";

    String formValue(String name) {
      if (TextUtils.isEmpty(body)) {
        return null;
      }
      return parseQueryString(body).get(name);
    }
  }

  private static Request readRequest(InputStream in) throws IOException {
    BufferedReader reader = new BufferedReader(new InputStreamReader(in, UTF_8), 1024);
    String requestLine = reader.readLine();
    if (requestLine == null || requestLine.isEmpty()) {
      return null;
    }
    if (requestLine.length() > MAX_REQUEST_LINE_LENGTH) {
      return null;
    }
    String[] parts = requestLine.split(" ");
    if (parts.length < 2) {
      return null;
    }
    Request request = new Request();
    request.method = parts[0].toUpperCase(Locale.US);
    String target = parts[1];
    int questionMark = target.indexOf('?');
    if (questionMark >= 0) {
      request.path = target.substring(0, questionMark);
      request.query.putAll(parseQueryString(target.substring(questionMark + 1)));
    } else {
      request.path = target;
    }

    String line;
    while ((line = reader.readLine()) != null && !line.isEmpty()) {
      int colon = line.indexOf(':');
      if (colon > 0) {
        request.headers.put(
            line.substring(0, colon).trim().toLowerCase(Locale.US),
            line.substring(colon + 1).trim());
      }
    }

    int contentLength = 0;
    String rawLength = request.headers.get("content-length");
    if (rawLength != null) {
      try {
        contentLength = Math.min(Integer.parseInt(rawLength.trim()), MAX_BODY_BYTES);
      } catch (NumberFormatException e) {
        contentLength = 0;
      }
    }
    if (contentLength > 0) {
      char[] buffer = new char[contentLength];
      int read = 0;
      while (read < contentLength) {
        int count = reader.read(buffer, read, contentLength - read);
        if (count < 0) {
          break;
        }
        read += count;
      }
      request.body = new String(buffer, 0, Math.max(read, 0));
    }
    return request;
  }

  private static Map<String, String> parseQueryString(String query) {
    Map<String, String> values = new LinkedHashMap<>();
    for (String pair : query.split("&")) {
      if (pair.isEmpty()) {
        continue;
      }
      int equals = pair.indexOf('=');
      try {
        if (equals < 0) {
          values.put(java.net.URLDecoder.decode(pair, "UTF-8"), "");
        } else {
          values.put(
              java.net.URLDecoder.decode(pair.substring(0, equals), "UTF-8"),
              java.net.URLDecoder.decode(pair.substring(equals + 1), "UTF-8"));
        }
      } catch (IOException | IllegalArgumentException e) {
        Log.w(TAG, "Skipping undecodable query parameter", e);
      }
    }
    return values;
  }

  private static byte[] errorBody(String message) {
    try {
      return new JSONObject().put("ok", false).put("error", message).toString().getBytes(UTF_8);
    } catch (JSONException e) {
      return ("{\"ok\":false}").getBytes(UTF_8);
    }
  }

  private static void writeJson(OutputStream out, int code, String reason, JSONObject body)
      throws IOException {
    writeResponse(out, code, reason, body.toString().getBytes(UTF_8), APPLICATION_JSON);
  }

  private static void writeResponse(OutputStream out, int code, String reason, byte[] body)
      throws IOException {
    writeResponse(out, code, reason, body, APPLICATION_JSON);
  }

  private static void writeResponse(
      OutputStream out, int code, String reason, byte[] body, String contentType)
      throws IOException {
    ByteArrayOutputStream header = new ByteArrayOutputStream();
    header.write(
        ("HTTP/1.1 " + code + " " + reason + "\r\n").getBytes(UTF_8));
    header.write(("Content-Type: " + contentType + "\r\n").getBytes(UTF_8));
    header.write(("Content-Length: " + body.length + "\r\n").getBytes(UTF_8));
    header.write("Cache-Control: no-store\r\n".getBytes(UTF_8));
    header.write("Connection: close\r\n\r\n".getBytes(UTF_8));
    out.write(header.toByteArray());
    out.write(body);
    out.flush();
  }
}
