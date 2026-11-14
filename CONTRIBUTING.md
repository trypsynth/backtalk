# Contributing to Backtalk

Thanks for wanting to help. Backtalk is maintained by one person in their spare time, and every pull request is read and reviewed by hand. These guidelines keep that possible.

## What Backtalk takes

Backtalk is meant to be TalkBack, but faster, with a few things put back that TalkBack lost. It's close to where it should be, so most of what goes in is:

*   Fixes, including for problems TalkBack has too.
*   Changes that make Backtalk faster or more responsive, measured on a device.
*   Behaviour from earlier TalkBack releases, such as the circle menu, and Google features that are in the source but don't work in it, such as voice commands and Describe screen.
*   Small improvements to things TalkBack already does.
*   Translations.

New features that TalkBack has never had are rarely taken, especially ones that add settings screens or gestures, or that change how touch, focus or speech work. Many of them can be written as a [script](docs/scripting.md) instead, and changes to the scripting API that make that possible are welcome. If you'd like a screen reader that takes more new features, [SandFly](https://github.com/aaron-gh/sandfly) is a fork of Backtalk that does.

## Pull requests

*   Open a few at a time. You can have up to three pull requests open at once, drafts included, and GitHub won't let you open a fourth until one of them is merged or closed. Wait for review rather than working around the limit. Opening a batch of pull requests at once, with an AI agent or by hand, buries the maintainer and everyone else waiting for review. It's disrespectful of their time and won't be tolerated: batches like that are closed without review.
*   One change per pull request. A pull request should do one thing, and its title should say what. Don't mix a fix with unrelated cleanup or another feature.
*   Keep it small. If a change needs hundreds of lines of code, or edits to many of Google's files, that usually means it should be split, done another way, or written as a script. Tests and documentation don't count toward this.
*   Say why. Describe the problem you're fixing and how you found it, such as an issue, a log, or a device and app where it happens, and say how you tested the change.
*   Test on a device. Unit tests and lint aren't enough for a screen reader. Say which phone or watch, Android version and speech engine you tried it on, and what you did. If you couldn't try something, say so.
*   Measure speed changes. If a change makes something faster, time it on master and on your branch, on a device, and give both numbers. Changes that should be faster but weren't measured aren't taken.
*   Answer review. If you're asked for changes, make them or explain why not. Pull requests that go two weeks without an answer to review are closed.
*   Agree on big changes first. For a new feature, or a change to how navigation, gestures or speech work, open an issue and wait until the approach is agreed there before you write it. An issue opened at the same time as the pull request doesn't count. Pull requests for features that weren't agreed may be closed without review.

## Using AI tools

You can use AI tools to help write your changes. But you're responsible for the result, exactly as if you'd written it all yourself:

*   You understand every line you're submitting, and why it's needed.
*   You can explain the change, and answer questions about it, in your own words. "The AI wrote it" isn't an answer.
*   You've read the code it changes, and checked that the description matches what the diff really does.
*   You've tried it on a device yourself.

Pull requests whose authors can't explain them are closed.

## Code

*   Keep changes to Google's files small. Most of the code comes from TalkBack. Put Backtalk's own code in files of its own where you can, and keep edits to Google's files to what's needed, so that later TalkBack updates merge cleanly.
*   New behaviour is off by default, behind a switch, unless it fixes something that is clearly broken. Users shouldn't find that Backtalk works differently after an update without having chosen it.
*   Describe Backtalk's own behaviour in [differences.md](differences.md), in plain words a user can follow.
*   Add tests for logic that can be tested without a device.
*   Keep work off the main thread, such as reading the screen's views, network requests and file access, wherever it doesn't have to be there. The main thread handles every gesture and every utterance.
*   Respect the minimum Android version. Backtalk runs from Android 8 (API 26). Check the version before calling newer APIs.
*   Before you push, run `./gradlew testPhoneDebugUnitTest :lintPhoneDebug :lintWearDebug`, and make sure all of it passes.

## Strings and translations

*   Each new string shown to the user gets a translator comment that says where it appears and what it means, with a `[CHAR LIMIT=…]`.
*   Add each new string's name to the `MissingTranslation` list in [lint.xml](lint.xml), as `<ignore regexp="name" />`, so that lint doesn't fail until it's translated.
*   Never regenerate `lint-baseline.xml`. It rewrites tens of thousands of lines, conflicts with every other pull request, and hides new lint errors along with the old ones.
*   In translations, the app's name is spelled Backtalk, with a lowercase t.

## Commits

Write commit messages that say what the change does for the user, in plain words, such as "Speak the new state at once when a switch is activated". Keep the first line short, and explain why in the body when it isn't obvious.
