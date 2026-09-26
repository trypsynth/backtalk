# Backtalk

Backtalk is a fork of [Google's TalkBack](https://github.com/google/talkback), the screen reader for blind and visually-impaired users of Android. It adds fixes and features on top of Google's source releases. It is not affiliated with Google.

For TalkBack usage instructions, see the [TalkBack User Guide](https://support.google.com/accessibility/android/answer/6283677?hl=en).

## Goals

*   Make TalkBack faster and more responsive for people who use their phone quickly.
*   Bring back useful behavior from older TalkBack and other screen readers.
*   Keep the build working on Windows, Linux, and macOS.
*   Move the code to Kotlin over time. New code is written in Kotlin. Google's Java files are converted only when they already need large changes, so that new Google releases stay easy to merge.
*   Stay close to Google's releases, and merge each new one.

## Changes from TalkBack

### Speed

*   **Time between taps setting.** In TalkBack settings, go to **Advanced settings > Reduce delay > Time between taps** to set how long TalkBack waits for another tap. The default is 0.25 seconds, and you can lower it to 0.1 seconds. A shorter time makes all multi-tap gestures respond faster, but a slow double tap can count as two single taps.
*   **Less lag while scrolling.** After each scroll event, TalkBack searched the list for a new item to focus. This blocked touch exploration and could make gestures fail. TalkBack now does this search once, after scrolling stops.
*   **Faster swiping in lists.** To find the next item in a list, TalkBack built the reading order of the whole screen three times: once to check whether you were at the end of the list, once to find the item, and once to check whether the item needed scrolling into view. It now builds it once and reuses it.
*   **Faster swiping on screens that do not change.** TalkBack asked the app for every item on the screen on each swipe, and waited for each answer. Backtalk now keeps the reading order between swipes, and builds it again only when the screen changes or Backtalk scrolls or clicks. In a test on a Realme phone, 3 of 4 swipes reused the order, and the longest pause during swiping went from about 450 ms to about 150 ms.

### Screen and brightness

*   **Brightness reading control.** Swipe up or down to change the screen brightness in steps of about 10%. This works when the screen is hidden, so you can set the brightness before you give the phone to someone. The first time you use it, TalkBack asks for the "Modify system settings" permission.
*   **Shorter hide screen message.** If you turn off **Always show this** in the hide screen dialog, Backtalk only says "Screen hidden" and skips the instructions for showing the screen again.
*   **Hide screen brightness fix.** For 3 minutes after you hide the screen, TalkBack set the screen to full brightness. It now keeps your brightness.
*   **Proximity sensor off by default.** TalkBack no longer stops speech when something covers the proximity sensor. To turn it back on, go to **Advanced settings > Cover proximity sensor to stop speech** in TalkBack settings.

### Gestures

*   **4-finger taps by default.** Tap with 4 fingers to go back. Double-tap with 4 fingers to go home. Triple-tap with 4 fingers to open recent apps. If you changed these gestures before, your settings stay.

### TalkBack menu

*   **Shorter menu by default.** These items are off by default: Actions, Screen search, Add or edit labels, Describe text formatting, Copy last spoken phrase, Spoken language, Voice commands, Keyboard shortcuts, and Braille display settings. Actions stay available with the actions reading control. Text-to-speech stays on, so that you can get to speech settings if your speech engine crashes. To turn them back on, go to **Customize menus** in TalkBack settings.

### Speech

*   **No "collapsed" on notifications.** TalkBack no longer says "collapsed" for each notification on the lock screen and in the notification shade. It still says "expanded" when you open one, and it still says "collapsed" in other apps.

### Braille keyboard

*   **Better haptics.** The braille keyboard uses the same crisp vibration effects as the rest of TalkBack. Submitting text feels the same as closing or switching the keyboard. Deleting in an empty field gives a soft vibration that fades out, so that you know there was nothing to delete.

## Build

### Linux or macOS

Run `./build.sh`. This produces an APK file.

### Windows

You need JDK 17, the Android SDK, and NDK 27.3.13750724. Set `ANDROID_HOME` to your SDK path, then run:

```
.\gradlew.bat assemblePhoneDebug
```

### Image descriptions with Gemini

Google's source release does not include the Gemini settings, so **Describe image** only reads text in images, and **Describe screen** does not work, unless you add your own Gemini API key. Backtalk adds its own support for Describe screen, including follow-up questions. To add a key:

1.  Get an API key from [Google AI Studio](https://aistudio.google.com/apikey).
2.  Add this line to `local.properties` in the project folder: `gemini.api.key=YOUR_KEY`
3.  Optional: to use a different model, add `gemini.model=MODEL_NAME`. The default is `gemini-flash-latest`.
4.  Build and install Backtalk again.

Git ignores `local.properties`, so your key is not committed. The key is built into the APK, so do not share an APK that contains your key.

Images and screenshots that you describe are sent to Google. On the free tier, Google can use this data to improve its products.

## Install

Install the APK on your device with adb.

On Windows, `.\deploy.ps1` builds the APK and installs it with adb. This script needs PowerShell 7. To install the last build without building again, use `-SkipBuild`.

Backtalk installs as `com.android.talkback`, so it does not replace Google's TalkBack. The two apps have separate settings.

## Run

After you install Backtalk, go to **Settings > Accessibility**. Backtalk is listed as **Backtalk** and is off by default. Turn off Google's TalkBack first, then turn on Backtalk.

## Debug tools

Debug builds include tools to find lag:

*   The `BacktalkStall` logcat tag logs each time the main thread is blocked for more than 100 ms, with the code that was running.
*   The `BacktalkGesture` logcat tag logs touch state changes and each gesture that TalkBack detects.
*   Event processing has trace sections, which show in [Perfetto](https://perfetto.dev) system traces.
