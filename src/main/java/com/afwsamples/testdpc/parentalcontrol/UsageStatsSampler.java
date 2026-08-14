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

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build.VERSION_CODES;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;
import com.afwsamples.testdpc.common.Util;
import java.util.ArrayList;
import java.util.List;

/**
 * Feeds {@link ScreenTimeCalculator} from {@link UsageStatsManager}.
 *
 * <p>Folds are incremental so that a five second cadence stays cheap, but the whole window is
 * rebuilt from the event log on start-up, after a reset, when the day turns over and periodically
 * as a drift guard. The rebuild is the reason a reboot or a force stop costs no accounted time.
 */
public class UsageStatsSampler {

  private static final String TAG = "UsageStatsSampler";

  private final UsageStatsManager mUsageStatsManager;

  public UsageStatsSampler(Context context) {
    mUsageStatsManager =
        (UsageStatsManager)
            context.getApplicationContext().getSystemService(Context.USAGE_STATS_SERVICE);
  }

  /** Whether the "Usage access" special permission has been granted to TestDPC. */
  public static boolean hasUsageAccess(Context context) {
    AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
    if (appOps == null) {
      return false;
    }
    int mode;
    try {
      if (Util.SDK_INT >= VERSION_CODES.Q) {
        mode =
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.getPackageName());
      } else {
        mode =
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.getPackageName());
      }
    } catch (RuntimeException e) {
      Log.w(TAG, "Could not read the usage stats app op", e);
      return false;
    }
    if (mode == AppOpsManager.MODE_DEFAULT) {
      return context.checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS)
          == PackageManager.PERMISSION_GRANTED;
    }
    return mode == AppOpsManager.MODE_ALLOWED;
  }

  /** Opens the system screen where "Usage access" is granted. */
  public static Intent usageAccessSettingsIntent() {
    return new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
  }

  /**
   * How far back a cold rebuild looks to discover which app was already on screen when the
   * accounting window opened. Its opening event lies before the window, so without this the app
   * being used at midnight would stop being counted until the next app switch.
   */
  private static final long OPEN_INTERVAL_LOOKBACK_MILLIS = 12 * 60 * 60 * 1000L;

  /**
   * Re-derives the whole window from the event log.
   *
   * @param carriedOpen state from a previous fold whose open intervals should continue, or null to
   *     discover them from the log.
   */
  public ScreenTimeCalculator.Snapshot rebuild(
      ScreenTimeCalculator.Snapshot carriedOpen, long windowStart, long nowMillis) {
    ScreenTimeCalculator.Snapshot seed =
        carriedOpen != null
            ? carriedOpen.carryOpenInto(windowStart)
            : discoverOpenIntervals(windowStart);
    return ScreenTimeCalculator.fold(seed, readEvents(windowStart, nowMillis), windowStart, nowMillis);
  }

  /** Replays the run-up to {@code windowStart} purely to learn what was open at that instant. */
  private ScreenTimeCalculator.Snapshot discoverOpenIntervals(long windowStart) {
    long lookbackStart = windowStart - OPEN_INTERVAL_LOOKBACK_MILLIS;
    ScreenTimeCalculator.Snapshot before =
        ScreenTimeCalculator.fold(
            ScreenTimeCalculator.Snapshot.empty(lookbackStart),
            readEvents(lookbackStart, windowStart),
            lookbackStart,
            windowStart);
    return before.carryOpenInto(windowStart);
  }

  /** Folds only the events that appeared since {@code previous} was computed. */
  public ScreenTimeCalculator.Snapshot advance(
      ScreenTimeCalculator.Snapshot previous, long windowStart, long nowMillis) {
    return ScreenTimeCalculator.fold(
        previous, readEvents(previous.computedUpTo, nowMillis), windowStart, nowMillis);
  }

  private List<ScreenTimeCalculator.Event> readEvents(long begin, long end) {
    List<ScreenTimeCalculator.Event> result = new ArrayList<>();
    if (mUsageStatsManager == null || begin >= end) {
      return result;
    }
    UsageEvents events;
    try {
      events = mUsageStatsManager.queryEvents(begin, end);
    } catch (RuntimeException e) {
      Log.e(TAG, "queryEvents(" + begin + ", " + end + ") failed", e);
      return result;
    }
    if (events == null) {
      return result;
    }
    UsageEvents.Event event = new UsageEvents.Event();
    while (events.hasNextEvent()) {
      events.getNextEvent(event);
      result.add(
          new ScreenTimeCalculator.Event(
              event.getTimeStamp(),
              event.getEventType(),
              event.getPackageName(),
              event.getClassName()));
    }
    return result;
  }
}
