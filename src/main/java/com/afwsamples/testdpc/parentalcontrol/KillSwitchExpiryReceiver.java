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

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build.VERSION_CODES;
import android.util.Log;
import com.afwsamples.testdpc.common.Util;

/**
 * Lifts the network block at its deadline even if the device is dozing.
 *
 * <p>The service's own loop would eventually notice too, but only once the device wakes; a
 * ten-minute block should not silently become an hour because the phone was left face down.
 */
public class KillSwitchExpiryReceiver extends BroadcastReceiver {

  private static final String TAG = "KillSwitchExpiry";
  private static final int REQUEST_CODE = 4711;

  public static void schedule(Context context, long triggerAtMillis) {
    AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    if (alarmManager == null) {
      return;
    }
    if (triggerAtMillis <= 0L) {
      cancel(context);
      return;
    }
    PendingIntent pendingIntent = pendingIntent(context);
    try {
      if (Util.SDK_INT >= VERSION_CODES.M) {
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
      } else {
        alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
      }
    } catch (SecurityException e) {
      // Exact alarms can be withheld from an app; an inexact one still fires during doze and is
      // good enough, since the service loop backs it up anyway.
      Log.w(TAG, "Falling back to an inexact alarm", e);
      if (Util.SDK_INT >= VERSION_CODES.M) {
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
      } else {
        alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
      }
    }
  }

  public static void cancel(Context context) {
    AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    if (alarmManager != null) {
      alarmManager.cancel(pendingIntent(context));
    }
  }

  private static PendingIntent pendingIntent(Context context) {
    Intent intent = new Intent(context, KillSwitchExpiryReceiver.class);
    int flags =
        Util.SDK_INT >= VERSION_CODES.M
            ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            : PendingIntent.FLAG_UPDATE_CURRENT;
    return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags);
  }

  @Override
  public void onReceive(Context context, Intent intent) {
    NetworkKillSwitch killSwitch = new NetworkKillSwitch(context);
    if (killSwitch.releaseIfExpired(System.currentTimeMillis())) {
      Log.i(TAG, "Network block expired and was lifted");
    }
    ParentalControlService.refresh(context);
  }
}
