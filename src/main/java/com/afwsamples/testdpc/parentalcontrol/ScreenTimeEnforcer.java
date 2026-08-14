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
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Build.VERSION_CODES;
import android.text.TextUtils;
import android.util.Log;
import com.afwsamples.testdpc.DevicePolicyManagerGateway;
import com.afwsamples.testdpc.DevicePolicyManagerGatewayImpl;
import com.afwsamples.testdpc.common.Util;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Decides which monitored apps must be blocked right now and applies that with {@link
 * android.app.admin.DevicePolicyManager#setPackagesSuspended}.
 *
 * <p>Suspension beats {@code setApplicationHidden} here because the platform already does exactly
 * what is wanted: launching a suspended app raises a "Can't open this app" dialog, and suspending
 * one that is in the foreground drops the user straight back to the launcher. Hiding would make
 * the icon disappear, which reads as "the app was uninstalled".
 *
 * <p>Only monitored packages are ever touched, and the set this class suspended is persisted, so
 * suspensions made by hand through TestDPC's own "Suspend apps" screen survive untouched.
 */
public class ScreenTimeEnforcer {

  private static final String TAG = "ScreenTimeEnforcer";

  /** Why a monitored app is currently blocked. */
  public enum BlockReason {
    NOT_BLOCKED,
    BLOCKED_WINDOW,
    PER_APP_LIMIT,
    COMBINED_LIMIT
  }

  private final Context mContext;
  private final ScreenTimeStore mStore;
  private final DevicePolicyManagerGateway mGateway;

  public ScreenTimeEnforcer(Context context, ScreenTimeStore store) {
    mContext = context.getApplicationContext();
    mStore = store;
    mGateway = new DevicePolicyManagerGatewayImpl(mContext);
  }

  public static boolean isSupported() {
    return Util.SDK_INT >= VERSION_CODES.N;
  }

  /** Evaluates the policy for one package without touching any system state. */
  public BlockReason evaluate(String packageName, Map<String, Long> totals, long nowMillis) {
    Set<String> monitored = mStore.getMonitoredPackages();
    // Guard here as well as in apply(), or a disabled feature would still report apps as blocked
    // on the settings screen and in the status the control page shows.
    if (!mStore.isEnabled() || !monitored.contains(packageName)) {
      return BlockReason.NOT_BLOCKED;
    }
    if (mStore.isWithinBlockedWindow(nowMillis)) {
      return BlockReason.BLOCKED_WINDOW;
    }
    if (totals == null) {
      return BlockReason.NOT_BLOCKED;
    }
    int perApp = mStore.getPerAppLimitMinutes();
    if (perApp > ScreenTimeStore.NO_LIMIT
        && ScreenTimeAccountant.totalFor(totals, packageName) >= minutesToMillis(perApp)) {
      return BlockReason.PER_APP_LIMIT;
    }
    int combined = mStore.getCombinedLimitMinutes();
    if (combined > ScreenTimeStore.NO_LIMIT
        && ScreenTimeAccountant.sumOf(totals, monitored) >= minutesToMillis(combined)) {
      return BlockReason.COMBINED_LIMIT;
    }
    return BlockReason.NOT_BLOCKED;
  }

  /**
   * Brings the suspension state of every monitored app in line with the policy.
   *
   * @param verifyAgainstSystem when true the real suspension state is read back from the platform,
   *     so a suspension lifted behind this class's back is restored.
   */
  public void apply(Map<String, Long> totals, long nowMillis, boolean verifyAgainstSystem) {
    if (!isSupported()) {
      return;
    }
    Set<String> ownedSuspensions = mStore.getSuspendedByUs();

    Set<String> desired = new HashSet<>();
    if (mStore.isEnabled()) {
      for (String packageName : mStore.getMonitoredPackages()) {
        if (evaluate(packageName, totals, nowMillis) != BlockReason.NOT_BLOCKED) {
          desired.add(packageName);
        }
      }
    }
    // Never lock the parent out of the controlling app itself.
    desired.remove(mContext.getPackageName());

    Set<String> toUnsuspend = new HashSet<>(ownedSuspensions);
    toUnsuspend.removeAll(desired);
    if (!toUnsuspend.isEmpty()) {
      setSuspended(toUnsuspend, false);
    }

    Set<String> toSuspend = new HashSet<>(desired);
    if (verifyAgainstSystem) {
      Set<String> alreadySuspended = new HashSet<>();
      for (String packageName : toSuspend) {
        if (isSuspended(packageName)) {
          alreadySuspended.add(packageName);
        }
      }
      toSuspend.removeAll(alreadySuspended);
    } else {
      toSuspend.removeAll(ownedSuspensions);
    }

    Set<String> newOwned = new TreeSet<>(desired);
    if (!toSuspend.isEmpty()) {
      newOwned.removeAll(setSuspended(toSuspend, true));
    }

    if (!newOwned.equals(ownedSuspensions)) {
      mStore.setSuspendedByUs(newOwned);
    }
  }

  /** Lifts every suspension this feature is responsible for. */
  public void releaseAll() {
    if (!isSupported()) {
      return;
    }
    Set<String> owned = mStore.getSuspendedByUs();
    if (!owned.isEmpty()) {
      setSuspended(owned, false);
    }
    mStore.setSuspendedByUs(Collections.emptySet());
  }

  private boolean isSuspended(String packageName) {
    try {
      return mGateway.isPackageSuspended(packageName);
    } catch (NameNotFoundException | RuntimeException e) {
      Log.w(TAG, "isPackageSuspended(" + packageName + ") failed", e);
      return false;
    }
  }

  /**
   * @return packages the platform refused to touch. {@code setPackagesSuspended} reports these in
   *     its return value rather than by throwing, for example for a device admin or a system
   *     component that may not be suspended.
   */
  private Set<String> setSuspended(Set<String> packages, boolean suspended) {
    String[] array = packages.toArray(new String[0]);
    Set<String> rejected = new HashSet<>();
    mGateway.setPackagesSuspended(
        array,
        suspended,
        failed -> {
          if (failed != null && failed.length > 0) {
            Log.w(
                TAG, "Could not set suspended=" + suspended + " on " + TextUtils.join(",", failed));
            Collections.addAll(rejected, failed);
          }
        },
        e -> {
          Log.e(TAG, "setPackagesSuspended failed", e);
          rejected.addAll(packages);
        });
    return rejected;
  }

  private static long minutesToMillis(int minutes) {
    return minutes * 60_000L;
  }
}
