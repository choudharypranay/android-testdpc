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

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Configuration for the screen time limiter, plus the small amount of state that genuinely has to
 * be persisted.
 *
 * <p>Usage itself is derived from the system event log by {@link ScreenTimeCalculator}; what is
 * stored here is where the current accounting window starts, plus the running totals that {@link
 * ScreenTimeAccountant} keeps as a floor. Both are needed: the window survives a manual reset, and
 * the floor survives a reboot that the platform's own event buffer does not.
 */
public class ScreenTimeStore {

  /** Sentinel meaning "no limit configured". */
  public static final int NO_LIMIT = 0;

  /** A backwards clock jump larger than this is treated as tampering rather than a correction. */
  private static final long CLOCK_REGRESSION_TOLERANCE_MILLIS = 5 * 60 * 1000L;

  private final SharedPreferences mPrefs;

  public ScreenTimeStore(Context context) {
    mPrefs = ParentalControlPrefs.get(context);
  }

  // ------------------------------------------------------------------
  // Configuration
  // ------------------------------------------------------------------

  public boolean isEnabled() {
    return mPrefs.getBoolean(ParentalControlPrefs.KEY_ST_ENABLED, false);
  }

  public void setEnabled(boolean enabled) {
    mPrefs.edit().putBoolean(ParentalControlPrefs.KEY_ST_ENABLED, enabled).commit();
  }

  public Set<String> getMonitoredPackages() {
    // The set handed back by SharedPreferences must not be mutated, so copy it.
    return new HashSet<>(
        mPrefs.getStringSet(ParentalControlPrefs.KEY_ST_MONITORED, Collections.emptySet()));
  }

  public void setMonitoredPackages(Set<String> packages) {
    mPrefs
        .edit()
        .putStringSet(ParentalControlPrefs.KEY_ST_MONITORED, new HashSet<>(packages))
        .commit();
  }

  /** Per-app daily cap in minutes, or {@link #NO_LIMIT}. */
  public int getPerAppLimitMinutes() {
    return mPrefs.getInt(ParentalControlPrefs.KEY_ST_PER_APP_MINUTES, NO_LIMIT);
  }

  public void setPerAppLimitMinutes(int minutes) {
    mPrefs.edit().putInt(ParentalControlPrefs.KEY_ST_PER_APP_MINUTES, minutes).commit();
  }

  /** Combined daily cap across every monitored app in minutes, or {@link #NO_LIMIT}. */
  public int getCombinedLimitMinutes() {
    return mPrefs.getInt(ParentalControlPrefs.KEY_ST_COMBINED_MINUTES, NO_LIMIT);
  }

  public void setCombinedLimitMinutes(int minutes) {
    mPrefs.edit().putInt(ParentalControlPrefs.KEY_ST_COMBINED_MINUTES, minutes).commit();
  }

  public boolean isWindowEnabled() {
    return mPrefs.getBoolean(ParentalControlPrefs.KEY_ST_WINDOW_ENABLED, false);
  }

  public void setWindowEnabled(boolean enabled) {
    mPrefs.edit().putBoolean(ParentalControlPrefs.KEY_ST_WINDOW_ENABLED, enabled).commit();
  }

  /** Start of the blocked window as minutes since local midnight. */
  public int getWindowStartMinute() {
    return mPrefs.getInt(ParentalControlPrefs.KEY_ST_WINDOW_START, 22 * 60);
  }

  /** End of the blocked window as minutes since local midnight. */
  public int getWindowEndMinute() {
    return mPrefs.getInt(ParentalControlPrefs.KEY_ST_WINDOW_END, 8 * 60);
  }

  public void setWindow(int startMinuteOfDay, int endMinuteOfDay) {
    mPrefs
        .edit()
        .putInt(ParentalControlPrefs.KEY_ST_WINDOW_START, startMinuteOfDay)
        .putInt(ParentalControlPrefs.KEY_ST_WINDOW_END, endMinuteOfDay)
        .commit();
  }

  /**
   * Whether {@code nowMillis} falls inside the blocked window. Windows that wrap past midnight,
   * such as 22:00 to 08:00, are supported. A window whose two ends are equal counts as inactive
   * rather than as "always blocked", so a mis-set window can never lock the child out completely.
   */
  public boolean isWithinBlockedWindow(long nowMillis) {
    if (!isWindowEnabled()) {
      return false;
    }
    int start = getWindowStartMinute();
    int end = getWindowEndMinute();
    if (start == end) {
      return false;
    }
    int nowMinute = minuteOfDay(nowMillis);
    if (start < end) {
      return nowMinute >= start && nowMinute < end;
    }
    return nowMinute >= start || nowMinute < end;
  }

  // ------------------------------------------------------------------
  // The accounting window
  // ------------------------------------------------------------------

  /**
   * Returns a wall clock reading that cannot be moved by changing the device clock.
   *
   * <p>Both directions have to be defended. Winding the clock back would replay a day that has
   * already been spent; winding it <em>forward</em> is worse, because it rolls the accounting
   * window into a fresh day, wipes the persisted floor, and leaves every later query asking the
   * usage log about a range in the future, which reports nothing at all.
   *
   * <p>The defence is {@link SystemClock#elapsedRealtime()}, which no setting can change. Within a
   * boot session the wall clock may only advance by as much as the monotonic clock did; a reading
   * that disagrees by more than a couple of minutes is replaced by the monotonic estimate. A reboot
   * resets the monotonic clock, so the pair is re-anchored then and only the high-water mark
   * guards against going backwards.
   */
  public long clampNow(long nowMillis) {
    long elapsed = SystemClock.elapsedRealtime();
    long lastWall = mPrefs.getLong(ParentalControlPrefs.KEY_ST_CLOCK_WALL, 0L);
    long lastElapsed = mPrefs.getLong(ParentalControlPrefs.KEY_ST_CLOCK_ELAPSED, -1L);
    long highWater = mPrefs.getLong(ParentalControlPrefs.KEY_ST_MAX_WALL_CLOCK, 0L);

    long accepted;
    if (lastWall > 0L && lastElapsed >= 0L && elapsed >= lastElapsed) {
      long expected = lastWall + (elapsed - lastElapsed);
      accepted =
          Math.abs(nowMillis - expected) > CLOCK_REGRESSION_TOLERANCE_MILLIS ? expected : nowMillis;
    } else {
      // First run, or the monotonic clock restarted with the device. Trust the reading unless it
      // is behind everything already seen.
      accepted =
          (highWater - nowMillis > CLOCK_REGRESSION_TOLERANCE_MILLIS) ? highWater : nowMillis;
    }

    mPrefs
        .edit()
        .putLong(ParentalControlPrefs.KEY_ST_CLOCK_WALL, accepted)
        .putLong(ParentalControlPrefs.KEY_ST_CLOCK_ELAPSED, elapsed)
        .putLong(ParentalControlPrefs.KEY_ST_MAX_WALL_CLOCK, Math.max(highWater, accepted))
        .apply();
    return accepted;
  }

  /**
   * The instant the current allowance started counting from: local midnight, or the moment of a
   * manual reset if that happened later today.
   */
  public long getWindowStart(long nowMillis) {
    long midnight = localMidnight(nowMillis);
    long baseline = mPrefs.getLong(ParentalControlPrefs.KEY_ST_RESET_BASELINE, 0L);
    return Math.max(midnight, Math.min(baseline, nowMillis));
  }

  /**
   * Starts a fresh allowance from {@code nowMillis}.
   *
   * <p>System usage stats cannot be erased, so a reset moves the window forward instead of
   * clearing a counter. The effect is the same and it cannot be undone by restarting anything.
   */
  public void resetUsage(long nowMillis) {
    mPrefs.edit().putLong(ParentalControlPrefs.KEY_ST_RESET_BASELINE, nowMillis).commit();
  }

  public long getResetBaseline() {
    return mPrefs.getLong(ParentalControlPrefs.KEY_ST_RESET_BASELINE, 0L);
  }

  // ------------------------------------------------------------------
  // Committed totals
  // ------------------------------------------------------------------

  /** The accounting window the stored totals belong to, or 0 if none are stored. */
  public long getCommittedWindowStart() {
    return mPrefs.getLong(ParentalControlPrefs.KEY_ST_COMMIT_WINDOW, 0L);
  }

  public Map<String, Long> getCommittedTotals() {
    Map<String, Long> totals = new HashMap<>();
    for (Map.Entry<String, ?> entry : mPrefs.getAll().entrySet()) {
      String key = entry.getKey();
      if (key.startsWith(ParentalControlPrefs.PREFIX_ST_COMMITTED)
          && entry.getValue() instanceof Long) {
        totals.put(
            key.substring(ParentalControlPrefs.PREFIX_ST_COMMITTED.length()),
            (Long) entry.getValue());
      }
    }
    return totals;
  }

  /**
   * Persists the running totals so that a reboot cannot give back time the platform's usage event
   * buffer failed to flush.
   *
   * <p>Only {@code keep} is stored. Writing every package the device saw today would grow this
   * file without bound for no benefit, since only monitored apps are ever enforced.
   */
  public void setCommittedTotals(long windowStart, Map<String, Long> totals, Set<String> keep) {
    SharedPreferences.Editor editor = mPrefs.edit();
    for (String key : mPrefs.getAll().keySet()) {
      if (key.startsWith(ParentalControlPrefs.PREFIX_ST_COMMITTED)) {
        editor.remove(key);
      }
    }
    for (Map.Entry<String, Long> entry : totals.entrySet()) {
      if (entry.getValue() != null && entry.getValue() > 0L && keep.contains(entry.getKey())) {
        editor.putLong(
            ParentalControlPrefs.PREFIX_ST_COMMITTED + entry.getKey(), entry.getValue());
      }
    }
    editor.putLong(ParentalControlPrefs.KEY_ST_COMMIT_WINDOW, windowStart);
    editor.apply();
  }

  public static long localMidnight(long millis) {
    Calendar calendar = Calendar.getInstance();
    calendar.setTimeInMillis(millis);
    calendar.set(Calendar.HOUR_OF_DAY, 0);
    calendar.set(Calendar.MINUTE, 0);
    calendar.set(Calendar.SECOND, 0);
    calendar.set(Calendar.MILLISECOND, 0);
    return calendar.getTimeInMillis();
  }

  public static int minuteOfDay(long millis) {
    Calendar calendar = Calendar.getInstance();
    calendar.setTimeInMillis(millis);
    return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE);
  }

  // ------------------------------------------------------------------
  // Suspension bookkeeping
  // ------------------------------------------------------------------

  /**
   * Packages the limiter suspended itself. Tracking them means turning the feature off lifts only
   * its own suspensions and leaves any set by hand on TestDPC's "Suspend apps" screen alone.
   */
  public Set<String> getSuspendedByUs() {
    return new HashSet<>(
        mPrefs.getStringSet(ParentalControlPrefs.KEY_ST_SUSPENDED_BY_US, Collections.emptySet()));
  }

  public void setSuspendedByUs(Set<String> packages) {
    mPrefs
        .edit()
        .putStringSet(ParentalControlPrefs.KEY_ST_SUSPENDED_BY_US, new HashSet<>(packages))
        .commit();
  }

  // ------------------------------------------------------------------
  // Tamper protection
  // ------------------------------------------------------------------

  public boolean isTamperProtectionEnabled() {
    return mPrefs.getBoolean(ParentalControlPrefs.KEY_ST_TAMPER_PROTECTION, false);
  }

  public void setTamperProtectionEnabled(boolean enabled) {
    mPrefs.edit().putBoolean(ParentalControlPrefs.KEY_ST_TAMPER_PROTECTION, enabled).commit();
  }

  public Set<String> getTamperOwnedRestrictions() {
    return new HashSet<>(
        mPrefs.getStringSet(
            ParentalControlPrefs.KEY_ST_TAMPER_OWNED_RESTRICTIONS, Collections.emptySet()));
  }

  public void setTamperOwnedRestrictions(Set<String> keys) {
    mPrefs
        .edit()
        .putStringSet(
            ParentalControlPrefs.KEY_ST_TAMPER_OWNED_RESTRICTIONS, new HashSet<>(keys))
        .commit();
  }
}
