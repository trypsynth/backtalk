# Czech translation status

This file is the continuity anchor for the Czech localization of BackTalk.

## Repositories and branches

- Upstream project: `trypsynth/backtalk`
- Upstream branch: `master`
- Czech fork: `matejplch626-afk/backtalk`
- Working branch: `czech-translation`
- Last upstream commit checked: `902bf7c14c1e04e591a3c1059f08116ef815e8c0` (`Speak each keyboard focus move once (#129)`, 2026-10-09)

## Localization strategy

BackTalk inherits the existing Czech translation from Google's TalkBack. Do not translate TalkBack from scratch. For BackTalk-specific strings, add Czech resources under the corresponding `values-cs` directory. Do not duplicate resource names. If BackTalk changes the meaning of an inherited key, edit the inherited Czech value in its original file. Preserve Android format placeholders, plurals, XML escaping, XLIFF/HTML markup and `translatable="false"` semantics exactly. Do not localize internal keys or `donottranslate` data. Keep user-facing product branding consistent as Backtalk. Validate resources with a Gradle/AAPT build before considering localization complete.

## Latest upstream watch

### 2026-10-09 — through `902bf7c14c1e04e591a3c1059f08116ef815e8c0`

Upstream advanced by 27 commits from `8219b85b193ec9fec37e4b715d064247ed0a232e`. Localization-relevant changes include voice profiles (#111), renamed tutorial setting paths (#120), revised Gemini API consent text (#126), table-reading verbosity settings (#103), and several smaller Backtalk strings. Czech translations were added for the new voice-profile UI, seven table-reading strings, four revised Gemini API/terms strings, and six tutorial strings whose English meaning/path changed. Existing upstream Czech additions in this range (battery/charger wording, navigation-bar buttons, typing-method confirmation and other small settings) were reviewed and not duplicated. XLIFF placeholders and XML escaping were preserved.

## Important Czech maintenance commits

- `276effcbb6825c13dc222706c2d4a74a5c7fd37f` — initial BackTalk-specific Czech strings
- `69d6f06647f92ad8ccfadfcb9eb67ed808809d88` — corrected Czech validation workflow
- `169a66615d7cc65de4b11c3db6605d35043769b8` — six Braille keyboard vibration controls
- `4b87efbc611b8780f801a101f10385e9b16af417` — emoji speech and repeated-emoji settings
- `a93641b0a5ccc063c00bb41293dc0a011f15cfab` — removed obsolete Czech resources removed upstream
- `3bb8318d7a479f8392cb4ae724cfabcf78f08188` — Czech Vibration Watch gesture strings
- `917135a9200c7bfdb44d4d4a65bc5b97689f6e60` — voice profiles, table reading, Gemini consent and tutorial-path updates

## Maintenance workflow

For every new upstream commit/release: compare from the recorded checkpoint; inspect English translatable resources in TalkBack and Braille for added, removed and meaning-changed keys; apply only required Czech changes; preserve placeholders/XLIFF/plurals/markup/XML semantics; update this checkpoint; validate/build; and submit a small focused upstream PR when Czech localization changes are required. If no Czech localization changes are required, update only this checkpoint and do not create a PR.

## Testing and contribution policy

Prefer small reviewable pull requests. Czech localization is maintained continuously by Matěj Plch, a native Czech daily screen-reader user. Test APKs are produced by the Czech audit/build workflow; runtime-test new or changed strings on Android when applicable. Upstream developers need not maintain Czech themselves; focused follow-up PRs are submitted as English user-facing strings change.
