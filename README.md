# Page Alarm

Android app that watches a web page and sounds a loud alarm when its **visible text** changes.

- You enter the URL in the app (http or https, no login).
- A foreground service re-checks the page every **5 minutes**.
- Only visible text counts. Scripts, styles, markup and comments are ignored.
- On a change: the siren plays on the **alarm volume stream at maximum** (works on silent),
  the phone vibrates, and a full-screen red alert appears over the lock screen.
  It keeps going until you tap **Stop alarm**. The page you were alerted about then becomes the new baseline.
- Failed checks (no network, HTTP errors) never trigger the alarm. They show in the status line.
- Monitoring restarts after a reboot or app update if it was switched on.

## Get the APK

Every push builds a debug APK in GitHub Actions: open the **Actions** tab, pick the latest
"Build APK" run and download the `page-alarm-debug-apk` artifact. Unzip it, copy
`app-debug.apk` to the phone and install it (allow "install unknown apps" when asked).

Or build locally with JDK 17 and the Android SDK: `./gradlew assembleDebug`
(output: `app/build/outputs/apk/debug/app-debug.apk`), or open the folder in Android Studio.

## First run

The app lists what is still missing on the main screen. Do all of them for a reliable alarm:

1. **Notifications**: needed for the alert notification (Android 13+).
2. **Full-screen alarm**: lets the alert pop up over the lock screen (Android 14+).
3. **Exact timing**: keeps the 5-minute interval accurate (Android 12+).
4. **Battery optimization off**: stops Android from pausing the checks while the phone sleeps.

Then tap **Test alarm** to check the volume, and **Start monitoring**.
Some phone brands (Xiaomi, Huawei, OnePlus, Samsung) also have their own "auto-start" or
"battery" settings. Allow background activity for Page Alarm there too.

## Limits

- Pages that show a clock, counter, ad rotation or "x visitors" will alarm every time that text changes.
- Pages rendered only by JavaScript (empty HTML shell) have no text for the app to see.
- The first check after pressing Start is the baseline. Nothing alarms until a later check differs.
- Minimum Android version is 8.1 (API 27).

## Layout

| File | Purpose |
| --- | --- |
| `MainActivity.kt` | URL input, start/stop, status, setup shortcuts, test alarm |
| `MonitorService.kt` | Foreground service: 5-minute timer, check, siren, notifications |
| `PageText.kt` | Downloads the page (jsoup) and reduces it to visible text lines; describes the change |
| `AlarmActivity.kt` | Full-screen alert with the Stop button |
| `BootReceiver.kt` | Restarts monitoring after reboot |
| `Store.kt` | Saved settings and state |
