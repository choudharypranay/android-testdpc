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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build.VERSION_CODES;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.text.TextUtils;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import com.afwsamples.testdpc.PolicyManagementActivity;
import com.afwsamples.testdpc.R;
import com.afwsamples.testdpc.common.Util;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Foreground service that runs both parental control features.
 *
 * <p>It owns a single ticking loop: each tick re-derives foreground time from the usage event log,
 * re-evaluates the screen time policy, and lets the network kill switch expire or heal itself. One
 * loop rather than two keeps a single notification in the shade and makes the ordering between
 * measuring and enforcing obvious.
 */
public class ParentalControlService extends Service implements RemoteControlServer.Actions {

  private static final String TAG = "ParentalControlService";

  private static final String CHANNEL_ID = "parental_control_channel";
  /** Separate, noisier channel: this one is aimed at whoever is holding the phone. */
  private static final String NOTICE_CHANNEL_ID = "parental_control_notice_channel";
  private static final int NOTIFICATION_ID = 1001;
  private static final int NOTICE_NOTIFICATION_ID = 1002;

  public static final String ACTION_REFRESH = "com.afwsamples.testdpc.parentalcontrol.REFRESH";

  /** Cadence while the screen is on. */
  private static final long TICK_ACTIVE_MILLIS = 5000L;
  /** Cadence when a cap is within reach, so the cut-off lands close to the configured minute. */
  private static final long TICK_PRECISE_MILLIS = 1000L;
  /** Cadence while the screen is off, when nothing can accrue. */
  private static final long TICK_IDLE_MILLIS = 30000L;
  /** How close to a cap the precise cadence kicks in. */
  private static final long PRECISE_THRESHOLD_MILLIS = 90_000L;
  /** How often the real suspension state is read back from the platform. */
  private static final long VERIFY_INTERVAL_MILLIS = 60_000L;

  private ScreenTimeStore mStore;
  private UsageStatsSampler mSampler;
  private ScreenTimeAccountant mAccountant;
  private ScreenTimeEnforcer mEnforcer;
  private NetworkKillSwitch mKillSwitch;
  private TamperProtection mTamperProtection;
  private RemoteControlServer mServer;
  private PowerManager mPowerManager;
  private Handler mHandler;
  private BroadcastReceiver mSystemReceiver;

  private Map<String, Long> mTotals = new HashMap<>();
  private String mControlPageHtml;
  /** Packages already announced as blocked, so the notice appears once per block, not per tick. */
  private Set<String> mAnnouncedBlocked = new HashSet<>();
  private long mLastVerifyMillis;
  private String mLastNotificationText;

  private final Runnable mTick =
      new Runnable() {
        @Override
        public void run() {
          long delay = TICK_IDLE_MILLIS;
          try {
            delay = tick();
          } catch (RuntimeException e) {
            Log.e(TAG, "Tick failed", e);
          } finally {
            mHandler.postDelayed(this, delay);
          }
        }
      };

  // ------------------------------------------------------------------
  // Lifecycle
  // ------------------------------------------------------------------

  private static boolean isNeeded(Context context) {
    SharedPreferences prefs = ParentalControlPrefs.get(context);
    return prefs.getBoolean(ParentalControlPrefs.KEY_ST_ENABLED, false)
        || prefs.getBoolean(ParentalControlPrefs.KEY_RC_ENABLED, false)
        || prefs.getBoolean(ParentalControlPrefs.KEY_KS_ACTIVE, false);
  }

  /** Starts the service only if one of the features actually needs it. */
  public static void startIfNeeded(Context context) {
    if (isNeeded(context)) {
      start(context);
    }
  }

  public static void start(Context context) {
    startWithAction(context, null);
  }

  /** Asks the service to pick up a settings change, or stops it if nothing is left to do. */
  public static void refresh(Context context) {
    if (!isNeeded(context)) {
      context.stopService(new Intent(context, ParentalControlService.class));
      return;
    }
    startWithAction(context, ACTION_REFRESH);
  }

  private static void startWithAction(Context context, String action) {
    Intent intent = new Intent(context, ParentalControlService.class);
    if (action != null) {
      intent.setAction(action);
    }
    try {
      if (Util.SDK_INT >= VERSION_CODES.O) {
        context.startForegroundService(intent);
      } else {
        context.startService(intent);
      }
    } catch (RuntimeException e) {
      Log.e(TAG, "Could not start the parental control service", e);
    }
  }

  @Override
  public void onCreate() {
    super.onCreate();
    mStore = new ScreenTimeStore(this);
    mSampler = new UsageStatsSampler(this);
    mAccountant = new ScreenTimeAccountant(mStore, mSampler);
    mEnforcer = new ScreenTimeEnforcer(this, mStore);
    mKillSwitch = new NetworkKillSwitch(this);
    mTamperProtection = new TamperProtection(this, mStore);
    mServer = new RemoteControlServer(this);
    mPowerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
    mHandler = new Handler(Looper.getMainLooper());
    registerSystemReceiver();
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    // Post the real status straight away. Stamping a placeholder here and letting the tick correct
    // it does not work: the tick compares against mLastNotificationText and would skip the update.
    syncServerState();
    mLastNotificationText = buildNotificationText();
    startForeground(NOTIFICATION_ID, buildNotification(mLastNotificationText));
    // A reboot leaves policy in place but nothing re-checked, so restore enforcement before
    // re-entering the loop.
    mKillSwitch.reassert();
    mTamperProtection.reassert();
    if (mKillSwitch.isActive()) {
      KillSwitchExpiryReceiver.schedule(this, mKillSwitch.getBlockedUntil());
    }
    mHandler.removeCallbacks(mTick);
    mHandler.post(mTick);
    return START_STICKY;
  }

  @Override
  public void onDestroy() {
    mHandler.removeCallbacks(mTick);
    if (mSystemReceiver != null) {
      unregisterReceiver(mSystemReceiver);
      mSystemReceiver = null;
    }
    mServer.stop();
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  /**
   * {@code adb shell dumpsys activity service com.afwsamples.testdpc} fans its arguments out to
   * every matching service, so this one would otherwise answer "nothing to dump" alongside the
   * output of TestDPC's own shell command. Printing the live state instead makes that noise useful.
   */
  @Override
  protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
    try {
      writer.println(buildStatus().toString(2));
    } catch (JSONException e) {
      writer.println("Could not build status: " + e);
    }
  }

  private void registerSystemReceiver() {
    IntentFilter filter = new IntentFilter();
    filter.addAction(Intent.ACTION_SCREEN_ON);
    filter.addAction(Intent.ACTION_SCREEN_OFF);
    filter.addAction(Intent.ACTION_USER_PRESENT);
    filter.addAction(Intent.ACTION_TIME_CHANGED);
    filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
    mSystemReceiver =
        new BroadcastReceiver() {
          @Override
          public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
              // The accounting window is expressed in wall clock time, so it has to be rebuilt.
              mAccountant.invalidate();
            }
            // React straight away rather than waiting out the current cadence.
            mHandler.removeCallbacks(mTick);
            mHandler.post(mTick);
          }
        };
    registerReceiver(mSystemReceiver, filter);
  }

  // ------------------------------------------------------------------
  // The loop
  // ------------------------------------------------------------------

  /** @return the delay before the next tick. */
  private long tick() {
    long now = mStore.clampNow(System.currentTimeMillis());

    if (mKillSwitch.releaseIfExpired(now)) {
      Log.i(TAG, "Network block expired");
      KillSwitchExpiryReceiver.cancel(this);
    }

    boolean screenTimeOn = mStore.isEnabled();
    if (screenTimeOn) {
      mTotals = mAccountant.update(mStore.getWindowStart(now), now);
      boolean verify = now - mLastVerifyMillis >= VERIFY_INTERVAL_MILLIS;
      if (verify) {
        mLastVerifyMillis = now;
      }
      mEnforcer.apply(mTotals, now, verify);
      announceNewlyBlocked(now);
    } else {
      mAnnouncedBlocked.clear();
    }

    updateNotification();
    return chooseDelay(screenTimeOn, now);
  }

  private long chooseDelay(boolean screenTimeOn, long now) {
    if (!screenTimeOn) {
      return TICK_IDLE_MILLIS;
    }
    if (mPowerManager != null
        && Util.SDK_INT >= VERSION_CODES.KITKAT_WATCH
        && !mPowerManager.isInteractive()) {
      return TICK_IDLE_MILLIS;
    }
    String current = mAccountant.foregroundPackage();
    if (current != null
        && mStore.getMonitoredPackages().contains(current)
        && remainingMillisFor(current, now) <= PRECISE_THRESHOLD_MILLIS) {
      return TICK_PRECISE_MILLIS;
    }
    return TICK_ACTIVE_MILLIS;
  }

  /** Smallest remaining allowance that applies to {@code packageName}, or Long.MAX_VALUE. */
  private long remainingMillisFor(String packageName, long now) {
    long remaining = Long.MAX_VALUE;
    int perApp = mStore.getPerAppLimitMinutes();
    if (perApp > ScreenTimeStore.NO_LIMIT) {
      remaining =
          Math.min(
              remaining,
              perApp * 60_000L - ScreenTimeAccountant.totalFor(mTotals, packageName));
    }
    int combined = mStore.getCombinedLimitMinutes();
    if (combined > ScreenTimeStore.NO_LIMIT) {
      remaining =
          Math.min(
              remaining,
              combined * 60_000L
                  - ScreenTimeAccountant.sumOf(mTotals, mStore.getMonitoredPackages()));
    }
    return remaining;
  }

  /**
   * Tells whoever is holding the phone why an app just stopped working.
   *
   * <p>The platform's own "Blocked by work policy" dialog cannot be reworded by a device owner:
   * the string lives in Settings and overriding it needs UPDATE_DEVICE_MANAGEMENT_RESOURCES, which
   * is granted by role. This friendlier note gets there first.
   */
  private void announceNewlyBlocked(long now) {
    Set<String> nowBlocked = new HashSet<>();
    ScreenTimeEnforcer.BlockReason firstReason = ScreenTimeEnforcer.BlockReason.NOT_BLOCKED;
    String firstPackage = null;
    for (String packageName : mStore.getMonitoredPackages()) {
      ScreenTimeEnforcer.BlockReason reason = mEnforcer.evaluate(packageName, mTotals, now);
      if (reason == ScreenTimeEnforcer.BlockReason.NOT_BLOCKED) {
        continue;
      }
      nowBlocked.add(packageName);
      if (!mAnnouncedBlocked.contains(packageName) && firstPackage == null) {
        firstPackage = packageName;
        firstReason = reason;
      }
    }
    mAnnouncedBlocked = nowBlocked;
    if (firstPackage == null) {
      return;
    }
    String title;
    String text;
    switch (firstReason) {
      case BLOCKED_WINDOW:
        title = getString(R.string.blocked_notice_window_title);
        text =
            getString(
                R.string.blocked_notice_window_text,
                appLabel(firstPackage),
                formatMinuteOfDay(mStore.getWindowEndMinute()));
        break;
      case PER_APP_LIMIT:
        title = getString(R.string.blocked_notice_app_title);
        text = getString(R.string.blocked_notice_app_text, appLabel(firstPackage));
        break;
      case COMBINED_LIMIT:
        title = getString(R.string.blocked_notice_combined_title);
        text = getString(R.string.blocked_notice_combined_text);
        break;
      default:
        return;
    }
    showNotice(title, text);
  }

  private void showNotice(String title, String text) {
    NotificationManager manager =
        (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    if (manager == null) {
      return;
    }
    if (Util.SDK_INT >= VERSION_CODES.O) {
      NotificationChannel channel =
          new NotificationChannel(
              NOTICE_CHANNEL_ID,
              getString(R.string.blocked_notice_channel),
              NotificationManager.IMPORTANCE_HIGH);
      manager.createNotificationChannel(channel);
    }
    manager.notify(
        NOTICE_NOTIFICATION_ID,
        new NotificationCompat.Builder(this, NOTICE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build());
  }

  // ------------------------------------------------------------------
  // Remote control server
  // ------------------------------------------------------------------

  private void syncServerState() {
    SharedPreferences prefs = ParentalControlPrefs.get(this);
    boolean shouldRun = prefs.getBoolean(ParentalControlPrefs.KEY_RC_ENABLED, false);
    int port = prefs.getInt(ParentalControlPrefs.KEY_RC_PORT, ParentalControlPrefs.DEFAULT_RC_PORT);
    if (!shouldRun) {
      if (mServer.isRunning()) {
        mServer.stop();
      }
      return;
    }
    if (mServer.isRunning() && mServer.getPort() != port) {
      mServer.stop();
    }
    if (!mServer.isRunning()) {
      mServer.start(port);
    }
  }

  @Override
  public void scheduleNetworkBlock(int minutes) {
    // The caller has already been answered; the pause is what lets that reply reach them before
    // their route to this device disappears.
    mHandler.postDelayed(
        () -> {
          mKillSwitch.engage(minutes);
          KillSwitchExpiryReceiver.schedule(this, mKillSwitch.getBlockedUntil());
          updateNotification();
        },
        RemoteControlServer.KILL_DELAY_MILLIS);
  }

  @Override
  public void releaseNetworkBlock() {
    // Requests arrive on the server's accept thread; the accountant, the enforcer and the totals
    // all belong to the tick on the main thread, so every mutation is handed over to it.
    mHandler.post(
        () -> {
          mKillSwitch.release();
          KillSwitchExpiryReceiver.cancel(this);
          updateNotification();
        });
  }

  @Override
  public void resetScreenTime() {
    mHandler.post(
        () -> {
          long now = mStore.clampNow(System.currentTimeMillis());
          mStore.resetUsage(now);
          mAccountant.invalidate();
          mTotals = mAccountant.update(mStore.getWindowStart(now), now);
          mEnforcer.apply(mTotals, now, /* verifyAgainstSystem= */ true);
          updateNotification();
        });
  }

  @Override
  public void setQuietHoursEnabled(boolean enabled) {
    mHandler.post(
        () -> {
          mStore.setWindowEnabled(enabled);
          long now = mStore.clampNow(System.currentTimeMillis());
          mTotals = mAccountant.update(mStore.getWindowStart(now), now);
          mEnforcer.apply(mTotals, now, /* verifyAgainstSystem= */ true);
          updateNotification();
        });
  }

  @Override
  public String getAuthToken() {
    SharedPreferences prefs = ParentalControlPrefs.get(this);
    if (!prefs.getBoolean(ParentalControlPrefs.KEY_RC_REQUIRE_TOKEN, false)) {
      return "";
    }
    return prefs.getString(ParentalControlPrefs.KEY_RC_TOKEN, "");
  }

  @Override
  public String getControlPageHtml() {
    String html = mControlPageHtml;
    if (html == null) {
      html = readRawResource(R.raw.remote_control_page);
      mControlPageHtml = html;
    }
    // The page needs the token to call the endpoints; it is blank unless one is required.
    return html.replace("__TOKEN__", getAuthToken());
  }

  private String readRawResource(int resId) {
    try (java.io.InputStream in = getResources().openRawResource(resId);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) > 0) {
        out.write(buffer, 0, read);
      }
      return out.toString("UTF-8");
    } catch (java.io.IOException e) {
      Log.e(TAG, "Could not read the control page", e);
      return "<html><body>Could not load the control page.</body></html>";
    }
  }

  @Override
  public JSONObject buildStatus() throws JSONException {
    long now = mStore.clampNow(System.currentTimeMillis());
    Map<String, Long> totals =
        mStore.isEnabled() ? mAccountant.update(mStore.getWindowStart(now), now) : mTotals;

    JSONObject root = new JSONObject();
    root.put("ok", true);
    root.put("usageAccessGranted", UsageStatsSampler.hasUsageAccess(this));

    JSONObject screenTime = new JSONObject();
    Set<String> monitored = new TreeSet<>(mStore.getMonitoredPackages());
    screenTime.put("enabled", mStore.isEnabled());
    screenTime.put("windowStartEpochMillis", mStore.getWindowStart(now));
    screenTime.put("perAppLimitMinutes", mStore.getPerAppLimitMinutes());
    screenTime.put("combinedLimitMinutes", mStore.getCombinedLimitMinutes());
    screenTime.put("combinedUsedMinutes", ScreenTimeAccountant.sumOf(totals, monitored) / 60000L);
    screenTime.put("tamperProtection", mStore.isTamperProtectionEnabled());

    JSONObject window = new JSONObject();
    window.put("enabled", mStore.isWindowEnabled());
    window.put("start", formatMinuteOfDay(mStore.getWindowStartMinute()));
    window.put("end", formatMinuteOfDay(mStore.getWindowEndMinute()));
    window.put("active", mStore.isWithinBlockedWindow(now));
    screenTime.put("blockedWindow", window);

    JSONArray apps = new JSONArray();
    JSONArray blocked = new JSONArray();
    for (String packageName : monitored) {
      ScreenTimeEnforcer.BlockReason reason = mEnforcer.evaluate(packageName, totals, now);
      JSONObject app = new JSONObject();
      app.put("package", packageName);
      app.put("label", appLabel(packageName));
      app.put("usedMinutes", ScreenTimeAccountant.totalFor(totals, packageName) / 60000L);
      app.put("limitMinutes", mStore.getPerAppLimitMinutes());
      app.put("blocked", reason != ScreenTimeEnforcer.BlockReason.NOT_BLOCKED);
      app.put("reason", describeReason(reason));
      apps.put(app);
      if (reason != ScreenTimeEnforcer.BlockReason.NOT_BLOCKED) {
        blocked.put(packageName);
      }
    }
    screenTime.put("apps", apps);
    screenTime.put("blocked", blocked);
    root.put("screenTime", screenTime);

    JSONObject network = new JSONObject();
    network.put("blocked", mKillSwitch.isActive());
    network.put("blockedUntilEpochMillis", mKillSwitch.getBlockedUntil());
    network.put("enforcement", new JSONArray(mKillSwitch.describeActiveEnforcement()));
    root.put("network", network);
    return root;
  }

  /** Why an app is blocked, in words, so the control page need not infer it. */
  private String describeReason(ScreenTimeEnforcer.BlockReason reason) {
    switch (reason) {
      case BLOCKED_WINDOW:
        return getString(R.string.screen_time_reason_window_short);
      case PER_APP_LIMIT:
        return getString(R.string.screen_time_reason_per_app_short);
      case COMBINED_LIMIT:
        return getString(R.string.screen_time_reason_combined_short);
      default:
        return "";
    }
  }

  private String appLabel(String packageName) {
    try {
      return getPackageManager()
          .getApplicationLabel(getPackageManager().getApplicationInfo(packageName, 0))
          .toString();
    } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException e) {
      return packageName;
    }
  }

  static String formatMinuteOfDay(int minuteOfDay) {
    return String.format(Locale.US, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60);
  }

  // ------------------------------------------------------------------
  // Notification
  // ------------------------------------------------------------------

  private void updateNotification() {
    String text = buildNotificationText();
    if (text.equals(mLastNotificationText)) {
      return;
    }
    mLastNotificationText = text;
    NotificationManager manager =
        (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    if (manager != null) {
      manager.notify(NOTIFICATION_ID, buildNotification(text));
    }
  }

  private String buildNotificationText() {
    List<String> parts = new ArrayList<>();
    if (mStore.isEnabled()) {
      long usedMinutes =
          ScreenTimeAccountant.sumOf(mTotals, mStore.getMonitoredPackages()) / 60000L;
      int combined = mStore.getCombinedLimitMinutes();
      if (combined > ScreenTimeStore.NO_LIMIT) {
        parts.add(getString(R.string.parental_control_status_screen_time, usedMinutes, combined));
      } else {
        parts.add(getString(R.string.parental_control_status_screen_time_no_cap, usedMinutes));
      }
    }
    if (mKillSwitch.isActive()) {
      parts.add(getString(R.string.parental_control_status_network_off));
    }
    if (mServer.isRunning()) {
      parts.add(getString(R.string.parental_control_status_server, mServer.getPort()));
    }
    if (parts.isEmpty()) {
      return getString(R.string.parental_control_status_idle);
    }
    return TextUtils.join(" | ", parts);
  }

  private Notification buildNotification(String text) {
    createChannel();
    int flags =
        Util.SDK_INT >= VERSION_CODES.M
            ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            : PendingIntent.FLAG_UPDATE_CURRENT;
    PendingIntent contentIntent =
        PendingIntent.getActivity(
            this, 0, new Intent(this, PolicyManagementActivity.class), flags);
    return new NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle(getString(R.string.parental_control_title))
        .setContentText(text)
        .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
        .setContentIntent(contentIntent)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build();
  }

  private void createChannel() {
    if (Util.SDK_INT < VERSION_CODES.O) {
      return;
    }
    NotificationManager manager =
        (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    if (manager == null) {
      return;
    }
    NotificationChannel channel =
        new NotificationChannel(
            CHANNEL_ID,
            getString(R.string.parental_control_title),
            NotificationManager.IMPORTANCE_LOW);
    channel.setShowBadge(false);
    manager.createNotificationChannel(channel);
  }
}
