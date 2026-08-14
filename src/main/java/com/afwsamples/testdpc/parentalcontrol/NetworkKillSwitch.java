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
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Build.VERSION_CODES;
import android.os.UserManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import com.afwsamples.testdpc.DeviceAdminReceiver;
import com.afwsamples.testdpc.common.Util;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Takes the device off the network and keeps it off until the deadline passes or it is released
 * from inside TestDPC.
 *
 * <p>The radios themselves are switched off; nothing is tunnelled or filtered. Wi-Fi goes down
 * through {@link WifiManager#setWifiEnabled}, which the platform still honours for a device owner,
 * and mobile data through {@link TelephonyManager}. User restrictions then stop the child turning
 * either back on.
 *
 * <p>Once engaged there is no way back in over the network, by design: the phone has left it.
 * Releasing early means opening TestDPC on the device itself.
 *
 * <p>Coexistence with the rest of TestDPC matters here, because the "Set user restrictions" screen
 * manipulates the very same state. Only restrictions this class actually added are cleared on
 * release, so anything set by hand survives untouched.
 */
public class NetworkKillSwitch {

  private static final String TAG = "NetworkKillSwitch";

  /** Restrictions applied while the kill switch is engaged, each guarded by its API level. */
  private static final Restriction[] RESTRICTIONS = {
    new Restriction(UserManager.DISALLOW_CONFIG_WIFI, VERSION_CODES.LOLLIPOP),
    new Restriction(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS, VERSION_CODES.LOLLIPOP),
    new Restriction(UserManager.DISALLOW_CONFIG_TETHERING, VERSION_CODES.LOLLIPOP),
    new Restriction(UserManager.DISALLOW_NETWORK_RESET, VERSION_CODES.LOLLIPOP),
    new Restriction(UserManager.DISALLOW_AIRPLANE_MODE, VERSION_CODES.P),
    new Restriction(UserManager.DISALLOW_CHANGE_WIFI_STATE, VERSION_CODES.TIRAMISU),
    new Restriction(UserManager.DISALLOW_ADD_WIFI_CONFIG, VERSION_CODES.TIRAMISU),
    new Restriction(UserManager.DISALLOW_WIFI_TETHERING, VERSION_CODES.TIRAMISU),
  };

  private final Context mContext;
  private final SharedPreferences mPrefs;
  private final DevicePolicyManager mDpm;
  private final ComponentName mAdmin;

  public NetworkKillSwitch(Context context) {
    mContext = context.getApplicationContext();
    mPrefs = ParentalControlPrefs.get(mContext);
    mDpm = (DevicePolicyManager) mContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
    mAdmin = DeviceAdminReceiver.getComponentName(mContext);
  }

  public boolean isActive() {
    return mPrefs.getBoolean(ParentalControlPrefs.KEY_KS_ACTIVE, false);
  }

  /** Epoch millis when the block lifts by itself, or 0 for "until released by hand". */
  public long getBlockedUntil() {
    return mPrefs.getLong(ParentalControlPrefs.KEY_KS_UNTIL, 0L);
  }

  /** Whether the last attempt to switch mobile data off actually worked. */
  public boolean isMobileDataControllable() {
    return mPrefs.getBoolean(ParentalControlPrefs.KEY_KS_DATA_CONTROLLABLE, true);
  }

  /**
   * Engages the kill switch.
   *
   * <p>The deadline is written on the same tamper-resistant clock the expiry check reads, so
   * changing the device clock cannot cut a block short.
   *
   * @param durationMinutes how long to stay offline; zero or less means indefinitely.
   */
  public synchronized void engage(int durationMinutes) {
    long nowMillis = new ScreenTimeStore(mContext).clampNow(System.currentTimeMillis());
    long until = durationMinutes > 0 ? nowMillis + durationMinutes * 60_000L : 0L;
    mPrefs
        .edit()
        .putBoolean(ParentalControlPrefs.KEY_KS_ACTIVE, true)
        .putLong(ParentalControlPrefs.KEY_KS_UNTIL, until)
        .commit();
    Log.i(TAG, "Engaging kill switch, until=" + until);
    applyBlock();
  }

  /**
   * Re-applies the block. Safe to call repeatedly: it is how the service heals state that was
   * changed behind its back and how the block is restored after a reboot.
   */
  public synchronized void reassert() {
    if (!isActive()) {
      return;
    }
    applyBlock();
  }

  /** Lifts the block, restoring only the state this class changed. */
  public synchronized void release() {
    Log.i(TAG, "Releasing kill switch");
    // Restrictions first: DISALLOW_CHANGE_WIFI_STATE would otherwise bind the admin's own call to
    // put the radio back.
    clearOwnedRestrictions();
    restoreRadios();
    mPrefs
        .edit()
        .putBoolean(ParentalControlPrefs.KEY_KS_ACTIVE, false)
        .putLong(ParentalControlPrefs.KEY_KS_UNTIL, 0L)
        .commit();
  }

  /**
   * Releases the block if its deadline has passed.
   *
   * @return true if the block was lifted by this call.
   */
  public synchronized boolean releaseIfExpired(long nowMillis) {
    if (!isActive()) {
      return false;
    }
    long until = getBlockedUntil();
    if (until <= 0L || nowMillis < until) {
      return false;
    }
    release();
    return true;
  }

  // ------------------------------------------------------------------
  // Enforcement
  // ------------------------------------------------------------------

  private void applyBlock() {
    // Radios first: DISALLOW_CHANGE_WIFI_STATE can bind the admin's own WifiManager call, so they
    // have to go down before the restrictions go on.
    disableWifi();
    disableMobileData();
    addRestrictions();
  }

  private void disableWifi() {
    WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
    if (wifiManager == null) {
      return;
    }
    try {
      if (wifiManager.isWifiEnabled()) {
        // Remember it so releasing can put the device back on the network by itself.
        mPrefs.edit().putBoolean(ParentalControlPrefs.KEY_KS_PREV_WIFI_ENABLED, true).commit();
        if (!wifiManager.setWifiEnabled(false)) {
          Log.w(TAG, "setWifiEnabled(false) was refused");
        }
      }
    } catch (SecurityException e) {
      Log.w(TAG, "setWifiEnabled(false) threw", e);
    }
  }

  /**
   * Switches the cellular data radio off.
   *
   * <p>There is no {@code DevicePolicyManager} call for the data toggle, so this goes through
   * {@link TelephonyManager}, which ordinarily wants MODIFY_PHONE_STATE. Whether a device owner is
   * let through varies by build, so the outcome is recorded and reported rather than assumed: a
   * kill switch that quietly left mobile data up would be worse than one that admits it.
   */
  @SuppressWarnings("deprecation")
  private void disableMobileData() {
    TelephonyManager telephony =
        (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
    if (telephony == null || telephony.getSimState() == TelephonyManager.SIM_STATE_ABSENT) {
      // No SIM, so Wi-Fi going down already takes the device fully offline.
      setMobileDataControllable(true);
      return;
    }
    try {
      if (telephony.isDataEnabled()) {
        mPrefs.edit().putBoolean(ParentalControlPrefs.KEY_KS_PREV_DATA_ENABLED, true).commit();
      }
      telephony.setDataEnabled(false);
      setMobileDataControllable(!telephony.isDataEnabled());
    } catch (RuntimeException e) {
      Log.e(TAG, "Could not switch mobile data off", e);
      setMobileDataControllable(false);
    }
  }

  /**
   * Puts both radios back on.
   *
   * <p>Unconditional on purpose. "Off for five minutes" has to mean the phone is working again
   * after five minutes; merely lifting the restrictions would leave it sitting there with the
   * radios down and no way to reach it.
   */
  @SuppressWarnings("deprecation")
  private void restoreRadios() {
    TelephonyManager telephony =
        (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
    if (telephony != null && telephony.getSimState() != TelephonyManager.SIM_STATE_ABSENT) {
      try {
        telephony.setDataEnabled(true);
      } catch (RuntimeException e) {
        Log.w(TAG, "Could not switch mobile data back on", e);
      }
    }
    WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
    if (wifiManager != null) {
      try {
        if (!wifiManager.isWifiEnabled() && !wifiManager.setWifiEnabled(true)) {
          Log.w(TAG, "setWifiEnabled(true) was refused; Wi-Fi must be switched on by hand");
        }
      } catch (SecurityException e) {
        Log.w(TAG, "setWifiEnabled(true) threw; Wi-Fi must be switched on by hand", e);
      }
    }
    mPrefs
        .edit()
        .remove(ParentalControlPrefs.KEY_KS_PREV_WIFI_ENABLED)
        .remove(ParentalControlPrefs.KEY_KS_PREV_DATA_ENABLED)
        .commit();
  }

  private void setMobileDataControllable(boolean controllable) {
    mPrefs
        .edit()
        .putBoolean(ParentalControlPrefs.KEY_KS_DATA_CONTROLLABLE, controllable)
        .commit();
  }

  private void addRestrictions() {
    Set<String> owned = getOwnedRestrictions();
    for (Restriction restriction : RESTRICTIONS) {
      if (Util.SDK_INT < restriction.minSdkVersion) {
        continue;
      }
      try {
        if (!hasRestriction(restriction.key)) {
          // Remember only the ones we turn on ourselves, so releasing never clears a restriction
          // the parent had set by hand on the "Set user restrictions" screen.
          mDpm.addUserRestriction(mAdmin, restriction.key);
          owned.add(restriction.key);
        }
      } catch (SecurityException | IllegalArgumentException e) {
        Log.w(TAG, "Could not add " + restriction.key, e);
      }
    }
    setOwnedRestrictions(owned);
  }

  private void clearOwnedRestrictions() {
    for (String key : getOwnedRestrictions()) {
      try {
        mDpm.clearUserRestriction(mAdmin, key);
      } catch (SecurityException | IllegalArgumentException e) {
        Log.w(TAG, "Could not clear " + key, e);
      }
    }
    setOwnedRestrictions(Collections.emptySet());
  }

  private boolean hasRestriction(String key) {
    UserManager userManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
    return userManager != null && userManager.hasUserRestriction(key);
  }

  /** Restrictions currently owned by the kill switch, in a mutable copy. */
  public Set<String> getOwnedRestrictions() {
    return new HashSet<>(
        mPrefs.getStringSet(
            ParentalControlPrefs.KEY_KS_OWNED_RESTRICTIONS, Collections.emptySet()));
  }

  private void setOwnedRestrictions(Set<String> keys) {
    mPrefs
        .edit()
        .putStringSet(ParentalControlPrefs.KEY_KS_OWNED_RESTRICTIONS, new HashSet<>(keys))
        .commit();
  }

  /** Human readable list of what the kill switch is currently holding down. */
  public List<String> describeActiveEnforcement() {
    List<String> details = new ArrayList<>();
    if (!isActive()) {
      return details;
    }
    if (mPrefs.getBoolean(ParentalControlPrefs.KEY_KS_PREV_WIFI_ENABLED, false)) {
      details.add("Wi-Fi off");
    }
    if (mPrefs.getBoolean(ParentalControlPrefs.KEY_KS_PREV_DATA_ENABLED, false)) {
      details.add("Mobile data off");
    }
    details.addAll(getOwnedRestrictions());
    return details;
  }

  private static final class Restriction {
    final String key;
    final int minSdkVersion;

    Restriction(String key, int minSdkVersion) {
      this.key = key;
      this.minSdkVersion = minSdkVersion;
    }
  }
}
