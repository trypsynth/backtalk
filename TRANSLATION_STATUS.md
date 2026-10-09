# Czech translation status

This file is the continuity anchor for the Czech localization of BackTalk.

## Repositories and branches

- Upstream project: `trypsynth/backtalk`
- Upstream branch: `master`
- Czech fork: `matejplch626-afk/backtalk`
- Working branch: `czech-translation`
- Last upstream commit checked: `d984cac89a1415ef1225c5a6352dc0e123d79aac` (`Fix the default shown on the focus delay screens`, 2026-10-09)

## Localization strategy

BackTalk inherits the existing Czech translation from Google's TalkBack. Do not translate TalkBack from scratch. For BackTalk-specific strings, add Czech resources under the corresponding `values-cs` directory. Do not duplicate resource names. If BackTalk changes the meaning of an inherited key, edit the inherited Czech value in its original file. Preserve Android format placeholders, plurals, XML escaping, XLIFF/HTML markup and `translatable="false"` semantics exactly. Do not localize internal keys or `donottranslate` data. Keep user-facing product branding consistent as Backtalk. Validate resources with a Gradle/AAPT build before considering localization complete.

## Latest upstream watch

### 2026-10-09 — through `d984cac89a1415ef1225c5a6352dc0e123d79aac`

Upstream advanced by 5 commits from `dd3e2dd188a6b8469b296f960b9c57c192d80336`. The localization-relevant change adds three new user-visible choices for the order in which Backtalk announces an item's name, state and type: `pref_node_desc_order_entry_name_state_role_pos`, `pref_node_desc_order_entry_role_state_name_pos`, and `pref_node_desc_order_entry_state_role_name_pos`. Czech translations were added to `talkback/src/main/res/values-cs/strings_backtalk.xml`. These strings contain no placeholders, plurals, XLIFF or HTML markup. The remaining changes adjust item-detail behavior/settings placement and focus-delay defaults without adding or changing other translatable strings.

## Important Czech maintenance commits

- `276effcbb6825c13dc222706c2d4a74a5c7fd37f` — initial BackTalk-specific Czech strings
- `69d6f06647f92ad8ccfadfcb9eb67ed808809d88` — corrected Czech validation workflow
- `169a66615d7cc65de4b11c3db6605d35043769b8` — six Braille keyboard vibration controls
- `4b87efbc611b8780f801a101f10385e9b16af417` — emoji speech and repeated-emoji settings
- `a93641b0a5ccc063c00bb41293dc0a011f15cfab` — removed obsolete Czech resources removed upstream
- `3bb8318d7a479f8392cb4ae724cfabcf78f08188` — Czech Vibration Watch gesture strings

## Maintenance workflow

For every new upstream commit/release: compare from the recorded checkpoint; inspect English translatable resources in TalkBack and Braille for added, removed and meaning-changed keys; apply only required Czech changes; preserve placeholders/XLIFF/plurals/markup/XML semantics; update this checkpoint; validate/build; and submit a small focused upstream PR when Czech localization changes are required. If no Czech localization changes are required, update only this checkpoint and do not create a PR.

## Testing and contribution policy

Prefer small reviewable pull requests. Czech localization is maintained continuously by Matěj Plch, a native Czech daily screen-reader user. Test APKs are produced by the Czech audit/build workflow; runtime-test new or changed strings on Android when applicable. Upstream developers need not maintain Czech themselves; focused follow-up PRs are submitted as English user-facing strings change.
