# Backtalk

Backtalk is a fork of [Google's TalkBack](https://github.com/google/talkback),
the screen reader for blind and visually-impaired users of Android. It adds
build fixes and changes on top of Google's source releases. It is not affiliated
with Google.

For TalkBack usage instructions, see the
[TalkBack User Guide](https://support.google.com/accessibility/android/answer/6283677?hl=en).

### How to Build

On Linux or macOS, run ./build.sh, which will produce an apk file.

On Windows, run .\gradlew.bat assemblePhoneDebug. You need JDK 17, the Android
SDK, and NDK 27.3.13750724. Set ANDROID_HOME to your SDK path.

### How to Install

Install the apk onto your Android device in the usual manner using adb.

On Windows, .\deploy.ps1 builds the apk and installs it with adb. Use
-SkipBuild to install the last build only.

### How to Run

With the apk now installed on the device, the TalkBack service should now be
present under Settings -> Accessibility, and will be off by default. To turn it
on, toggle the switch preference to the on position.
