# Backtalk

Backtalk is a fork of [Google's TalkBack](https://github.com/google/talkback), the screen reader for blind and visually-impaired users of Android. It adds fixes and features on top of Google's source releases. It is not affiliated with Google.

## Goals

*   Make Backtalk faster and more responsive for people who use their phone quickly.
*   Bring back useful behavior from older TalkBack and other screen readers.
*   Keep the build working on Windows, Linux, and macOS.
*   Move the code to Kotlin over time. New code is written in Kotlin. Google's Java files are converted only when they already need large changes, so that new Google releases stay easy to merge.
*   Stay close to Google's releases, and merge each new one.

## Changes from TalkBack

### Speed

*   **Time between taps setting.** In Backtalk settings, go to **Advanced settings > Reduce delay > Time between taps** to set how long Backtalk waits for another tap. The default is 0.25 seconds, and you can lower it to 0.1 seconds. A shorter time makes all multi-tap gestures respond faster, but a slow double tap can count as two single taps.
*   **Less lag while scrolling.** After each scroll event, TalkBack searched the list for a new item to focus. This blocked touch exploration and could make gestures fail. Backtalk now does this search once, after scrolling stops.
*   **Faster swiping in lists.** To find the next item in a list, TalkBack built the reading order of the whole screen three times: once to check whether you were at the end of the list, once to find the item, and once to check whether the item needed scrolling into view. Backtalk now builds it once and reuses it.
*   **Faster swiping on screens that do not change.** TalkBack asked the app for every item on the screen on each swipe, and waited for each answer. Backtalk now keeps the reading order between swipes, and builds it again only when the screen changes or Backtalk scrolls or clicks. In a test on a Realme phone, 3 of 4 swipes reused the order, and the longest pause during swiping went from about 450 ms to about 150 ms.
*   **Swiping away from long paragraphs.** Some speech engines, such as Gryphon, only stop between the blocks they synthesize, and treat a whole item as one block. When you swiped away from a long paragraph on a web page, the next item could wait up to 850 ms for the engine to stop. To fix this, go to **Text-to-speech** in Backtalk settings and turn on **Send long text a sentence at a time**. Backtalk then sends text longer than 150 characters a sentence at a time, queued one after another, so the engine stops at once. In Chrome on a Samsung phone with Gryphon, web swipes went from as much as 900 ms to at most about 110 ms. It is off by default, because many engines, such as RHVoice, already stop at once, and pause longer between sentences sent separately.
*   **Faster screen changes.** After a window changes, TalkBack waits for it to settle before it says the title, and counted that wait in a way that made 550 ms last about 900 ms. Backtalk counts the real time. **Turn off animations** in Advanced settings turns off animations for the whole phone, so screens change at once and the wait is only 200 ms. TalkBack had disabled the part that turns animations off, so the setting did nothing. Backtalk turns it back on, and turns animations back on when Backtalk is turned off. On Android 12 and earlier, which do not let Backtalk turn animations off, the setting only shortens the wait. The setting is on by default.
*   **Swiping into the next part of a list.** When the next item was only partly on screen, TalkBack scrolled it into view and waited for the scroll before it spoke, and then waited another 110 ms in case more scroll events came. Backtalk now speaks the item at once and lets the scroll finish behind the speech. When the next item is off screen, Backtalk checks once a frame whether the list has moved, rather than waiting for the scroll event, which Android sends at most every 100 ms. On a Samsung phone, swipes that scroll went from about 310 ms to about 60 ms when the item was partly on screen, and from about 320 ms to about 140 ms when the list had to scroll a page.
*   **Scroll views show the next screen at once.** Screens that are one long page, rather than a list, keep all of their content in the tree. When you swipe past the last item on screen, Backtalk scrolls straight to the next screen, without the animation, so that the next item starts it, and searches again at once. An item that was cut off at the edge of the screen is read rather than scrolled past, as TalkBack's page scroll did. This needs Android 14 or later. Lists, grids and Compose screens still scroll a page, because they only create the items on screen or animate their scrolling.
*   **Focus stays where a swipe's scroll put it.** A list can carry on scrolling for a moment after a swipe has moved focus onto the next item. TalkBack took the rest of that scroll for one made by hand, and moved focus on to the next item by itself. Backtalk counts it as part of its own scroll.

### Screen and brightness

*   **Brightness reading control.** Swipe up or down to change the screen brightness in steps of about 10%. This works when the screen is hidden, so you can set the brightness before you give the phone to someone. The first time you use it, Backtalk asks for the "Modify system settings" permission.
*   **Shorter hide screen message.** If you turn off **Always show this** in the hide screen dialog, Backtalk only says "Screen hidden" and skips the instructions for showing the screen again.
*   **Hide screen brightness fix.** For 3 minutes after you hide the screen, TalkBack set the screen to full brightness. Backtalk keeps your brightness.
*   **Proximity sensor off by default.** Backtalk does not stop speech when something covers the proximity sensor. To turn it back on, go to **Advanced settings > Cover proximity sensor to stop speech** in Backtalk settings.

### Gestures

*   **New default gestures.**
    *   Tap with 4 fingers: go back.
    *   Double-tap with 4 fingers: go home.
    *   Triple-tap with 4 fingers: open recent apps.
    *   Double-tap and hold with 4 fingers: open the notification shade.
    *   Double-tap and hold with 2 fingers: switch to the braille keyboard.
    *   Tap and hold with 3 fingers: pass through the next gesture.
    *   Triple-tap with 3 fingers: copy the last spoken phrase.
    *   Double-tap with 3 fingers: turn speech off or on, as with VoiceOver.
    *   Triple-tap and hold with 3 fingers: paste.
    *   Quadruple-tap with 3 fingers: hide or show the screen. This gesture is new in Backtalk, which recognizes it itself, so it needs Android 13 or later and **Handle gestures in Backtalk** on, which it is by default. It can be reassigned in gesture settings like the others.
    *   Selection mode, copy, and the old speech gesture (triple-tap and hold with 2 fingers) have no gesture.
*   **Navigation gestures.** You can assign a gesture to move to the next or previous character, word, line, paragraph, heading, link, control, landmark, button, checkbox, radio button, edit field, combo box, focusable item, graphic, list, list item, table, visited link, unvisited link, or heading of a given level. The reading control does not change. Find these actions under **Navigate by text**, **Navigate by element**, and **Navigate by heading level** when you choose an action for a gesture. Headings, links, and controls work in apps and on web pages. The other elements work only on web pages, and elsewhere Backtalk says so.
*   **Status gesture.** Triple-tap with 2 fingers to hear what the status bar shows: the time, battery, Wi-Fi, and mobile signal, and the ringer, Do Not Disturb, and airplane mode when they are not in their usual state. To hear the Wi-Fi network name, allow location access when Backtalk asks the first time. To choose what it says and in what order, go to **Status readout** in Backtalk settings. Each item has **Move up** and **Move down** actions. This action is also in the gesture list as **Speak status**. To read from the current item, use **Read from next item** in the Backtalk menu.
*   **Choose what is said when the screen turns off or on.** In **Verbosity > Screen on and off**, turn off "Screen off" or the ringer mode when the screen turns off, the time when the screen turns on, or "Device unlocked" when you unlock the phone. When the screen turns on, Backtalk can also say the battery, Wi-Fi, mobile network, ringer and Do Not Disturb, and airplane mode, each with its own switch, after the time and in the same announcement. These are separate from **Status readout**, so the status gesture can say different things.
*   **Rotor.** Turn 2 fingers on the screen like a dial to choose a reading control. Turn clockwise for the next one and counterclockwise for the previous one. Each step of about a twelfth of a turn moves one reading control, so you can keep turning to move further. Then swipe up or down to change it. You can assign other actions to **Rotate clockwise with 2 fingers** and **Rotate counterclockwise with 2 fingers** in gesture settings. This needs Android 13 or later.
*   **Lift to activate.** Backtalk can activate the item under your finger when you lift it after exploring by touch, as TalkBack already does for keys on the keyboard. Go to **Advanced settings > Lift to activate** in Backtalk settings and choose **Only on navigation bar**, so that the back, home and recent apps buttons work this way and the rest of the screen does not, or **Entire screen**. It is **Disabled** by default. **Lift to activate** is also a reading control, so you can change it with a swipe up or down.
*   **Gestures handled by Backtalk.** Backtalk now recognizes gestures itself by default, instead of leaving this to Android. This is what makes the rotor possible. To let Android recognize gestures again, go to **Developer settings** in Backtalk settings and turn off **Handle gestures in Backtalk**, then turn Backtalk off and on again. The rotor does not work then.

### Backtalk menu

*   **Shorter menu by default.** These items are off by default: Actions, Screen search, Add or edit labels, Describe text formatting, Copy last spoken phrase, Spoken language, Voice commands, Keyboard shortcuts, and Braille display settings. Actions stay available with the actions reading control. Text-to-speech stays on, so that you can get to speech settings if your speech engine crashes. To turn them back on, go to **Backtalk menu** in Backtalk settings.
*   **Circle menu.** The Backtalk menu can show as a circle in the middle of the screen, like in TalkBack 8.1 and earlier. Each item is a slice of the screen around the middle. Slide to an item, and lift to select it. Lift in the middle of the circle to close the menu. Items that open more items show them in a new circle. To turn it on, go to **Backtalk menu** in Backtalk settings and turn on **Circle menu**.

### Pause

*   **Pause Backtalk.** Pausing turns off Backtalk's speech, sounds, vibration and gestures, and explore by touch, so the phone works as if no screen reader is on. Backtalk stays on, so it resumes at once. This was in TalkBack 8.1 and earlier as suspend. To pause, choose **Pause Backtalk** in the Backtalk menu, assign the **Pause Backtalk** action to a gesture, or assign the **Pause or resume Backtalk** keyboard shortcut. The first time, Backtalk asks to confirm and says how to resume. To resume, tap the **Backtalk is paused** notification, press volume down 3 times quickly, or press the keyboard shortcut again. By default, Backtalk also resumes when the lock screen shows. To change this, go to **Advanced settings > Resume Backtalk** in Backtalk settings and choose **When the screen turns on** or **Only from the notification or a shortcut**. Volume keys still change the volume while Backtalk is paused. If Backtalk restarts while paused, it starts unpaused.

### Speech

*   **Speak notifications setting.** To stop Backtalk from reading new notifications when they arrive, go to **Verbosity** in Backtalk settings and turn off **Speak notifications**. Incoming calls are still read, and you can still read notifications in the notification shade.
*   **Resume speech where you paused it.** With speech engines that do not report word positions, such as RHVoice and Gryphon, tapping with 2 fingers to resume speech started the text over. Backtalk now learns how fast your engine speaks from the items it finishes, works out how far it got when you paused, and resumes from the start of that sentence or phrase. It can repeat a few words, but it never skips any. Engines that report word positions still resume from the word.
*   **No "collapsed" on notifications.** Backtalk does not say "collapsed" for each notification on the lock screen and in the notification shade. It still says "expanded" when you open one, and it still says "collapsed" in other apps.

### Calls

*   **Speaker when away from your ear.** Like VoiceOver on iPhone, Backtalk can move a call to the speaker when you take the phone away from your ear, and back to the earpiece when you hold it up again. A call that starts with the phone away from your ear goes straight to the speaker. Backtalk leaves Bluetooth and wired headsets alone, and if you turn the speaker on or off in the Phone app, your choice stays until you move the phone again. Android only lets apps such as smartwatch companions change where call audio goes, so you grant the permission yourself, once, with adb or [Shizuku](https://shizuku.rikka.app): `adb shell appops set fyi.quin.backtalk MANAGE_ONGOING_CALLS allow`. Then turn on **Speaker when away from your ear** in **Advanced settings**. Until the permission is granted, the setting is unavailable and shows the command. This needs Android 12 or later.

### Sound and vibration

*   **A vibration for every sound.** Like VoiceOver, Backtalk tells you what happened by touch alone. Every sound has its own vibration, and it plays even when **Sound feedback** is off, so you can turn all sounds off and still feel each action. Progress bar tones do not vibrate, and neither does the sound for capital letters. The loading tone, which repeats while you wait for an image or screen description, has a short heartbeat, firm enough to feel through a case, so you can feel that the work goes on, which matters most with slower on-device AI. Braille display and braille keyboard sounds do not vibrate either, so braille vibrations still follow the braille keyboard's own setting. No two actions share a vibration. Frequent ones are a single hit: a soft tap when focus lands on an item, a firm click on an actionable item, and a lighter tap over an empty area. Others have their own rhythm and shape: two knocks at the end of a list, a rise when you enter a list and a fall when you leave it, a swell up and down when the window changes, and three quick ticks when a list scrolls. When the circle menu opens, you feel one tick for each item. Phones that support rich haptics play these as composed effects. Other phones that can vary vibration strength play them as swells and fades, and the rest play them as rhythms of short and long pulses, which are also unique.
*   **Individual sounds and vibrations.** To turn off one sound or one vibration and keep the rest, go to **Sound and vibration** in Backtalk settings and open **Individual sounds and vibrations**. There is a switch for each sound and each vibration, including the braille display and braille keyboard sounds. Turning off a sound keeps its vibration, and turning off a vibration keeps its sound. To hear or feel one, open the actions menu on its switch and choose **Preview**. Changing a switch plays nothing. **Sound feedback** and **Vibration feedback** still turn all of them off at once.
*   **Sound themes.** A sound theme is a set of sounds and vibrations that replace Backtalk's own, with its own control sounds and 3D audio settings. Install as many as you like, from a file or a link, and switch between them in **Sound and vibration** > **Sound themes**. You can also open or share a theme file, or a link to one, with Backtalk from another app. A link to a GitHub repository with a theme in it works too. To replace single sounds with your own, open **Sounds** under the theme in use, and to share your sounds, save the theme as a file. See [themes.md](themes.md) for how to install, use and make themes.
*   **Control sounds.** Like the [Unspoken](https://github.com/ahicks92/Unspoken) add-on for NVDA, Backtalk can play a sound for each kind of element in place of the focus sound, and leave out saying "button", "checkbox" and the like. There are sounds, and vibrations, for buttons, checkboxes (also used for switches and toggle buttons), radio buttons, edit fields, drop-down lists, sliders, images, clocks, tabs, menu items and list items, and on web pages also for links and tree items. Backtalk has no sounds or vibrations of its own for them: they come from the sound theme in use. A control's vibration plays even with sound feedback off, so you can learn the kinds of control by touch alone, and an element with a vibration but no sound plays the focus sound with its own vibration. Until a kind of element has a sound or a vibration, it plays the focus sound and Backtalk says what kind it is. Each sound comes from where the element is on the screen: from the left for elements on the left, and from higher up for elements near the top. A row that is not a control itself, such as a row in Settings, plays the sound of the control in it, such as its switch, and otherwise the sound of the kind of item it is, such as a list item. Plain text keeps the usual focus sound, and moving into or out of a list plays the usual list sounds instead, and then Backtalk still says the kind of control. Keys on the keyboard keep the usual focus sound too. All of these come from where the focus lands as well. To turn them on, go to **Sound and vibration** > **Sound themes** and turn on **Control sounds and vibrations**. With headphones, including any Bluetooth audio device, the sounds play in 3D, using measurements of how a real head hears sound from each direction (the MIT Media Lab KEMAR measurements, by Bill Gardner and Keith Martin). On the phone speaker, they only move between left and right. To play them in 3D on the speaker as well, or never, use **3D audio**. Each theme has its own **Control sounds and vibrations** and **3D audio** settings. To keep hearing the kind of control as well, turn on **Still say the kind of control** in **Sound and vibration**. Each sound and vibration also has its own switch in **Individual sounds and vibrations**.

### Direct touch

Audio games need raw touch, but Explore by Touch captures taps and swipes before the game sees them. With direct touch, Backtalk passes your touches straight to the games you choose, and you do not have to suspend Backtalk.

Direct touch is all of [NVGT Bridge](https://github.com/trypsynth/nvgt-bridge), the accessibility service that gives audio games direct touch, built into Backtalk. It works better in games than running NVGT Bridge next to Backtalk, because Backtalk is the screen reader and already knows what is on the screen:

*   **One service, not two.** There is nothing extra to install, sideload, or turn on in Accessibility settings, and no second service that can undo Backtalk's touch setting. Direct touch shares that setting with the pass-through gesture and the braille keyboard, so none of them cancels another.
*   **No searching the screen.** NVGT Bridge searches each window's view tree for dialogs and text fields, and only looks five levels deep. Backtalk already follows the windows, the keyboard and the screen state, so it hands touch back to your screen reader as soon as they change, and there is no depth limit.
*   **Announcements through Backtalk.** "Direct touch on" and "Direct touch off" are spoken by Backtalk's own speech, even while the game is playing audio, and it can vibrate on each change.

To use it:

*   **Choose your games.** Go to **Direct touch** in TalkBack settings and turn on each game in the **Apps** list. Backtalk says "Direct touch on" when a game you chose comes to the front, and "Direct touch off" when it gives touch back. You can turn the speech off, and turn on a short vibration instead or as well.
*   **Backtalk takes touch back when it is needed.** Touch returns to Backtalk when a dialog appears, another app opens on top of the game, you open the notification shade or quick settings, a text field takes focus, or the screen turns off. It goes back to the game when they are gone. The keyboard always stays with Backtalk, so you can explore it while the rest of the screen stays in direct touch.
*   **Direct typing.** By default the keyboard area keeps working with Backtalk. For a game that draws its own keyboard, open the actions menu on the game in the list and choose **Turn on direct typing**.
*   **Quick settings tile.** Add the **Direct touch** tile to pause and resume direct touch without leaving your game.
*   **Backup and restore.** **Back up settings** saves your choices to a file, and **Restore settings** loads them on another device.
*   **For game developers.** Add this `<meta-data>` tag inside your `<application>` or your main `<activity>`, and Backtalk turns your game on the first time it sees it. Players can still turn it off.

    ```xml
    <meta-data
        android:name="dev.nvgt.capability.DIRECT_TOUCH"
        android:value="true" />
    ```

*   **Not covered.** A dialog or text field that a game draws inside its own screen is invisible to Backtalk. Use the quick settings tile to pause direct touch when that happens.

### Braille keyboard

*   **Typing sounds.** In braille keyboard settings, **Typing sounds** plays Android's keyboard clicks as you type: a click for each character, and the space, delete and return sounds for those actions. It is off by default, and works even when touch sounds are off in Android's settings. A [sound theme](themes.md) can replace them with sounds of its own.
*   **Swap top and bottom dots.** In braille keyboard settings, **Swap top and bottom dots** makes dot 1 trade places with dot 3, and dot 4 with dot 6. **Reverse dots** is now called **Swap left and right dots**. You can turn on both.
*   **Skip the tutorial.** The first page of the braille keyboard tutorial has a **Skip tutorial** button, which opens the keyboard right away.
*   **Better haptics.** The braille keyboard uses the same crisp vibration effects as the rest of Backtalk. Submitting text feels the same as closing or switching the keyboard. Deleting in an empty field gives a soft vibration that fades out, so that you know there was nothing to delete.
*   **Navigation stays in the text field.** If you swiped to another control while the braille keyboard opened, moving by character, word, or line read that control instead of the text field. The braille keyboard now moves Backtalk's focus back to the text field before each command.
*   **Dots follow how you hold the device.** The braille keyboard keeps the dots under your fingers whichever way round you hold the device, with auto-rotate on or off. TalkBack expected the charging port on one side when auto-rotate was off, so holding the phone the other way round typed dot 4 for dot 1. Backtalk remembers how you last held the device up, the whole time the screen is on, as auto-rotate does. In screen-away mode, the dots follow how you hold the device. In tabletop mode, laying the device flat keeps the side you held it up with, even from before the keyboard opened, and turning it on the table turns the dots with it. The keyboard says where the charging port is when the mode changes and after each turn, such as "Tabletop mode, charging port on the left". On a phone, the dots turn by half turns, as the port can only be on your left or right. On a tablet or an unfolded foldable, they turn by quarter turns, including upside down, which auto-rotate does not do.
    *   **Lock the orientation.** Hold dots 4, 5 and 6 and swipe up to keep the dots facing the way they are, and do it again to let them follow the device. There is a lock for each screen size and mode, so a foldable can be locked one way folded and another way unfolded.
    *   **Limitations.** Gravity cannot tell whether a device held up faces you or faces away. Laid flat after being held up, a phone takes it that you held it screen away, and a tablet that you held it facing you. A phone opened flat that has not been held up since the screen came on, or was last held in portrait, still expects the port on the left; turning it around on the table, or locking it, corrects this. Turns on the table need the game rotation vector sensor; devices without it still follow how they are held up. A tablet only says where the port is if it is a portrait tablet with the port at the bottom. Remembering how the device is held keeps the accelerometer running while the screen is on, as auto-rotate does.

## Build

You need JDK 17 or newer, the Android SDK with platform 37, and NDK 27.3.13750724. The Gradle wrapper downloads the correct Gradle version, so you do not need to install Gradle.

### Linux or macOS

Set `ANDROID_SDK` to your SDK path, then run `./build.sh`. This produces an APK file.

### Windows

Set `ANDROID_HOME` to your SDK path, then run:

```
.\gradlew.bat assemblePhoneDebug
```

### Dependencies

Library and plugin versions are in `gradle/libs.versions.toml`. Renovate opens pull requests to update them each week, and GitHub Actions builds each pull request.

### Image descriptions with Gemini

Google's source release does not include the Gemini settings, so **Describe image** only reads text in images, and **Describe screen** does not work, unless you add your own Gemini API key. Backtalk adds its own support for Describe screen, including follow-up questions. To add a key:

1.  Open Backtalk settings, then **Automatic descriptions**, then **Gemini API key**.
2.  Choose **Get a key** to open [Google AI Studio](https://aistudio.google.com/apikey) and create a key.
3.  Paste the key into the field, and choose **Save**. It works at once.

To remove the key, clear the field and save.

If you build Backtalk yourself, you can also build a key into the APK instead. Add `gemini.api.key=YOUR_KEY` to `local.properties` in the project folder, then build and install Backtalk again. A key entered in settings takes the place of the built-in key. Git ignores `local.properties`, so your key is not committed, but do not share an APK that contains your key. To use a different model, add `gemini.model=MODEL_NAME`. The default is `gemini-flash-latest`.

Images and screenshots that you describe are sent to Google. On the free tier, Google can use this data to improve its products.

### On-device AI

If you do not want to use an API key, or you have hit the free tier limit, Backtalk can describe images and screens with a Gemma 4 model that runs on your phone. Nothing is sent to Google or anyone else when you use it.

1.  Open Backtalk settings, then **Automatic descriptions**, then **On-device AI**.
2.  Choose a model. **Gemma 4 E2B** is the one to start with: 2.6 GB, and it needs a phone with about 6 GB of memory. **Gemma 4 E4B** is 3.7 GB, gives better answers, and needs about 8 GB. The list also has other small vision models from the [LiteRT community](https://huggingface.co/litert-community), from 0.4 GB up, so that you can try them. Those are marked experimental: they are community conversions that the Backtalk developers have not tried, and some may not work or may follow the screen description format badly. The list only shows models that your phone has enough memory for, plus any you already have. If your phone does not have enough memory for Gemma 4 E2B, Backtalk picks the largest model that fits.
3.  Choose **Download model**. It downloads once from [Hugging Face](https://huggingface.co/litert-community) and carries on where it stopped if the connection drops. A notification shows the progress. Backtalk checks the file against a known SHA-256 hash and deletes it if it does not match. Or choose **Use a model file from storage** to use a `.litertlm` file that you downloaded yourself, such as `gemma-4-E2B-it.litertlm` from `litert-community/gemma-4-E2B-it-litert-lm`. Backtalk works out which model it is.
4.  Turn on **Use on-device AI**.

You still need to turn on Gemini support in the Gemini settings, which switches on Describe image and Describe screen. After that, they use the model on your phone. Turn **Use on-device AI** off to go back to the Gemini API.

Answers take several seconds and use battery, more than the cloud on a mid-range phone. The model loads on the first request and unloads after two idle minutes to free memory. The model runs in its own process, so if the phone runs out of memory, Android stops the model and not Backtalk, and Backtalk says so. Before it loads a model, Backtalk checks that the phone has at least as much free memory as the model's size, and if not, it says there is not enough free memory and does not load it. To test with a model that is too big for your phone, turn on **Ignore on-device AI memory limits** in **Developer settings**. It lists every model, lets you choose any downloaded one, and skips the free memory check. If answers fail or the phone slows down, try the other model, or turn **Use the GPU** on or off. On-device AI needs a 64-bit ARM phone and adds about 22 MB to the app.

## Install

Install the APK on your device with adb.

On Windows, `.\deploy.ps1` builds the APK and installs it with adb. This script needs PowerShell 7. To install the last build without building again, use `-SkipBuild`.

Backtalk installs as `fyi.quin.backtalk`, so it does not replace Google's TalkBack. The two apps have separate settings. Because its app ID is its own, Backtalk also installs on GrapheneOS and other ROMs that ship the AOSP TalkBack as a system app named `com.android.talkback`.

### Moving from the old app ID

Earlier builds of Backtalk installed as `com.android.talkback`. To move to the new app ID:

1.  Update as usual. The update installs the new Backtalk next to the old one, and the accessibility settings open.
2.  Turn on the new Backtalk when asked. Your settings and custom labels come along.
3.  The old Backtalk turns itself off, and a notification asks to remove it. Tap it to uninstall the old app.

On-device AI models are not carried over, so download them again in the new app. If you used the braille keyboard or an accessibility shortcut, turn them on again for the new Backtalk.

To make the switch fully automatic, grant the new app permission to change secure settings before you turn it on: `adb shell pm grant fyi.quin.backtalk android.permission.WRITE_SECURE_SETTINGS`. Then the new Backtalk turns the old one off, and moves the accessibility shortcut and the braille keyboard over, by itself.

## Updates

Each change to Backtalk is built on GitHub as a development build, on the [dev release](https://github.com/trypsynth/backtalk/releases/tag/dev) page. `backtalk.apk` is for phones and `backtalk-wear.apk` is for Wear OS watches, and each checks for updates of its own kind. The [latest release](https://github.com/trypsynth/backtalk/releases/tag/latest) holds the last build with the old app ID, which moves old installs to the new one. Backtalk checks for a new build when it starts and about once a day. When there is one, it shows a notification with the list of changes. Tap the notification to download and install the new build. The first time, Android asks you to allow Backtalk to install apps.

To check now, go to **Check for updates** in Backtalk settings. To stop the daily checks, turn off **Automatically check for updates**.

Android only installs an update that is signed with the same key as the installed app. If you build Backtalk yourself, your build is signed with your own debug key, so it cannot be updated by the builds from GitHub. To use them, uninstall your build first.

## Run

After you install Backtalk, go to **Settings > Accessibility**. Backtalk is listed as **Backtalk** and is off by default. Turn off Google's TalkBack first, then turn on Backtalk.

## Debug tools

Debug builds include tools to find lag:

*   The `BacktalkStall` logcat tag logs each time the main thread is blocked for more than 100 ms, with the code that was running.
*   The `BacktalkGesture` logcat tag logs touch state changes and each gesture that Backtalk detects.
*   Event processing has trace sections, which show in [Perfetto](https://perfetto.dev) system traces.
