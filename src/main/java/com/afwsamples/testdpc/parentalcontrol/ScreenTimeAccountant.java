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

import android.util.Log;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Produces the authoritative "time used today, per app" figure.
 *
 * <p>Neither of the two obvious designs is correct on its own. Simply accumulating a counter every
 * tick drifts and loses whatever happened while the process was dead. Deriving the total purely
 * from {@link android.app.usage.UsageStatsManager} is exact and drift-free, but the platform
 * buffers usage events in memory and only flushes them periodically, so an abrupt reboot silently
 * discards the last minute or so. Measured on a Pixel 4a, rebooting out of a monitored app erased
 * roughly seventy seconds of foreground time, which is exactly the loophole a child would find.
 *
 * <p>So both are used. The event log supplies precise interval arithmetic, and a running total is
 * persisted every tick to act as a floor. When the process starts, whatever the log has lost is
 * recovered as a fixed per-package offset:
 *
 * <pre>offset = max(0, persistedFloor - derivedFromLog)</pre>
 *
 * <p>and from then on the reported total is {@code derived + offset}, which keeps growing correctly
 * as the log records new activity. The offset is computed once per accounting window, never
 * compounded.
 */
public class ScreenTimeAccountant {

  private static final String TAG = "ScreenTimeAccountant";

  /** How often the whole window is re-derived from the log as a correctness net. */
  private static final long FULL_REBUILD_INTERVAL_MILLIS = 10 * 60 * 1000L;

  private final ScreenTimeStore mStore;
  private final UsageStatsSampler mSampler;

  private ScreenTimeCalculator.Snapshot mSnapshot;
  private Map<String, Long> mOffsets = new HashMap<>();
  private Map<String, Long> mTotals = new HashMap<>();
  private long mWindowStart = -1L;
  private long mLastFullRebuild;

  public ScreenTimeAccountant(ScreenTimeStore store, UsageStatsSampler sampler) {
    mStore = store;
    mSampler = sampler;
  }

  /** Drops derived state so the next call rebuilds from the log. */
  public void invalidate() {
    mSnapshot = null;
    mWindowStart = -1L;
  }

  /** The package currently in the foreground according to the log, or null. */
  public String foregroundPackage() {
    return mSnapshot == null ? null : mSnapshot.foregroundPackage();
  }

  /**
   * Recomputes the totals and persists them as the new floor.
   *
   * <p>Only the owning service should call this; read-only callers such as the settings screen use
   * {@link #computeWithoutPersisting} so two components never fight over the stored floor.
   */
  public Map<String, Long> update(long windowStart, long nowMillis) {
    mTotals = compute(windowStart, nowMillis, /* persist= */ true);
    return new HashMap<>(mTotals);
  }

  /** Same calculation, but leaves the persisted floor alone. */
  public Map<String, Long> computeWithoutPersisting(long windowStart, long nowMillis) {
    mTotals = compute(windowStart, nowMillis, /* persist= */ false);
    return new HashMap<>(mTotals);
  }

  private Map<String, Long> compute(long windowStart, long nowMillis, boolean persist) {
    boolean windowChanged = windowStart != mWindowStart;
    boolean rebuild =
        mSnapshot == null
            || windowChanged
            || nowMillis < mSnapshot.computedUpTo
            || nowMillis - mLastFullRebuild >= FULL_REBUILD_INTERVAL_MILLIS;

    if (rebuild) {
      mLastFullRebuild = nowMillis;
      // Carry the open intervals across the rebuild. Dropping them would stop the clock for
      // whatever app is on screen when the day turns over, since its opening event sits before
      // the new window and a fresh query would never see it.
      mSnapshot = mSampler.rebuild(mSnapshot, windowStart, nowMillis);
      if (windowChanged) {
        // A new window means a new day or a manual reset: recover the floor once, then hold the
        // offset steady so later rebuilds cannot compound it.
        mOffsets = computeOffsets(windowStart, mSnapshot.totalsAt(nowMillis));
        mWindowStart = windowStart;
      }
    } else {
      mSnapshot = mSampler.advance(mSnapshot, windowStart, nowMillis);
    }

    Map<String, Long> totals = mSnapshot.totalsAt(nowMillis);
    for (Map.Entry<String, Long> entry : mOffsets.entrySet()) {
      Long derived = totals.get(entry.getKey());
      totals.put(entry.getKey(), (derived == null ? 0L : derived) + entry.getValue());
    }

    if (persist) {
      mStore.setCommittedTotals(windowStart, totals, mStore.getMonitoredPackages());
    }
    return totals;
  }

  /**
   * Works out how much foreground time the event log lost, by comparing it against the floor that
   * was persisted before this process started.
   */
  private Map<String, Long> computeOffsets(long windowStart, Map<String, Long> derived) {
    Map<String, Long> offsets = new HashMap<>();
    if (mStore.getCommittedWindowStart() != windowStart) {
      // The floor belongs to a different day or to the state before a reset; ignore it.
      return offsets;
    }
    Map<String, Long> floor = mStore.getCommittedTotals();
    Set<String> packages = new HashSet<>(floor.keySet());
    for (String packageName : packages) {
      long committed = floor.get(packageName);
      Long derivedValue = derived.get(packageName);
      long missing = committed - (derivedValue == null ? 0L : derivedValue);
      if (missing > 0L) {
        offsets.put(packageName, missing);
        Log.i(
            TAG,
            "Recovered "
                + missing
                + "ms of "
                + packageName
                + " that the usage event log did not persist");
      }
    }
    return offsets;
  }

  static long totalFor(Map<String, Long> totals, String packageName) {
    Long value = totals.get(packageName);
    return value == null ? 0L : value;
  }

  static long sumOf(Map<String, Long> totals, Iterable<String> packages) {
    long sum = 0L;
    for (String packageName : packages) {
      sum += totalFor(totals, packageName);
    }
    return sum;
  }
}
