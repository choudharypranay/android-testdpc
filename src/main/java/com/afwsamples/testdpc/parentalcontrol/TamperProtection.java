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

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build.VERSION_CODES;
import android.os.UserManager;
import android.util.Log;
import com.afwsamples.testdpc.DeviceAdminReceiver;
import com.afwsamples.testdpc.DevicePolicyManagerGateway;
import com.afwsamples.testdpc.DevicePolicyManagerGatewayImpl;
import com.afwsamples.testdpc.common.Util;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Closes the obvious routes around the screen time limiter.
 *
 * <p>Suspension itself is enforced by the framework, so killing TestDPC does not unblock anything.
 * What killing it <em>would</em> achieve is stopping the sampler, letting an app already running
 * over-run its cap until the service restarts, and the clock is a more direct attack still: wind
 * the date forward and the daily allowance starts again. This turns off both routes.
 *
 * <p>Every restriction it adds is recorded so that switching the protection off again clears only
 * what it turned on, leaving anything the parent set by hand on the "Set user restrictions" screen
 * exactly as it was.
 */
public class TamperProtection {

  private static final String TAG = "TamperProtection";

  private static final String[] RESTRICTIONS = {
    UserManager.DISALLOW_CONFIG_DATE_TIME, UserManager.DISALLOW_SAFE_BOOT,
  };

  private final Context mContext;
  private final ScreenTimeStore mStore;
  private final DevicePolicyManager mDpm;
  private final DevicePolicyManagerGateway mGateway;
  private final ComponentName mAdmin;

  public TamperProtection(Context context, ScreenTimeStore store) {
    mContext = context.getApplicationContext();
    mStore = store;
    mDpm = (DevicePolicyManager) mContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
    mGateway = new DevicePolicyManagerGatewayImpl(mContext);
    mAdmin = DeviceAdminReceiver.getComponentName(mContext);
  }

  public boolean isEnabled() {
    return mStore.isTamperProtectionEnabled();
  }

  public void setEnabled(boolean enabled) {
    if (enabled) {
      apply();
    } else {
      revert();
    }
    mStore.setTamperProtectionEnabled(enabled);
  }

  /** Re-applies the protection, for example after a reboot. */
  public void reassert() {
    if (isEnabled()) {
      apply();
    }
  }

  private void apply() {
    pinClockToNetworkTime();
    addRestrictions();
    setSelfUninstallBlocked(true);
    setSelfUserControlDisabled(true);
  }

  private void revert() {
    clearOwnedRestrictions();
    setSelfUninstallBlocked(false);
    setSelfUserControlDisabled(false);
  }

  /** Without this, moving the date forward hands out a brand new daily allowance. */
  private void pinClockToNetworkTime() {
    if (Util.SDK_INT < VERSION_CODES.R) {
      return;
    }
    try {
      mDpm.setAutoTimeEnabled(mAdmin, true);
      mDpm.setAutoTimeZoneEnabled(mAdmin, true);
    } catch (RuntimeException e) {
      Log.w(TAG, "Could not pin the clock to network time", e);
    }
  }

  private void addRestrictions() {
    UserManager userManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
    Set<String> owned = mStore.getTamperOwnedRestrictions();
    for (String key : RESTRICTIONS) {
      try {
        if (userManager != null && userManager.hasUserRestriction(key)) {
          continue;
        }
        mDpm.addUserRestriction(mAdmin, key);
        owned.add(key);
      } catch (SecurityException | IllegalArgumentException e) {
        Log.w(TAG, "Could not add " + key, e);
      }
    }
    mStore.setTamperOwnedRestrictions(owned);
  }

  private void clearOwnedRestrictions() {
    for (String key : mStore.getTamperOwnedRestrictions()) {
      try {
        mDpm.clearUserRestriction(mAdmin, key);
      } catch (SecurityException | IllegalArgumentException e) {
        Log.w(TAG, "Could not clear " + key, e);
      }
    }
    mStore.setTamperOwnedRestrictions(new HashSet<>());
  }

  private void setSelfUninstallBlocked(boolean blocked) {
    mGateway.setUninstallBlocked(
        mContext.getPackageName(),
        blocked,
        unused -> Log.d(TAG, "setUninstallBlocked(" + blocked + ") succeeded"),
        e -> Log.w(TAG, "setUninstallBlocked(" + blocked + ") failed", e));
  }

  /** Removes the Force stop button, which otherwise silences the usage sampler. */
  private void setSelfUserControlDisabled(boolean disabled) {
    if (Util.SDK_INT < VERSION_CODES.R) {
      return;
    }
    String self = mContext.getPackageName();
    List<String> packages;
    try {
      List<String> current = mGateway.getUserControlDisabledPackages();
      packages = current == null ? new ArrayList<>() : new ArrayList<>(current);
    } catch (RuntimeException e) {
      Log.w(TAG, "Could not read the user-control-disabled list", e);
      packages = new ArrayList<>();
    }
    // The setter replaces the whole list, so merge rather than overwrite.
    if (disabled) {
      if (!packages.contains(self)) {
        packages.add(self);
      }
    } else {
      packages.remove(self);
    }
    mGateway.setUserControlDisabledPackages(
        packages,
        unused -> Log.d(TAG, "setUserControlDisabledPackages succeeded"),
        e -> Log.w(TAG, "setUserControlDisabledPackages failed", e));
  }
}
