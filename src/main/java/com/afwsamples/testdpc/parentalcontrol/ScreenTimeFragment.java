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

import android.app.AlertDialog;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.Toast;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreference;
import com.afwsamples.testdpc.R;
import com.afwsamples.testdpc.common.BaseSearchablePolicyPreferenceFragment;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Settings screen for the screen time limiter.
 *
 * <p>The preference hierarchy is built in code rather than inflated from XML because most rows show
 * live values, and because a code-built {@code DpcPreference} would need its constraint attributes
 * set programmatically anyway. Nothing here persists through the preference framework: every value
 * is read from and written to {@link ScreenTimeStore}, which keeps a single owner of the state.
 */
public class ScreenTimeFragment extends BaseSearchablePolicyPreferenceFragment {

  private static final String KEY_ENABLED = "screen_time_enabled";
  private static final String KEY_USAGE_ACCESS = "screen_time_usage_access";
  private static final String KEY_MONITORED_APPS = "screen_time_monitored_apps";
  private static final String KEY_PER_APP_LIMIT = "screen_time_per_app_limit";
  private static final String KEY_COMBINED_LIMIT = "screen_time_combined_limit";
  private static final String KEY_WINDOW_ENABLED = "screen_time_window_enabled";
  private static final String KEY_WINDOW_START = "screen_time_window_start";
  private static final String KEY_WINDOW_END = "screen_time_window_end";
  private static final String KEY_TAMPER = "screen_time_tamper_protection";
  private static final String KEY_TODAY = "screen_time_today";
  private static final String KEY_RESET = "screen_time_reset";

  private ScreenTimeStore mStore;
  private ScreenTimeEnforcer mEnforcer;
  private TamperProtection mTamperProtection;
  private ScreenTimeAccountant mAccountant;
  private PackageManager mPackageManager;

  private SwitchPreference mEnabled;
  private Preference mUsageAccess;
  private Preference mMonitoredApps;
  private Preference mPerAppLimit;
  private Preference mCombinedLimit;
  private SwitchPreference mWindowEnabled;
  private Preference mWindowStart;
  private Preference mWindowEnd;
  private SwitchPreference mTamper;
  private Preference mToday;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    mStore = new ScreenTimeStore(getActivity());
    mEnforcer = new ScreenTimeEnforcer(getActivity(), mStore);
    mTamperProtection = new TamperProtection(getActivity(), mStore);
    mAccountant =
        new ScreenTimeAccountant(mStore, new UsageStatsSampler(getActivity()));
    mPackageManager = getActivity().getPackageManager();
    super.onCreate(savedInstanceState);
  }

  @Override
  public boolean isAvailable(Context context) {
    return ScreenTimeEnforcer.isSupported();
  }

  @Override
  public void onCreatePreferences(Bundle bundle, String rootKey) {
    PreferenceScreen screen =
        getPreferenceManager().createPreferenceScreen(getPreferenceManager().getContext());
    setPreferenceScreen(screen);
    Context context = getPreferenceManager().getContext();

    mEnabled = newSwitch(context, KEY_ENABLED, R.string.screen_time_enabled);
    mEnabled.setSummary(R.string.screen_time_enabled_summary);
    mEnabled.setOnPreferenceChangeListener(
        (preference, value) -> {
          boolean enabled = Boolean.TRUE.equals(value);
          mStore.setEnabled(enabled);
          if (!enabled) {
            // Turning the feature off must not leave apps stranded in a suspended state.
            mEnforcer.releaseAll();
          }
          ParentalControlService.refresh(getActivity());
          refreshUi();
          return true;
        });
    screen.addPreference(mEnabled);

    mUsageAccess = new Preference(context);
    mUsageAccess.setKey(KEY_USAGE_ACCESS);
    mUsageAccess.setPersistent(false);
    mUsageAccess.setTitle(R.string.screen_time_usage_access);
    mUsageAccess.setOnPreferenceClickListener(
        preference -> {
          try {
            startActivity(UsageStatsSampler.usageAccessSettingsIntent());
          } catch (RuntimeException e) {
            Toast.makeText(
                    getActivity(), R.string.screen_time_usage_access_no_settings, Toast.LENGTH_LONG)
                .show();
          }
          return true;
        });
    screen.addPreference(mUsageAccess);

    PreferenceCategory limits = new PreferenceCategory(context);
    limits.setTitle(R.string.screen_time_limits_category);
    screen.addPreference(limits);

    mMonitoredApps = new Preference(context);
    mMonitoredApps.setKey(KEY_MONITORED_APPS);
    mMonitoredApps.setPersistent(false);
    mMonitoredApps.setTitle(R.string.screen_time_monitored_apps);
    mMonitoredApps.setOnPreferenceClickListener(
        preference -> {
          showFragment(new MonitoredAppsFragment());
          return true;
        });
    limits.addPreference(mMonitoredApps);

    mPerAppLimit =
        newMinutesPreference(
            context,
            KEY_PER_APP_LIMIT,
            R.string.screen_time_per_app_limit,
            () -> mStore.getPerAppLimitMinutes(),
            minutes -> mStore.setPerAppLimitMinutes(minutes));
    limits.addPreference(mPerAppLimit);

    mCombinedLimit =
        newMinutesPreference(
            context,
            KEY_COMBINED_LIMIT,
            R.string.screen_time_combined_limit,
            () -> mStore.getCombinedLimitMinutes(),
            minutes -> mStore.setCombinedLimitMinutes(minutes));
    limits.addPreference(mCombinedLimit);

    PreferenceCategory window = new PreferenceCategory(context);
    window.setTitle(R.string.screen_time_window_category);
    screen.addPreference(window);

    mWindowEnabled = newSwitch(context, KEY_WINDOW_ENABLED, R.string.screen_time_window_enabled);
    mWindowEnabled.setOnPreferenceChangeListener(
        (preference, value) -> {
          mStore.setWindowEnabled(Boolean.TRUE.equals(value));
          ParentalControlService.refresh(getActivity());
          refreshUi();
          return true;
        });
    window.addPreference(mWindowEnabled);

    mWindowStart = newTimePreference(context, KEY_WINDOW_START, R.string.screen_time_window_start);
    window.addPreference(mWindowStart);
    mWindowEnd = newTimePreference(context, KEY_WINDOW_END, R.string.screen_time_window_end);
    window.addPreference(mWindowEnd);

    PreferenceCategory status = new PreferenceCategory(context);
    status.setTitle(R.string.screen_time_status_category);
    screen.addPreference(status);

    mToday = new Preference(context);
    mToday.setKey(KEY_TODAY);
    mToday.setPersistent(false);
    mToday.setSelectable(false);
    mToday.setTitle(R.string.screen_time_today);
    status.addPreference(mToday);

    Preference reset = new Preference(context);
    reset.setKey(KEY_RESET);
    reset.setPersistent(false);
    reset.setTitle(R.string.screen_time_reset);
    reset.setSummary(R.string.screen_time_reset_summary);
    reset.setOnPreferenceClickListener(preference -> {
      confirmReset();
      return true;
    });
    status.addPreference(reset);

    mTamper = newSwitch(context, KEY_TAMPER, R.string.screen_time_tamper);
    mTamper.setSummary(R.string.screen_time_tamper_summary);
    mTamper.setOnPreferenceChangeListener(
        (preference, value) -> {
          mTamperProtection.setEnabled(Boolean.TRUE.equals(value));
          refreshUi();
          return true;
        });
    status.addPreference(mTamper);

    refreshUi();
  }

  @Override
  public void onResume() {
    super.onResume();
    // Set here rather than in onCreate so returning from the app picker restores the title.
    getActivity().getActionBar().setTitle(R.string.screen_time_title);
    refreshUi();
  }

  // ------------------------------------------------------------------
  // UI state
  // ------------------------------------------------------------------

  private void refreshUi() {
    if (mEnabled == null) {
      return;
    }
    mEnabled.setChecked(mStore.isEnabled());

    boolean hasUsageAccess = UsageStatsSampler.hasUsageAccess(getActivity());
    mUsageAccess.setSummary(
        hasUsageAccess
            ? getString(R.string.screen_time_usage_access_granted)
            : getString(R.string.screen_time_usage_access_missing));

    Set<String> monitored = mStore.getMonitoredPackages();
    mMonitoredApps.setSummary(
        monitored.isEmpty()
            ? getString(R.string.screen_time_monitored_apps_none)
            : getString(
                R.string.screen_time_monitored_apps_summary,
                monitored.size(),
                describePackages(monitored)));

    mPerAppLimit.setSummary(describeLimit(mStore.getPerAppLimitMinutes()));
    mCombinedLimit.setSummary(describeLimit(mStore.getCombinedLimitMinutes()));

    mWindowEnabled.setChecked(mStore.isWindowEnabled());
    mWindowStart.setSummary(
        ParentalControlService.formatMinuteOfDay(mStore.getWindowStartMinute()));
    mWindowEnd.setSummary(ParentalControlService.formatMinuteOfDay(mStore.getWindowEndMinute()));
    mWindowStart.setEnabled(mStore.isWindowEnabled());
    mWindowEnd.setEnabled(mStore.isWindowEnabled());

    mTamper.setChecked(mStore.isTamperProtectionEnabled());
    mToday.setSummary(buildTodaySummary(hasUsageAccess));
  }

  private CharSequence buildTodaySummary(boolean hasUsageAccess) {
    if (!hasUsageAccess) {
      return getString(R.string.screen_time_today_no_access);
    }
    Set<String> monitored = new TreeSet<>(mStore.getMonitoredPackages());
    if (monitored.isEmpty()) {
      return getString(R.string.screen_time_monitored_apps_none);
    }
    long now = mStore.clampNow(System.currentTimeMillis());
    // Read-only: only the service owns the persisted running totals.
    Map<String, Long> totals =
        mAccountant.computeWithoutPersisting(mStore.getWindowStart(now), now);

    StringBuilder builder = new StringBuilder();
    long combined = 0L;
    for (String packageName : monitored) {
      Long millis = totals.get(packageName);
      long value = millis == null ? 0L : millis;
      combined += value;
      int perApp = mStore.getPerAppLimitMinutes();
      builder.append(labelOf(packageName)).append(": ").append(formatDuration(value));
      if (perApp > ScreenTimeStore.NO_LIMIT) {
        builder.append(" / ").append(formatDuration(perApp * 60_000L));
      }
      ScreenTimeEnforcer.BlockReason reason = mEnforcer.evaluate(packageName, totals, now);
      if (reason != ScreenTimeEnforcer.BlockReason.NOT_BLOCKED) {
        builder.append(" — ").append(describeReason(reason));
      }
      builder.append('\n');
    }
    builder
        .append(getString(R.string.screen_time_today_combined, formatDuration(combined)));
    if (mStore.getResetBaseline() > ScreenTimeStore.localMidnight(now)) {
      builder
          .append('\n')
          .append(
              getString(
                  R.string.screen_time_today_since,
                  ParentalControlService.formatMinuteOfDay(
                      ScreenTimeStore.minuteOfDay(mStore.getResetBaseline()))));
    }
    return builder.toString();
  }

  private String describeReason(ScreenTimeEnforcer.BlockReason reason) {
    switch (reason) {
      case BLOCKED_WINDOW:
        return getString(R.string.screen_time_reason_window);
      case PER_APP_LIMIT:
        return getString(R.string.screen_time_reason_per_app);
      case COMBINED_LIMIT:
        return getString(R.string.screen_time_reason_combined);
      default:
        return "";
    }
  }

  private String describePackages(Set<String> packages) {
    List<String> labels = new ArrayList<>();
    for (String packageName : new TreeSet<>(packages)) {
      labels.add(labelOf(packageName));
      if (labels.size() == 3) {
        break;
      }
    }
    String joined = TextUtils.join(", ", labels);
    return packages.size() > labels.size() ? joined + "…" : joined;
  }

  private String labelOf(String packageName) {
    try {
      return mPackageManager
          .getApplicationLabel(mPackageManager.getApplicationInfo(packageName, 0))
          .toString();
    } catch (PackageManager.NameNotFoundException | RuntimeException e) {
      return packageName;
    }
  }

  private String describeLimit(int minutes) {
    return minutes <= ScreenTimeStore.NO_LIMIT
        ? getString(R.string.screen_time_no_limit)
        : formatDuration(minutes * 60_000L);
  }

  static String formatDuration(long millis) {
    long totalSeconds = millis / 1000L;
    long hours = totalSeconds / 3600;
    long minutes = (totalSeconds % 3600) / 60;
    long seconds = totalSeconds % 60;
    if (hours > 0) {
      return String.format(Locale.getDefault(), "%dh %02dm", hours, minutes);
    }
    // Below an hour the seconds are shown, so a per-app row and the combined row visibly agree
    // instead of each losing up to a minute to truncation.
    return String.format(Locale.getDefault(), "%dm %02ds", minutes, seconds);
  }

  // ------------------------------------------------------------------
  // Builders and dialogs
  // ------------------------------------------------------------------

  private static SwitchPreference newSwitch(Context context, String key, int titleResId) {
    SwitchPreference preference = new SwitchPreference(context);
    preference.setKey(key);
    preference.setPersistent(false);
    preference.setTitle(titleResId);
    return preference;
  }

  private interface MinutesSetter {
    void set(int minutes);
  }

  private interface MinutesGetter {
    int get();
  }

  /**
   * A row that asks for a number of minutes.
   *
   * <p>Built on a plain dialog rather than an {@code EditTextPreference} because
   * {@code setOnBindEditTextListener} is only honoured by {@code PreferenceFragmentCompat}; under
   * the framework {@code PreferenceFragment} this repo uses it never fires, so the field would come
   * up with a full text keyboard and happily accept letters.
   */
  private Preference newMinutesPreference(
      Context context, String key, int titleResId, MinutesGetter getter, MinutesSetter setter) {
    Preference preference = new Preference(context);
    preference.setKey(key);
    preference.setPersistent(false);
    preference.setTitle(titleResId);
    preference.setOnPreferenceClickListener(
        unused -> {
          EditText input = new EditText(getActivity());
          input.setInputType(InputType.TYPE_CLASS_NUMBER);
          int current = getter.get();
          if (current > ScreenTimeStore.NO_LIMIT) {
            input.setText(String.valueOf(current));
          }
          new AlertDialog.Builder(getActivity())
              .setTitle(titleResId)
              .setMessage(R.string.screen_time_minutes_hint)
              .setView(input)
              .setPositiveButton(
                  android.R.string.ok,
                  (dialog, which) -> {
                    int minutes = parseMinutes(input.getText().toString());
                    if (minutes < 0) {
                      Toast.makeText(
                              getActivity(),
                              R.string.screen_time_invalid_minutes,
                              Toast.LENGTH_SHORT)
                          .show();
                      return;
                    }
                    setter.set(minutes);
                    ParentalControlService.refresh(getActivity());
                    refreshUi();
                  })
              .setNegativeButton(android.R.string.cancel, null)
              .show();
          return true;
        });
    return preference;
  }

  /** @return the parsed minutes, 0 for "no limit", or -1 when the text is not a valid number. */
  private static int parseMinutes(String text) {
    if (TextUtils.isEmpty(text) || TextUtils.isEmpty(text.trim())) {
      return 0;
    }
    try {
      int minutes = Integer.parseInt(text.trim());
      return minutes < 0 ? -1 : minutes;
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private Preference newTimePreference(Context context, String key, int titleResId) {
    Preference preference = new Preference(context);
    preference.setKey(key);
    preference.setPersistent(false);
    preference.setTitle(titleResId);
    preference.setOnPreferenceClickListener(
        unused -> {
          boolean isStart = KEY_WINDOW_START.equals(key);
          int current = isStart ? mStore.getWindowStartMinute() : mStore.getWindowEndMinute();
          new TimePickerDialog(
                  getActivity(),
                  (view, hourOfDay, minute) -> {
                    int value = hourOfDay * 60 + minute;
                    if (isStart) {
                      mStore.setWindow(value, mStore.getWindowEndMinute());
                    } else {
                      mStore.setWindow(mStore.getWindowStartMinute(), value);
                    }
                    ParentalControlService.refresh(getActivity());
                    refreshUi();
                  },
                  current / 60,
                  current % 60,
                  true)
              .show();
          return true;
        });
    return preference;
  }

  private void confirmReset() {
    new AlertDialog.Builder(getActivity())
        .setTitle(R.string.screen_time_reset)
        .setMessage(R.string.screen_time_reset_confirm)
        .setPositiveButton(
            android.R.string.ok,
            (dialog, which) -> {
              long now = mStore.clampNow(System.currentTimeMillis());
              mStore.resetUsage(now);
              mAccountant.invalidate();
              mEnforcer.apply(
                  mAccountant.computeWithoutPersisting(mStore.getWindowStart(now), now),
                  now,
                  /* verifyAgainstSystem= */ true);
              ParentalControlService.refresh(getActivity());
              refreshUi();
              Toast.makeText(getActivity(), R.string.screen_time_reset_done, Toast.LENGTH_SHORT)
                  .show();
            })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  private void showFragment(Fragment fragment) {
    FragmentManager fragmentManager = getFragmentManager();
    fragmentManager
        .beginTransaction()
        .addToBackStack(ScreenTimeFragment.class.getName())
        .replace(R.id.container, fragment)
        .commit();
  }
}
