Test Device Policy Control (Test DPC) App
=========================================

Test DPC is an app designed to help EMMs, ISVs, and OEMs to test their applications and platforms in a Android enterprise managed profile (i.e. work profile). It serves as both a sample Device Policy Controller and a testing application to flex the APIs available for Android enterprise. It supports devices running Android 5.0 Lollipop or later.

See the [documentation](https://developer.android.com/work/index.html) to learn more about Android in the enterprise.

## Getting Started

This sample uses the Bazel build system. To build this project, use the "bazel build testdpc" command.

This app can also be found [on the Play store](https://play.google.com/store/apps/details?id=com.afwsamples.testdpc).

## Provisioning

You can find various kinds of provisioning methods [here](https://developers.google.com/android/work/prov-devices#Key_provisioning_differences_across_android_releases). Let's take a few of them as an example.

### AFW# code provisioning (Device Owner M+)
1. Factory reset your device.
2. Setup Wi-Fi
3. When prompted to sign in, enter **afw#testdpc**
4. Follow onscreen instructions
  - Choose 'Use for work only' for fully managed setup.

### QR code provisioning (Device Owner N+ only)
1. Factory reset your device and tap the welcome screen in setup wizard 6 times.
1. On Android O or older, the setup wizard prompts the user to connect to the Internet so the setup wizard can download a QR code reader.
   Android P and newer devices already have the QR code reader available.
1. Generate a QR code with the content:
   ```
    {
    	"android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": "com.afwsamples.testdpc/com.afwsamples.testdpc.DeviceAdminReceiver",
    	"android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM": "gJD2YwtOiWJHkSMkkIfLRlj-quNqG1fb6v100QmzM9w=",
    	"android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": "https://testdpc-latest-apk.appspot.com"
    }
   ```
   or use this pre-made QR code:  
   ![testdpc_provisioning](qrcode.png)
1. Scan the QR code and follow onscreen instructions

#### Note

If using this QR code your device is stuck on the configuring screen, it may due to a problem connecting to the `appspot.com` domain.

In these cases you can use the [latest release](https://github.com/googlesamples/android-testdpc/releases/latest) available on github.
You can also upload this version on your own server and use that as your download location.

Replace the link used for `PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION` with a link to your APK. After that, regenerate the QR code.

### ADB command

#### Device Owner (DO)

*   Run the `adb` command:

    ```console
    adb shell dpm set-device-owner com.afwsamples.testdpc/.DeviceAdminReceiver
    ```

#### Profile Owner - Personal device (PO - BYOD)

*   Create a managed profile by launching the “Set up TestDPC” app
*   Skip adding an account at the end of the flow

#### Profile Owner - Corporate-owned device (PO - COPE)

*   Create a managed profile by launching the “Set up TestDPC” app
*   Skip adding an account at the end of the flow
*   Run the `adb` command:

    ```console
    adb shell dpm mark-profile-owner-on-organization-owned-device --user 10 com.afwsamples.testdpc/.DeviceAdminReceiver`
    ```

#### TestDPC as DM role holder

TestDPC v9.0.5+ can be setup as Device Management Role Holder.

*   Running the following `adb` commands:

    ```console
    adb shell cmd role set-bypassing-role-qualification true
    adb shell cmd role add-role-holder android.app.role.DEVICE_POLICY_MANAGEMENT com.afwsamples.testdpc
    ```

    Note: unlike DO/PO, this change is not persisted so TestDPC needs to be
    marked as role holder again if the device reboots.

## Parental controls

Two screens under **Parental controls** in the policy list, added for running a
child's phone from a device owner where Digital Wellbeing is unavailable.

### Screen time limits

Choose which apps to watch from everything installed, system apps included,
then set any combination of:

*   a daily cap per app, for example two hours of YouTube
*   a combined daily cap across every monitored app
*   quiet hours, which may wrap past midnight, such as 22:00 to 08:00

Apps that are not on the list are never touched, so the dialer and messaging
keep working. Reaching a cap suspends the app with
`DevicePolicyManager#setPackagesSuspended`: launching it then shows the
platform's "blocked by your admin" dialog, and an app suspended while it is on
screen drops back to the launcher. TestDPC also posts a friendlier notification
of its own, since that system dialog cannot be reworded without a role-granted
permission.

Counters reset at local midnight, or on demand from the screen or the control
page. Foreground time is derived from the usage event log rather than counted
by a ticker, so it survives a reboot, a force stop and process death. Time does
not accrue while the screen is locked or during quiet hours.

**Usage access is required** and cannot be granted by a device owner, so grant
it once per install:

```console
adb shell appops set com.afwsamples.testdpc android:get_usage_stats allow
```

The screen shows whether it is granted and links to the settings page. Without
it the counters silently read zero rather than reporting an error.

*Tamper protection* is an optional switch that pins the clock to network time
and blocks force stop, uninstall and safe mode. Turn it off again before
uninstalling.

### Network kill switch

Switches Wi-Fi and mobile data off for a chosen number of minutes and switches
both back on when the time is up. Nothing is tunnelled or filtered; the radios
themselves go down, so the phone leaves the network entirely and cannot be
reached until the block expires. Ending one early is done on the device.

Enable the control server on the screen and open the address it shows in a
browser on another phone:

```
http://<phone-ip>:8080
```

The page picks a duration, reports whether the block actually took hold, shows
today's screen time and can switch quiet hours on and off. A token can be
required but is off by default, on the grounds that a secret typed by hand on a
phone costs more than it protects on a home network. Ports below 1024 need
root, so 8080 rather than 80.

The same interface is scriptable, and `scripts/network_killswitch.py` drives
it:

```console
python scripts/network_killswitch.py --host 192.168.1.15 off --minutes 30
python scripts/network_killswitch.py --host 192.168.1.15 status
```

Confirming a cut is deliberately indirect. The phone answers 200, waits a
second and only then pulls the network down; the client waits five seconds and
probes `/ping`, where silence means success. A successful call destroys the
channel it arrived on, so no reply could ever report it.

There is no device owner API for the cellular data toggle, so that goes through
`TelephonyManager` and the outcome is recorded rather than assumed. Wi-Fi is
switched off through `WifiManager`, which the platform honours for a device
owner.

### Removing the app afterwards

The application is marked `testOnly`, so a device owner provisioned from a
build of this repository can be removed without a factory reset:

```console
adb shell dpm remove-active-admin com.afwsamples.testdpc/.DeviceAdminReceiver
adb uninstall com.afwsamples.testdpc
```

This is recorded when the admin is set, not when the APK is installed, so a
device provisioned from a build without the flag must be de-provisioned through
the app's own "Remove this device owner" screen first.

## Android Studio import

To import this repository in Android Studio, you need to use the 
[Bazel for Android Studio](https://plugins.jetbrains.com/plugin/9185-bazel-for-android-studio)
Plugin.

When importing the project you have to select the folder containing the Bazel's
`BUILD` file. When prompted to select a "project view", you can choose the
option "Copy external" and choose the `scripts/ij.bazelproject` available in
this repository.

Once Bazel has complete the import operation and the first sync of the
project, you can create a "Run Configuration".
Select "Bazel Command" as Configuration type and add `//:testdpc` as
"target expression".

You can now run the project from inside Android Studio.

## Building with Bazel

The repository includes a `build.sh` script to build the application. The required
[setupdesign library](https://android.googlesource.com/platform/external/setupdesign/+/refs/heads/main)
is now imported and patched dynamically using the command line utility `sed`. This needs to be
available on the path to successfully build the project.

### Building on Windows

`sed` comes with Git for Windows, so the patch step works from Git Bash. Bazel
needs a JDK on `PATH` to fetch dependencies, and Android Studio's bundled one
will do:

```console
export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
export PATH="$JAVA_HOME/bin:$PATH"
bazel build testdpc --java_runtime_version=remotejdk_17 --tool_java_runtime_version=remotejdk_17
```

If a build fails with "failed to delete output files ... Permission denied", a
worker is still holding an output jar; `bazel shutdown` and retry.

Installing needs `-t`, because the application is marked `testOnly`. Play
Protect may also refuse the install, which can be turned off for the duration:

```console
adb shell settings put global verifier_verify_adb_installs 0
adb install -r -t bazel-bin/testdpc.apk
adb shell settings delete global verifier_verify_adb_installs
```

### `ANDROID_HOME` environment setup

Bazel requires that you set the `ANDROID_HOME` environment variable to the path of your Android SDK.
As an example, you can add to your `.bashrc` on linux:
```
export ANDROID_HOME=<Path to the Android SDK>
```

## Support

If you've found an error in this sample, please file an issue:
https://github.com/googlesamples/android-testdpc/issues

Patches are encouraged, and may be submitted by forking this project and submitting a pull request through GitHub.

## License

Licensed under the Apache 2.0 license. See the LICENSE file for details.

## How to make contributions?

Please read and follow the steps in the CONTRIB file.
