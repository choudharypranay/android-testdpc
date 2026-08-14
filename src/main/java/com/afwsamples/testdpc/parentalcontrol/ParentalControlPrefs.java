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

/**
 * Single access point to the persistent state shared by the parental control features.
 *
 * <p>All state lives in one {@link SharedPreferences} file so that a reboot, a force stop or a
 * crash never loses the accumulated screen time or the state of the network kill switch.
 */
public final class ParentalControlPrefs {

  private static final String PREFS_NAME = "parental_control";

  // ---- Screen time configuration ----
  public static final String KEY_ST_ENABLED = "st_enabled";
  public static final String KEY_ST_MONITORED = "st_monitored";
  public static final String KEY_ST_PER_APP_MINUTES = "st_per_app_minutes";
  public static final String KEY_ST_COMBINED_MINUTES = "st_combined_minutes";
  public static final String KEY_ST_WINDOW_ENABLED = "st_window_enabled";
  public static final String KEY_ST_WINDOW_START = "st_window_start";
  public static final String KEY_ST_WINDOW_END = "st_window_end";

  // ---- Screen time accounting ----
  /**
   * Instant of the last manual reset. Usage is derived from the system event log, so a reset moves
   * the start of the accounting window forward instead of clearing a counter.
   */
  public static final String KEY_ST_RESET_BASELINE = "st_reset_baseline";
  /** Highest wall clock value ever observed, used to ignore backwards clock jumps. */
  public static final String KEY_ST_MAX_WALL_CLOCK = "st_max_wall_clock";
  /** Last accepted wall clock reading, paired with the monotonic clock below. */
  public static final String KEY_ST_CLOCK_WALL = "st_clock_wall";
  /** SystemClock.elapsedRealtime() when that reading was accepted; no setting can move it. */
  public static final String KEY_ST_CLOCK_ELAPSED = "st_clock_elapsed";
  /**
   * Running per-package totals, persisted as a floor. The platform drops usage events that were
   * still buffered when the device rebooted, so the event log alone would hand back the lost time.
   */
  public static final String PREFIX_ST_COMMITTED = "st_committed_";
  /**
   * The accounting window the committed totals belong to. Deliberately not prefixed with {@link
   * #PREFIX_ST_COMMITTED}, or scanning that prefix would read this timestamp back as if it were a
   * package's usage.
   */
  public static final String KEY_ST_COMMIT_WINDOW = "st_commit_window";
  /** Packages this feature suspended itself, so a reset never touches unrelated suspensions. */
  public static final String KEY_ST_SUSPENDED_BY_US = "st_suspended_by_us";
  public static final String KEY_ST_TAMPER_PROTECTION = "st_tamper_protection";
  public static final String KEY_ST_TAMPER_OWNED_RESTRICTIONS = "st_tamper_owned_restrictions";

  // ---- Network kill switch ----
  public static final String KEY_KS_ACTIVE = "ks_active";
  /** Epoch millis at which the block lifts automatically; 0 means "until manually reset". */
  public static final String KEY_KS_UNTIL = "ks_until";
  /** User restrictions this feature added itself and may therefore clear again. */
  public static final String KEY_KS_OWNED_RESTRICTIONS = "ks_owned_restrictions";
  /** Whether this feature was the one that switched Wi-Fi off, for the status text. */
  public static final String KEY_KS_PREV_WIFI_ENABLED = "ks_prev_wifi_enabled";
  /** Whether this feature was the one that switched mobile data off. */
  public static final String KEY_KS_PREV_DATA_ENABLED = "ks_prev_data_enabled";
  /**
   * Whether switching mobile data off actually worked. There is no device owner API for the data
   * toggle, so the result is recorded instead of assumed and shown on the settings screen.
   */
  public static final String KEY_KS_DATA_CONTROLLABLE = "ks_data_controllable";

  // ---- Remote control HTTP server ----
  public static final String KEY_RC_ENABLED = "rc_enabled";
  public static final String KEY_RC_PORT = "rc_port";
  public static final String KEY_RC_TOKEN = "rc_token";
  /**
   * Whether the control server demands a token. Off by default: the interface is meant to be
   * opened in a phone browser on the home network, where a secret that has to be typed in by hand
   * is worse than no secret at all.
   */
  public static final String KEY_RC_REQUIRE_TOKEN = "rc_require_token";

  public static final int DEFAULT_RC_PORT = 8080;

  private ParentalControlPrefs() {}

  public static SharedPreferences get(Context context) {
    return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
  }
}
