Parental controls for TestDPC: per-app and combined daily screen time limits,
quiet hours, and a network kill switch you can trigger from a browser on your
own phone. Built on device owner APIs, because Digital Wellbeing is unavailable
once an app is device owner.

## Screen time limits

Pick which apps to watch from everything installed, system apps included, then
set any combination of:

- a **daily cap per app**, for example two hours of YouTube
- a **combined daily cap** across every monitored app, so two hours of YouTube
  and two of Netflix uses up a four hour allowance
- **quiet hours**, which may wrap past midnight, such as 22:00 to 08:00

Apps that are not on the list are never touched, so the dialer and messaging
keep working. Reaching a limit suspends the app: launching it shows the
platform's "blocked" dialog, and an app suspended while it is on screen drops
back to the launcher. A friendlier notification explains what happened.

> 🌙 **Time for bed** — YouTube is having a rest until 08:00. Sweet dreams! 💤

Counters reset at local midnight, or on demand from the app or the web page.

**What it survives.** Foreground time is derived from the system usage event
log rather than counted by a ticker, so a reboot, a force stop or process death
costs nothing. Time does not accrue while the screen is locked, so locking the
phone mid-video is not punished, nor during quiet hours, when the app cannot be
opened at all. The accounting clock is cross-checked against the monotonic
clock, so winding the date forward does not hand out a fresh day.

Optional **tamper protection** pins the clock to network time and blocks force
stop, uninstall and safe mode.

## Network kill switch

Switches Wi-Fi and mobile data off for a chosen number of minutes and switches
both back on when the time is up. Nothing is tunnelled or filtered: the radios
themselves go down, so the phone leaves the network entirely.

Turn on the control server and open the address it shows in a browser on your
own phone:

```
http://<phone-ip>:8080
```

Choose 5 minutes to 2 hours, or "until I restore it", and press one button. The
page also shows today's screen time and can switch quiet hours on and off, which
is handy when handing the phone over after bedtime.

Confirming the cut is deliberately indirect: the phone answers, waits a second,
then pulls the network down, and the page waits five seconds and checks that the
phone has gone quiet. A successful call destroys the channel it arrived on, so
no reply could report it. Ending a block early is done in the app on the device,
by design.

`scripts/network_killswitch.py` drives the same interface from a desktop.

## Installing

The APK is marked `testOnly`, so that a device owner provisioned from it can be
removed with one adb command instead of a factory reset. **It will not install
by tapping it** — use `-t`:

```console
adb install -r -t testdpc-9.0.14-parental-controls.apk
adb shell dpm set-device-owner com.afwsamples.testdpc/.DeviceAdminReceiver
adb shell appops set com.afwsamples.testdpc android:get_usage_stats allow
```

Setting a device owner requires a device with **no accounts signed in**, so this
is done on a freshly reset phone before adding a Google account.

The usage access grant on the third line is required and cannot be granted by a
device owner itself. Without it the screen time counters silently read zero. Run
it again after any update.

To hand the phone back:

```console
adb shell dpm remove-active-admin com.afwsamples.testdpc/.DeviceAdminReceiver
adb uninstall com.afwsamples.testdpc
```

## Known limits

- **Mobile data** has no device owner API for its toggle. It goes through
  `TelephonyManager` and the result is reported rather than assumed; Wi-Fi is
  switched off through `WifiManager`, which the platform honours for a device
  owner.
- **The system's "blocked" dialog cannot be reworded.** That string belongs to
  the Settings app and overriding it needs a role-granted permission, so the
  app posts its own friendlier notification instead.
- **The control server has no password by default.** It is meant for a home
  network, where a secret typed by hand on a phone costs more than it protects.
  A token can be required on the settings screen.
- **Port 80 is not possible.** Ports below 1024 need root, which being device
  owner does not grant, hence 8080.

Tested on a Pixel 4a (Android 13) and an AI+ Pulse 2 4G (Android 16).
