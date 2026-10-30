# Contributing to Backtalk

Thanks for wanting to help. Backtalk is maintained by one person in their spare time, and every pull request is read and reviewed by hand. These guidelines keep that possible.

## Pull requests

*   Open a few at a time. You can have up to three pull requests open at once, drafts included, and GitHub won't let you open a fourth until one of them is merged or closed. Wait for review rather than working around the limit. Opening a batch of pull requests at once, with an AI agent or by hand, buries the maintainer and everyone else waiting for review. It's disrespectful of their time and won't be tolerated: batches like that are closed without review.
*   One change per pull request. A pull request should do one thing, and its title should say what. Don't mix a fix with unrelated cleanup or another feature.
*   Say why. Describe the problem you're fixing and how you found it, such as an issue, a log, or a device and app where it happens, and say how you tested the change.
*   Test on a device. Unit tests and lint aren't enough for a screen reader. Say which phone or watch, Android version and speech engine you tried it on, and what you did. If you couldn't try something, say so.
*   Answer review. If you're asked for changes, make them or explain why not. Pull requests that go two weeks without an answer to review are closed.
*   Open an issue first for big changes, such as a new feature or a change to how navigation, gestures or speech work, so that we can agree on the approach before you write it.

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
