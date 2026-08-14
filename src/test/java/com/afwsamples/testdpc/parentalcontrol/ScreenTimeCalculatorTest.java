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

import static com.google.common.truth.Truth.assertThat;

import com.afwsamples.testdpc.parentalcontrol.ScreenTimeCalculator.Event;
import com.afwsamples.testdpc.parentalcontrol.ScreenTimeCalculator.Snapshot;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for the foreground time arithmetic behind the screen time limiter. */
@RunWith(JUnit4.class)
public class ScreenTimeCalculatorTest {

  private static final String YOUTUBE = "com.google.android.youtube";
  private static final String CHROME = "com.android.chrome";

  private static final long DAY_START = 1_000_000L;
  private static final long MINUTE = 60_000L;

  private static Event resumed(long at, String packageName) {
    return new Event(at, ScreenTimeCalculator.ACTIVITY_RESUMED, packageName);
  }

  private static Event paused(long at, String packageName) {
    return new Event(at, ScreenTimeCalculator.ACTIVITY_PAUSED, packageName);
  }

  private static Snapshot foldFresh(List<Event> events, long windowEnd) {
    return ScreenTimeCalculator.fold(
        Snapshot.empty(DAY_START), events, DAY_START, windowEnd);
  }

  @Test
  public void closedInterval_isCounted() {
    Snapshot snapshot =
        foldFresh(
            Arrays.asList(resumed(DAY_START + MINUTE, YOUTUBE), paused(DAY_START + 6 * MINUTE, YOUTUBE)),
            DAY_START + 10 * MINUTE);

    assertThat(snapshot.totalForAt(YOUTUBE, DAY_START + 10 * MINUTE)).isEqualTo(5 * MINUTE);
  }

  @Test
  public void openInterval_countsUpToNow() {
    Snapshot snapshot =
        foldFresh(Collections.singletonList(resumed(DAY_START + MINUTE, YOUTUBE)), DAY_START + 4 * MINUTE);

    assertThat(snapshot.totalForAt(YOUTUBE, DAY_START + 4 * MINUTE)).isEqualTo(3 * MINUTE);
    assertThat(snapshot.foregroundPackage()).isEqualTo(YOUTUBE);
  }

  @Test
  public void screenOff_closesOpenIntervals() {
    // Without this the phone going to sleep on an open app would bill the whole night to it.
    Snapshot snapshot =
        foldFresh(
            Arrays.asList(
                resumed(DAY_START, YOUTUBE),
                new Event(
                    DAY_START + 2 * MINUTE, ScreenTimeCalculator.SCREEN_NON_INTERACTIVE, "android")),
            DAY_START + 600 * MINUTE);

    assertThat(snapshot.totalForAt(YOUTUBE, DAY_START + 600 * MINUTE)).isEqualTo(2 * MINUTE);
    assertThat(snapshot.foregroundPackage()).isNull();
  }

  @Test
  public void deviceShutdown_closesOpenIntervals() {
    Snapshot snapshot =
        foldFresh(
            Arrays.asList(
                resumed(DAY_START, YOUTUBE),
                new Event(DAY_START + 3 * MINUTE, ScreenTimeCalculator.DEVICE_SHUTDOWN, "android")),
            DAY_START + 120 * MINUTE);

    assertThat(snapshot.totalForAt(YOUTUBE, DAY_START + 120 * MINUTE)).isEqualTo(3 * MINUTE);
  }

  @Test
  public void eventsBeforeWindowStart_areIgnored() {
    // This is what a manual reset does: it moves the window rather than erasing system stats.
    Snapshot snapshot =
        ScreenTimeCalculator.fold(
            Snapshot.empty(DAY_START + 10 * MINUTE),
            Arrays.asList(
                resumed(DAY_START, YOUTUBE),
                paused(DAY_START + 5 * MINUTE, YOUTUBE),
                resumed(DAY_START + 11 * MINUTE, YOUTUBE),
                paused(DAY_START + 13 * MINUTE, YOUTUBE)),
            DAY_START + 10 * MINUTE,
            DAY_START + 20 * MINUTE);

    assertThat(snapshot.totalForAt(YOUTUBE, DAY_START + 20 * MINUTE)).isEqualTo(2 * MINUTE);
  }

  @Test
  public void intervalOpenBeforeWindow_isClippedToWindowStart() {
    Snapshot carried =
        foldFresh(Collections.singletonList(resumed(DAY_START, YOUTUBE)), DAY_START + 5 * MINUTE);

    // A reset at +5 minutes must not credit the 5 minutes that came before it.
    long newWindowStart = DAY_START + 5 * MINUTE;
    Snapshot after =
        ScreenTimeCalculator.fold(
            carried, Collections.emptyList(), newWindowStart, newWindowStart + 2 * MINUTE);

    assertThat(after.totalForAt(YOUTUBE, newWindowStart + 2 * MINUTE)).isEqualTo(2 * MINUTE);
  }

  @Test
  public void incrementalFold_matchesSingleFold() {
    List<Event> events =
        Arrays.asList(
            resumed(DAY_START + MINUTE, YOUTUBE),
            paused(DAY_START + 4 * MINUTE, YOUTUBE),
            resumed(DAY_START + 4 * MINUTE, CHROME),
            paused(DAY_START + 9 * MINUTE, CHROME),
            resumed(DAY_START + 9 * MINUTE, YOUTUBE));
    long now = DAY_START + 12 * MINUTE;

    Snapshot single = foldFresh(events, now);

    Snapshot incremental = Snapshot.empty(DAY_START);
    long cursor = DAY_START;
    for (long step = DAY_START + 2 * MINUTE; step <= now; step += 2 * MINUTE) {
      incremental =
          ScreenTimeCalculator.fold(incremental, slice(events, cursor, step), DAY_START, step);
      cursor = step;
    }

    assertThat(incremental.totalForAt(YOUTUBE, now)).isEqualTo(single.totalForAt(YOUTUBE, now));
    assertThat(incremental.totalForAt(CHROME, now)).isEqualTo(single.totalForAt(CHROME, now));
    assertThat(single.totalForAt(YOUTUBE, now)).isEqualTo(6 * MINUTE);
    assertThat(single.totalForAt(CHROME, now)).isEqualTo(5 * MINUTE);
  }

  @Test
  public void sumAt_addsOnlyTheGivenPackages() {
    Snapshot snapshot =
        foldFresh(
            Arrays.asList(
                resumed(DAY_START, YOUTUBE),
                paused(DAY_START + 2 * MINUTE, YOUTUBE),
                resumed(DAY_START + 2 * MINUTE, CHROME),
                paused(DAY_START + 5 * MINUTE, CHROME),
                resumed(DAY_START + 5 * MINUTE, "com.example.unmonitored"),
                paused(DAY_START + 30 * MINUTE, "com.example.unmonitored")),
            DAY_START + 30 * MINUTE);

    assertThat(snapshot.sumAt(Arrays.asList(YOUTUBE, CHROME), DAY_START + 30 * MINUTE))
        .isEqualTo(5 * MINUTE);
  }

  @Test
  public void unbalancedPause_withoutResume_doesNotGoNegative() {
    Snapshot snapshot =
        foldFresh(Collections.singletonList(paused(DAY_START + MINUTE, YOUTUBE)), DAY_START + 5 * MINUTE);

    assertThat(snapshot.totalForAt(YOUTUBE, DAY_START + 5 * MINUTE)).isEqualTo(0L);
  }

  @Test
  public void outOfOrderEvents_areSortedBeforeFolding() {
    Snapshot snapshot =
        foldFresh(
            Arrays.asList(paused(DAY_START + 5 * MINUTE, YOUTUBE), resumed(DAY_START + MINUTE, YOUTUBE)),
            DAY_START + 10 * MINUTE);

    assertThat(snapshot.totalForAt(YOUTUBE, DAY_START + 10 * MINUTE)).isEqualTo(4 * MINUTE);
  }

  @Test
  public void activitySwitchWithinOneApp_keepsCounting() {
    // Android stops the outgoing activity only after the incoming one resumes, so a package emits
    // PAUSED(A), RESUMED(B), STOPPED(A). Treating that trailing STOPPED as "the app left the
    // foreground" would stop the clock while the app is still on screen.
    Snapshot snapshot =
        foldFresh(
            Arrays.asList(
                new Event(DAY_START, ScreenTimeCalculator.ACTIVITY_RESUMED, CHROME, "TabbedActivity"),
                new Event(
                    DAY_START + MINUTE, ScreenTimeCalculator.ACTIVITY_PAUSED, CHROME, "TabbedActivity"),
                new Event(
                    DAY_START + MINUTE + 200,
                    ScreenTimeCalculator.ACTIVITY_RESUMED,
                    CHROME,
                    "SettingsActivity"),
                new Event(
                    DAY_START + MINUTE + 400,
                    ScreenTimeCalculator.ACTIVITY_STOPPED,
                    CHROME,
                    "TabbedActivity")),
            DAY_START + 60 * MINUTE);

    assertThat(snapshot.foregroundPackage()).isEqualTo(CHROME);
    // One minute closed, then the rest of the hour still accruing under the second activity.
    assertThat(snapshot.totalForAt(CHROME, DAY_START + 60 * MINUTE))
        .isEqualTo(60 * MINUTE - 200);
  }

  @Test
  public void secondInstanceOfTheSameActivityClass_keepsCounting() {
    // Observed on a Pixel 4a: Chrome resumed a fresh FirstRunActivity and only then stopped the
    // previous instance of that same class. Keying open state on the class name would have closed
    // the package while it was still on screen.
    Snapshot snapshot =
        foldFresh(
            Arrays.asList(
                new Event(DAY_START, ScreenTimeCalculator.ACTIVITY_RESUMED, CHROME, "FirstRun"),
                new Event(
                    DAY_START + MINUTE, ScreenTimeCalculator.ACTIVITY_PAUSED, CHROME, "FirstRun"),
                new Event(
                    DAY_START + MINUTE, ScreenTimeCalculator.ACTIVITY_RESUMED, CHROME, "FirstRun"),
                new Event(
                    DAY_START + MINUTE, ScreenTimeCalculator.ACTIVITY_STOPPED, CHROME, "FirstRun")),
            DAY_START + 30 * MINUTE);

    assertThat(snapshot.foregroundPackage()).isEqualTo(CHROME);
    assertThat(snapshot.totalForAt(CHROME, DAY_START + 30 * MINUTE)).isEqualTo(30 * MINUTE);
  }

  @Test
  public void resumingAnotherApp_closesTheOneItReplaced() {
    // The single-foreground invariant also recovers from an app that died without pausing.
    Snapshot snapshot =
        foldFresh(
            Arrays.asList(
                resumed(DAY_START, YOUTUBE), resumed(DAY_START + 5 * MINUTE, CHROME)),
            DAY_START + 10 * MINUTE);

    assertThat(snapshot.totalForAt(YOUTUBE, DAY_START + 10 * MINUTE)).isEqualTo(5 * MINUTE);
    assertThat(snapshot.totalForAt(CHROME, DAY_START + 10 * MINUTE)).isEqualTo(5 * MINUTE);
    assertThat(snapshot.foregroundPackage()).isEqualTo(CHROME);
  }

  @Test
  public void lastActivityStopping_closesThePackage() {
    Snapshot snapshot =
        foldFresh(
            Arrays.asList(
                new Event(DAY_START, ScreenTimeCalculator.ACTIVITY_RESUMED, CHROME, "TabbedActivity"),
                new Event(
                    DAY_START + 2 * MINUTE,
                    ScreenTimeCalculator.ACTIVITY_PAUSED,
                    CHROME,
                    "TabbedActivity")),
            DAY_START + 60 * MINUTE);

    assertThat(snapshot.foregroundPackage()).isNull();
    assertThat(snapshot.totalForAt(CHROME, DAY_START + 60 * MINUTE)).isEqualTo(2 * MINUTE);
  }

  @Test
  public void carryOpenInto_keepsTheForegroundAppAcrossAWindowChange() {
    // At midnight the accounting window moves. The app on screen opened before the new window, so
    // rebuilding from the log alone would never see it and would silently stop counting.
    Snapshot before =
        foldFresh(Collections.singletonList(resumed(DAY_START, YOUTUBE)), DAY_START + 10 * MINUTE);
    long newWindowStart = DAY_START + 10 * MINUTE;

    Snapshot carried = before.carryOpenInto(newWindowStart);

    assertThat(carried.completedMillis).isEmpty();
    assertThat(carried.foregroundPackage()).isEqualTo(YOUTUBE);
    Snapshot after =
        ScreenTimeCalculator.fold(
            carried, Collections.emptyList(), newWindowStart, newWindowStart + 30 * MINUTE);
    assertThat(after.totalForAt(YOUTUBE, newWindowStart + 30 * MINUTE)).isEqualTo(30 * MINUTE);
  }

  private static List<Event> slice(List<Event> events, long from, long to) {
    List<Event> result = new ArrayList<>();
    for (Event event : events) {
      if (event.timestamp >= from && event.timestamp < to) {
        result.add(event);
      }
    }
    return result;
  }
}
