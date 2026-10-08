# Czech translation status

This file is the continuity anchor for the Czech localization of BackTalk.

## Repositories and branches

- Upstream project: `trypsynth/backtalk`
- Upstream branch: `master`
- Czech fork: `matejplch626-afk/backtalk`
- Working branch: `czech-translation`
- Last upstream commit checked: `4b7020c09e01883955233eab631f838dd6be1384` (`Update Czech localization for build 221 (#109)`, 2026-10-08)

## Localization strategy

BackTalk inherits a large existing Czech translation from Google's TalkBack. Do not translate TalkBack from scratch.

For BackTalk-specific strings:

1. Add new Czech resources under the corresponding `values-cs` directory.
2. Prefer separate feature-specific files when the resource key is new.
3. Do not duplicate an existing Czech resource name in a supplementary file.
4. If BackTalk changes the meaning of an existing TalkBack resource key, edit the inherited Czech resource value in its original Czech file instead of creating a duplicate.
5. Preserve Android format placeholders, plurals, XML escaping, XLIFF markup, and `translatable="false"` semantics exactly.
6. Do not localize internal preference keys or other `donottranslate` data.
7. Validate resources with an actual Gradle/AAPT build before considering the localization complete.

## Czech files added or extended for BackTalk

### Main TalkBack app

Under `talkback/src/main/res/values-cs/`:

- `strings_backtalk.xml` — low-latency audio, automatic language/dialect switching, screen announcements, lift-to-activate, wrap-around navigation, rotor gestures, proximity speakerphone behavior, audio routing, TTS options, long-text sentence mode, incoming notifications
- `strings_control_sounds.xml` — control sounds/vibrations, 3D audio, control sound labels
- `strings_sound_themes.xml` — sound themes, install/remove/export, custom sounds/vibrations, Braille keyboard sounds
- `strings_direct_touch.xml` — Direct touch and app-specific direct typing
- `strings_on_device_ai.xml` — on-device AI model UI/errors
- `strings_pause.xml` — pause/resume BackTalk
- `strings_radial_menu.xml` — circle/radial menu
- `strings_update.xml` — built-in updater
- `strings_gemini_api_key.xml` — Gemini API key settings
- `strings_gemini_errors.xml` — Gemini API/quota errors
- `strings_individual_feedback.xml` — individual sounds/vibrations, including six independently switchable Braille keyboard vibrations added upstream in #89
- `strings_emoji.xml` — emoji speech mode and repeated-emoji controls added upstream in #72

### Braille IME

Under `braille/brailleime/src/phone/res/values-cs/`, `strings_backtalk.xml` covers orientation announcements/settings, Braille keyboard typing sounds/echo, vertical dot swap, and skip tutorial.

### Braille common

Under `braille/common/src/phone/res/values-cs/`, `strings_backtalk.xml` covers the orientation-lock gesture label.

## Important commits in the Czech branch

- `276effcbb6825c13dc222706c2d4a74a5c7fd37f` — initial BackTalk-specific Czech strings
- `885c622875751031eb4cd52e5233da6779a22eb9` — initial Braille orientation additions
- `9104fa801add12524d7fa98ed998c6afb19c17e3` — lift activation, wrap-around, rotor gestures, call routing
- `b823d08891ff36c71329a69ff89b70f2639a2aae` — speech and notification settings
- `69d6f06647f92ad8ccfadfcb9eb67ed808809d88` — corrected Czech validation workflow; successful audit/build run followed
- `169a66615d7cc65de4b11c3db6605d35043769b8` — Czech labels for six new Braille keyboard vibration controls from upstream #89
- `4b87efbc611b8780f801a101f10385e9b16af417` — Czech strings for the new emoji speech and repeated-emoji settings from upstream #72
- `a93641b0a5ccc063c00bb41293dc0a011f15cfab` — removed three obsolete Czech Backtalk strings removed upstream

## Upstream watch log

### 2026-10-08 — through `4b7020c09e01883955233eab631f838dd6be1384`

Upstream advanced by 3 commits from `7b7df8489033130cbb788e8c7258c1b755f3132f`. Two commits changed internal speech/ringer handling without changing translatable TalkBack/Braille English resources. The third commit merged our Czech localization PR #109 (`Update Czech localization for build 221`), changing only Czech resources already supplied by this localization project. No new Czech translation work or follow-up PR is required.

### 2026-10-08 — through `7b7df8489033130cbb788e8c7258c1b755f3132f`

Upstream advanced by 16 commits from `694af2bebd957cb90d5c70af6c425b7beeaad4f4`. Most changes were code, build/CI/dependency maintenance, touch-exploration performance, call speech flags, and lift-to-activate behavior. No new translatable TalkBack/Braille resource keys or changed English meanings requiring new Czech wording were found. Commit `d3bbe47ebbd7ba8dd84fb884a11da3e0dfddf5a3` removed three Backtalk-added English resources that were no longer used (`value_audio_output_device_none_connected`, `value_audio_output_device_aux_line`, and `pref_category_selector_menu_summary_no_rotor`), so their Czech counterparts were removed as well.

### 2026-10-07 — through `694af2bebd957cb90d5c70af6c425b7beeaad4f4`

Upstream advanced by 2 commits from `348917379a54f31fda51f6b1d413af89062af5d5`. Commit #94 changed only on-device AI request-state handling in Kotlin code. The following commit removed four lines from `differences.md` describing Direct touch limitations. Neither commit changed TalkBack/Braille translatable resources or existing English resource meanings, so no Czech localization changes were required.

### 2026-10-07 — through `348917379a54f31fda51f6b1d413af89062af5d5`

Upstream advanced by 4 commits from `5e2fae60d1c2ed3f057db938ee9b46268a82daf4`. Commit #72 added a user-visible Emoji setting with three speech modes, a reading-control/menu state announcement, and a repeated-emoji threshold setting. Thirteen Czech strings were added in `strings_emoji.xml`, preserving the `%1$s` XLIFF placeholder in `emoji_speech_state`. The other three commits changed Gemini/audio behavior, Braille tabletop orientation logic, and x86_64 build support without adding translatable user-visible resources.

### 2026-10-07 — through `5e2fae60d1c2ed3f057db938ee9b46268a82daf4`

Upstream advanced by 13 commits from the previous checkpoint `f4cdc0d3f46f4f6a946824ee7b24c236c6487f1e`. Most changes were code, CI, dependencies, documentation, TV behavior, or non-translatable resources. Commit #89 added six new user-visible English string resources for independently controlling Braille keyboard vibrations. Czech translations were added for all six. No placeholders, XLIFF markup, or plurals are involved in these six strings.

New Czech labels:

- Braille keyboard: typing a character → `Braillská klávesnice: zadání znaku`
- space or delete → `Braillská klávesnice: mezera nebo mazání`
- new line or deleting a word → `Braillská klávesnice: nový řádek nebo smazání slova`
- holding fingers down → `Braillská klávesnice: přidržení prstů`
- other gestures → `Braillská klávesnice: ostatní gesta`
- nothing to delete → `Braillská klávesnice: není co smazat`

## Completed BackTalk-specific areas

The Czech branch includes translations for sound themes, control sounds/vibrations, 3D audio, custom sounds, screen/tabletop orientation, audio routing, rotor behavior, lift-to-activate, wrap-around navigation, proximity speakerphone behavior, Direct touch, on-device AI, Gemini settings/errors, Pause BackTalk, circle menu, updater, Braille keyboard additions, TTS options, sentence-at-a-time mode, incoming notifications, individual Braille keyboard vibration controls, and emoji speech controls.

## Audit and build status

GitHub Actions workflow `Czech translation audit and build` completed successfully in run #18 on 2026-10-07. It audited changed existing English keys, checked duplicate Czech resource names, validated XML and simple-string placeholders, completed the Gradle debug build, and produced APK artifacts. A new validation run is expected after localization maintenance commits.

## Test APK status

Successful test artifacts have been produced by the Czech audit/build workflow, including a phone debug APK and Wear build. BackTalk application ID is `fyi.quin.backtalk`, so it is expected to install alongside system/Google TalkBack as a separate accessibility service. Published debug builds use a debug signing key.

Runtime test sequence: install the phone APK, keep system TalkBack enabled initially, confirm BackTalk appears separately in Android accessibility settings, then begin controlled Czech localization testing.

## Remaining work before declaring 100% coverage

1. Runtime-test Czech strings on a real Android device.
2. Correct terminology, truncation, missing strings, malformed announcements, or feature-specific issues found in testing.
3. Re-run validation/build after final corrections and after syncing current upstream code.
4. Prepare the initial professional upstream PR to `trypsynth/backtalk:master`.

## Maintenance workflow

For every new upstream commit/release: compare from the recorded checkpoint; inspect English translatable resources in TalkBack/Braille; translate new keys; audit changed existing keys; preserve placeholders/XLIFF/plurals; commit focused Czech changes; update this checkpoint; validate/build when the branch contains the corresponding upstream code; and submit small upstream PRs once coherent and tested.

## Upstream contribution policy

Prefer small, reviewable pull requests rather than a permanently open mega-PR. After upstream accepts the initial Czech localization, subsequent BackTalk features should normally be delivered as focused Czech translation updates tied to relevant upstream changes.

## Initial professional upstream PR plan

Do not open the first upstream PR until the Czech localization has passed real-device testing and remaining audit findings have been reviewed. The PR should explain that BackTalk inherits Google's Czech TalkBack localization; this contribution adds/updates BackTalk-specific Czech strings; inherited Czech strings change only where English meaning/branding changed; Android formatting semantics were preserved; resources passed automated validation/build; and localization was tested by a native Czech daily screen-reader user.

Matěj Plch is willing to act as the Czech localization maintainer/contact. Upstream developers need not translate Czech themselves; focused Czech follow-up PRs can be submitted when English user-facing strings change.

Suggested wording:

> Czech localization is maintained continuously. I am a native Czech speaker and daily screen reader user. I can keep Backtalk-specific Czech strings updated as new features are added. You don't need to maintain the Czech translations yourself; I will submit small follow-up pull requests whenever English user-facing strings change.
