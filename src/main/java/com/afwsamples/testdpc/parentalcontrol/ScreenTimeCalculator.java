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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a stream of system usage events into per-package foreground time.
 *
 * <p>Foreground time is <em>derived</em> from the event log rather than accumulated by a ticking
 * counter, which is what lets the daily allowance survive a reboot or a force stop: nothing was
 * being held in a counter, so re-reading the log reconstructs the same answer.
 *
 * <p>Usage events are per <em>activity</em>, not per app, and that distinction matters. Android
 * dispatches the outgoing activity's stop only after the incoming one has resumed, so navigating
 * within a single app produces {@code PAUSED(A)}, {@code RESUMED(B)}, {@code STOPPED(A)}, all
 * carrying the same package name. Closing the package's interval on that trailing stop would halt
 * the clock while the app is still on screen, so {@code ACTIVITY_STOPPED} is ignored: it always
 * trails a pause for the same activity and adds nothing. Tracking activities by class name is not
 * enough either, as Chrome was observed stopping one instance of a class while a second, newer
 * instance of the very same class was on screen.
 *
 * <p>What keeps the books straight is instead the invariant that only one app is in the foreground
 * at a time, so a resume closes every other package's interval.
 *
 * <p>This class deliberately touches no Android API so the interval arithmetic can be unit tested
 * on a plain JVM.
 */
public final class ScreenTimeCalculator {

  // UsageEvents.Event type constants, repeated here to keep this class framework free.
  public static final int ACTIVITY_RESUMED = 1;
  public static final int ACTIVITY_PAUSED = 2;
  public static final int ACTIVITY_STOPPED = 23;
  public static final int SCREEN_NON_INTERACTIVE = 16;
  public static final int KEYGUARD_SHOWN = 17;
  public static final int DEVICE_SHUTDOWN = 26;

  private ScreenTimeCalculator() {}

  /** One usage event, reduced to the fields the arithmetic needs. */
  public static final class Event {
    public final long timestamp;
    public final int type;
    public final String packageName;
    /** The activity the event refers to; several may be live within one package. */
    public final String className;

    public Event(long timestamp, int type, String packageName, String className) {
      this.timestamp = timestamp;
      this.type = type;
      this.packageName = packageName;
      this.className = className == null ? packageName : className;
    }

    public Event(long timestamp, int type, String packageName) {
      this(timestamp, type, packageName, packageName);
    }
  }

  /**
   * Foreground time accounted so far, plus any interval still open.
   *
   * <p>Keeping open intervals separate from completed ones is what lets the fold run incrementally:
   * an app that has been in the foreground for an hour has no completed interval yet, and its time
   * must not be lost when the window is advanced.
   */
  public static final class Snapshot {
    /** Time in fully closed intervals, per package. */
    public final Map<String, Long> completedMillis;
    /** Packages currently in the foreground, mapped to the instant they got there. */
    public final Map<String, Long> openSince;
    /** The instant up to which events have been folded in. */
    public final long computedUpTo;

    public Snapshot(
        Map<String, Long> completedMillis, Map<String, Long> openSince, long computedUpTo) {
      this.completedMillis = completedMillis;
      this.openSince = openSince;
      this.computedUpTo = computedUpTo;
    }

    public static Snapshot empty(long startingAt) {
      return new Snapshot(new HashMap<>(), new HashMap<>(), startingAt);
    }

    /**
     * A snapshot that keeps only what is still open, with the totals cleared.
     *
     * <p>This is how a new accounting window begins without losing the app that is on screen at the
     * moment the window turns over. Its opening event lies before the new window, so a plain
     * rebuild from the event log would never see it and would stop counting until the next app
     * switch.
     */
    public Snapshot carryOpenInto(long newWindowStart) {
      Map<String, Long> carried = new HashMap<>();
      for (Map.Entry<String, Long> entry : openSince.entrySet()) {
        carried.put(entry.getKey(), Math.max(entry.getValue(), newWindowStart));
      }
      return new Snapshot(new HashMap<>(), carried, newWindowStart);
    }

    /** Foreground time per package as of {@code now}, including intervals still open. */
    public Map<String, Long> totalsAt(long now) {
      Map<String, Long> totals = new HashMap<>(completedMillis);
      for (Map.Entry<String, Long> entry : openSince.entrySet()) {
        long delta = now - entry.getValue();
        if (delta > 0) {
          Long existing = totals.get(entry.getKey());
          totals.put(entry.getKey(), (existing == null ? 0L : existing) + delta);
        }
      }
      return totals;
    }

    public long totalForAt(String packageName, long now) {
      Long value = totalsAt(now).get(packageName);
      return value == null ? 0L : value;
    }

    /** Sum over {@code packages} only, which is how the combined cap is measured. */
    public long sumAt(Iterable<String> packages, long now) {
      Map<String, Long> totals = totalsAt(now);
      long sum = 0L;
      for (String packageName : packages) {
        Long value = totals.get(packageName);
        if (value != null) {
          sum += value;
        }
      }
      return sum;
    }

    /** The package believed to be in the foreground, or null if the device is idle. */
    public String foregroundPackage() {
      String newest = null;
      long newestSince = Long.MIN_VALUE;
      for (Map.Entry<String, Long> entry : openSince.entrySet()) {
        if (entry.getValue() > newestSince) {
          newestSince = entry.getValue();
          newest = entry.getKey();
        }
      }
      return newest;
    }
  }

  /**
   * Folds {@code events} into {@code previous}.
   *
   * @param previous state carried over from the last fold; open intervals continue across calls.
   * @param windowStart nothing before this instant is counted, so a manual reset or a new day
   *     simply moves the window rather than trying to erase system usage stats.
   * @param windowEnd the instant the fold advances to.
   */
  public static Snapshot fold(
      Snapshot previous, List<Event> events, long windowStart, long windowEnd) {
    Map<String, Long> completed = new HashMap<>(previous.completedMillis);
    Map<String, Long> open = new HashMap<>(previous.openSince);

    // An interval that started before the window must be clipped to it, not counted from its
    // original start.
    for (Map.Entry<String, Long> entry : open.entrySet()) {
      if (entry.getValue() < windowStart) {
        entry.setValue(windowStart);
      }
    }

    List<Event> ordered = new ArrayList<>(events);
    Collections.sort(
        ordered,
        new Comparator<Event>() {
          @Override
          public int compare(Event a, Event b) {
            return Long.compare(a.timestamp, b.timestamp);
          }
        });

    for (Event event : ordered) {
      if (event.timestamp < windowStart || event.timestamp > windowEnd) {
        continue;
      }
      switch (event.type) {
        case ACTIVITY_RESUMED:
          if (event.packageName != null) {
            // Only one app is in the foreground at a time. Closing the others here also recovers
            // from an app that died without ever reporting a pause.
            closeAllExcept(completed, open, event.packageName, event.timestamp);
            if (!open.containsKey(event.packageName)) {
              open.put(event.packageName, event.timestamp);
            }
          }
          break;
        case ACTIVITY_PAUSED:
          if (event.packageName != null) {
            closeInterval(completed, open, event.packageName, event.timestamp);
          }
          break;
        case SCREEN_NON_INTERACTIVE:
        case KEYGUARD_SHOWN:
        case DEVICE_SHUTDOWN:
          // Without this an app that was in the foreground when the screen went off, or when the
          // device was powered down, keeps accruing time it never actually got.
          closeAll(completed, open, event.timestamp);
          break;
        default:
          // ACTIVITY_STOPPED deliberately lands here; see the class comment.
          break;
      }
    }
    return new Snapshot(completed, open, windowEnd);
  }

  private static void closeInterval(
      Map<String, Long> completed, Map<String, Long> open, String packageName, long until) {
    Long since = open.remove(packageName);
    if (since == null) {
      return;
    }
    long delta = until - since;
    if (delta <= 0) {
      return;
    }
    Long existing = completed.get(packageName);
    completed.put(packageName, (existing == null ? 0L : existing) + delta);
  }

  private static void closeAll(Map<String, Long> completed, Map<String, Long> open, long until) {
    for (String packageName : new ArrayList<>(open.keySet())) {
      closeInterval(completed, open, packageName, until);
    }
  }

  private static void closeAllExcept(
      Map<String, Long> completed, Map<String, Long> open, String keep, long until) {
    for (String packageName : new ArrayList<>(open.keySet())) {
      if (!packageName.equals(keep)) {
        closeInterval(completed, open, packageName, until);
      }
    }
  }
}
