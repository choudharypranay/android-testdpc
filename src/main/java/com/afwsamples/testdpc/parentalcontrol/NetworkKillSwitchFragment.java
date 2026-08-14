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
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.widget.EditText;
import android.widget.Toast;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreference;
import com.afwsamples.testdpc.R;
import com.afwsamples.testdpc.common.BaseSearchablePolicyPreferenceFragment;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/** Settings screen for the remotely triggered network kill switch. */
public class NetworkKillSwitchFragment extends BaseSearchablePolicyPreferenceFragment {

  private static final String KEY_SERVER_ENABLED = "kill_switch_server_enabled";
  private static final String KEY_SERVER_PORT = "kill_switch_server_port";
  private static final String KEY_SERVER_ADDRESS = "kill_switch_server_address";
  private static final String KEY_TOKEN = "kill_switch_token";
  private static final String KEY_REQUIRE_TOKEN = "kill_switch_require_token";
  private static final String KEY_STATUS = "kill_switch_status";
  private static final String KEY_BLOCK_NOW = "kill_switch_block_now";
  private static final String KEY_RESTORE = "kill_switch_restore";

  private ScreenTimeStore mStore;
  private NetworkKillSwitch mKillSwitch;
  private SharedPreferences mPrefs;

  private SwitchPreference mServerEnabled;
  private Preference mServerPort;
  private Preference mServerAddress;
  private SwitchPreference mRequireToken;
  private Preference mToken;
  private Preference mStatus;
  private Preference mRestore;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    mStore = new ScreenTimeStore(getActivity());
    mKillSwitch = new NetworkKillSwitch(getActivity());
    mPrefs = ParentalControlPrefs.get(getActivity());
    super.onCreate(savedInstanceState);
    getActivity().getActionBar().setTitle(R.string.network_kill_switch_title);
  }

  @Override
  public boolean isAvailable(Context context) {
    return true;
  }

  @Override
  public void onCreatePreferences(Bundle bundle, String rootKey) {
    PreferenceScreen screen =
        getPreferenceManager().createPreferenceScreen(getPreferenceManager().getContext());
    setPreferenceScreen(screen);
    Context context = getPreferenceManager().getContext();

    PreferenceCategory control = new PreferenceCategory(context);
    control.setTitle(R.string.network_kill_switch_control_category);
    screen.addPreference(control);

    mStatus = new Preference(context);
    mStatus.setKey(KEY_STATUS);
    mStatus.setPersistent(false);
    mStatus.setSelectable(false);
    mStatus.setTitle(R.string.network_kill_switch_status);
    control.addPreference(mStatus);

    Preference blockNow = new Preference(context);
    blockNow.setKey(KEY_BLOCK_NOW);
    blockNow.setPersistent(false);
    blockNow.setTitle(R.string.network_kill_switch_block_now);
    blockNow.setSummary(R.string.network_kill_switch_block_now_summary);
    blockNow.setOnPreferenceClickListener(preference -> {
      promptForMinutes();
      return true;
    });
    control.addPreference(blockNow);

    mRestore = new Preference(context);
    mRestore.setKey(KEY_RESTORE);
    mRestore.setPersistent(false);
    mRestore.setTitle(R.string.network_kill_switch_restore);
    mRestore.setSummary(R.string.network_kill_switch_restore_summary);
    mRestore.setOnPreferenceClickListener(preference -> {
      mKillSwitch.release();
      KillSwitchExpiryReceiver.cancel(getActivity());
      ParentalControlService.refresh(getActivity());
      refreshUi();
      Toast.makeText(getActivity(), R.string.network_kill_switch_restored, Toast.LENGTH_SHORT)
          .show();
      return true;
    });
    control.addPreference(mRestore);

    PreferenceCategory server = new PreferenceCategory(context);
    server.setTitle(R.string.network_kill_switch_server_category);
    screen.addPreference(server);

    mServerEnabled = new SwitchPreference(context);
    mServerEnabled.setKey(KEY_SERVER_ENABLED);
    mServerEnabled.setPersistent(false);
    mServerEnabled.setTitle(R.string.network_kill_switch_server_enabled);
    mServerEnabled.setSummary(R.string.network_kill_switch_server_enabled_summary);
    mServerEnabled.setOnPreferenceChangeListener(
        (preference, value) -> {
          mPrefs
              .edit()
              .putBoolean(ParentalControlPrefs.KEY_RC_ENABLED, Boolean.TRUE.equals(value))
              .commit();
          ParentalControlService.refresh(getActivity());
          refreshUi();
          return true;
        });
    server.addPreference(mServerEnabled);

    // A plain dialog rather than an EditTextPreference: setOnBindEditTextListener is only honoured
    // by PreferenceFragmentCompat, so under the framework PreferenceFragment this repo uses the
    // field would come up with a text keyboard.
    mServerPort = new Preference(context);
    mServerPort.setKey(KEY_SERVER_PORT);
    mServerPort.setPersistent(false);
    mServerPort.setTitle(R.string.network_kill_switch_server_port);
    mServerPort.setOnPreferenceClickListener(
        unused -> {
          EditText input = new EditText(getActivity());
          input.setInputType(InputType.TYPE_CLASS_NUMBER);
          input.setText(
              String.valueOf(
                  mPrefs.getInt(
                      ParentalControlPrefs.KEY_RC_PORT, ParentalControlPrefs.DEFAULT_RC_PORT)));
          new AlertDialog.Builder(getActivity())
              .setTitle(R.string.network_kill_switch_server_port)
              .setView(input)
              .setPositiveButton(
                  android.R.string.ok,
                  (dialog, which) -> {
                    int port = parsePort(input.getText().toString());
                    if (port < 0) {
                      Toast.makeText(
                              getActivity(),
                              R.string.network_kill_switch_bad_port,
                              Toast.LENGTH_SHORT)
                          .show();
                      return;
                    }
                    mPrefs.edit().putInt(ParentalControlPrefs.KEY_RC_PORT, port).commit();
                    ParentalControlService.refresh(getActivity());
                    refreshUi();
                  })
              .setNegativeButton(android.R.string.cancel, null)
              .show();
          return true;
        });
    server.addPreference(mServerPort);

    mServerAddress = new Preference(context);
    mServerAddress.setKey(KEY_SERVER_ADDRESS);
    mServerAddress.setPersistent(false);
    mServerAddress.setSelectable(false);
    mServerAddress.setTitle(R.string.network_kill_switch_server_address);
    server.addPreference(mServerAddress);

    mRequireToken = new SwitchPreference(context);
    mRequireToken.setKey(KEY_REQUIRE_TOKEN);
    mRequireToken.setPersistent(false);
    mRequireToken.setTitle(R.string.network_kill_switch_require_token);
    mRequireToken.setSummary(R.string.network_kill_switch_require_token_summary);
    mRequireToken.setOnPreferenceChangeListener(
        (preference, value) -> {
          boolean required = Boolean.TRUE.equals(value);
          if (required) {
            ensureToken();
          }
          mPrefs
              .edit()
              .putBoolean(ParentalControlPrefs.KEY_RC_REQUIRE_TOKEN, required)
              .commit();
          ParentalControlService.refresh(getActivity());
          refreshUi();
          return true;
        });
    server.addPreference(mRequireToken);

    mToken = new Preference(context);
    mToken.setKey(KEY_TOKEN);
    mToken.setPersistent(false);
    mToken.setTitle(R.string.network_kill_switch_token);
    mToken.setOnPreferenceClickListener(preference -> {
      confirmRegenerateToken();
      return true;
    });
    server.addPreference(mToken);

    refreshUi();
  }

  @Override
  public void onResume() {
    super.onResume();
    refreshUi();
  }

  private void refreshUi() {
    if (mStatus == null) {
      return;
    }
    boolean active = mKillSwitch.isActive();
    if (active) {
      long until = mKillSwitch.getBlockedUntil();
      String detail = TextUtils.join(", ", mKillSwitch.describeActiveEnforcement());
      mStatus.setSummary(
          until > 0
              ? getString(
                  R.string.network_kill_switch_status_until, formatTime(until), detail)
              : getString(R.string.network_kill_switch_status_indefinite, detail));
    } else {
      mStatus.setSummary(R.string.network_kill_switch_status_inactive);
    }
    mRestore.setEnabled(active);

    boolean serverOn = mPrefs.getBoolean(ParentalControlPrefs.KEY_RC_ENABLED, false);
    int port = mPrefs.getInt(ParentalControlPrefs.KEY_RC_PORT, ParentalControlPrefs.DEFAULT_RC_PORT);
    mServerEnabled.setChecked(serverOn);
    mServerPort.setSummary(String.valueOf(port));

    List<String> addresses = localAddresses();
    if (!serverOn) {
      mServerAddress.setSummary(R.string.network_kill_switch_server_off);
    } else if (addresses.isEmpty()) {
      mServerAddress.setSummary(R.string.network_kill_switch_no_address);
    } else {
      List<String> urls = new ArrayList<>();
      for (String address : addresses) {
        urls.add("http://" + address + ":" + port);
      }
      mServerAddress.setSummary(
          getString(R.string.network_kill_switch_open_in_browser, TextUtils.join("\n", urls)));
    }

    boolean requireToken = mPrefs.getBoolean(ParentalControlPrefs.KEY_RC_REQUIRE_TOKEN, false);
    mRequireToken.setChecked(requireToken);
    mToken.setVisible(requireToken);
    String token = mPrefs.getString(ParentalControlPrefs.KEY_RC_TOKEN, "");
    mToken.setSummary(
        TextUtils.isEmpty(token)
            ? getString(R.string.network_kill_switch_token_none)
            : getString(R.string.network_kill_switch_token_summary, token));
  }

  private void promptForMinutes() {
    EditText input = new EditText(getActivity());
    input.setInputType(InputType.TYPE_CLASS_NUMBER);
    input.setHint(R.string.network_kill_switch_minutes_hint);
    new AlertDialog.Builder(getActivity())
        .setTitle(R.string.network_kill_switch_block_now)
        .setMessage(R.string.network_kill_switch_block_now_prompt)
        .setView(input)
        .setPositiveButton(
            android.R.string.ok,
            (dialog, which) -> {
              int minutes = 0;
              String text = input.getText().toString().trim();
              if (!TextUtils.isEmpty(text)) {
                try {
                  minutes = Integer.parseInt(text);
                } catch (NumberFormatException e) {
                  minutes = -1;
                }
              }
              if (minutes < 0) {
                Toast.makeText(
                        getActivity(),
                        R.string.network_kill_switch_bad_minutes,
                        Toast.LENGTH_SHORT)
                    .show();
                return;
              }
              mKillSwitch.engage(minutes);
              KillSwitchExpiryReceiver.schedule(getActivity(), mKillSwitch.getBlockedUntil());
              ParentalControlService.refresh(getActivity());
              refreshUi();
            })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  private void confirmRegenerateToken() {
    new AlertDialog.Builder(getActivity())
        .setTitle(R.string.network_kill_switch_token)
        .setMessage(R.string.network_kill_switch_token_regenerate)
        .setPositiveButton(
            android.R.string.ok,
            (dialog, which) -> {
              mPrefs
                  .edit()
                  .putString(ParentalControlPrefs.KEY_RC_TOKEN, generateToken())
                  .commit();
              refreshUi();
            })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  private void ensureToken() {
    if (TextUtils.isEmpty(mPrefs.getString(ParentalControlPrefs.KEY_RC_TOKEN, ""))) {
      mPrefs.edit().putString(ParentalControlPrefs.KEY_RC_TOKEN, generateToken()).commit();
    }
  }

  private static String generateToken() {
    byte[] bytes = new byte[12];
    new SecureRandom().nextBytes(bytes);
    StringBuilder builder = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      builder.append(String.format(Locale.US, "%02x", b));
    }
    return builder.toString();
  }

  private static int parsePort(String text) {
    try {
      int port = Integer.parseInt(text.trim());
      return (port >= 1024 && port <= 65535) ? port : -1;
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private CharSequence formatTime(long epochMillis) {
    return DateFormat.getTimeFormat(getActivity()).format(new java.util.Date(epochMillis));
  }

  /** Non-loopback IPv4 addresses, which is how the phone is reached from the home network. */
  private static List<String> localAddresses() {
    List<String> addresses = new ArrayList<>();
    try {
      Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
      if (interfaces == null) {
        return addresses;
      }
      while (interfaces.hasMoreElements()) {
        NetworkInterface networkInterface = interfaces.nextElement();
        if (!networkInterface.isUp() || networkInterface.isLoopback()) {
          continue;
        }
        Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
        while (inetAddresses.hasMoreElements()) {
          InetAddress address = inetAddresses.nextElement();
          if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
            addresses.add(address.getHostAddress());
          }
        }
      }
    } catch (SocketException e) {
      return Collections.emptyList();
    }
    return addresses;
  }
}
