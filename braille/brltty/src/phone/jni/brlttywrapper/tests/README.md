# Focus Bluetooth idle refresh tests

`idle_keepalive_test.c` checks the three-second refresh deadline, the one-minute
inactivity cutoff, resuming input, and disabled/disconnected sessions.
`focus_refresh_test.c` includes the actual Freedom Scientific driver and replaces
only wire output, cell translation, and the alarm service. It checks packet
checksums, unchanged cell content, ACK ordering, using newer content while a
write is pending, split payloads, and write failure propagation.

From the repository root, on a POSIX development host with a C compiler:

```sh
w=braille/brltty/src/phone/jni/brlttywrapper
b="$w/third_party/brltty"
mkdir -p build/native-tests
cc -std=c99 -Wall -Wextra -Werror "$w/tests/idle_keepalive_test.c" \
  -o build/native-tests/idle_keepalive_test
cc -std=c99 -DHAVE_CONFIG_H -ffunction-sections -fdata-sections \
  -Wl,--gc-sections -I"$b" -I"$b/Programs" -I"$b/Headers" \
  -I"$b/for_talkback" -I"$w" "$w/tests/focus_refresh_test.c" \
  -o build/native-tests/focus_refresh_test
build/native-tests/idle_keepalive_test
build/native-tests/focus_refresh_test
```

For Android, use the NDK's clang instead of `cc`, add
`--target=aarch64-linux-android26`, and run the resulting executables in
`/data/local/tmp/` using ADB. This is the path verified on a Samsung SM-X730.
The tests do not access Bluetooth or change accessibility settings.

These tests verify code behaviour, not the effect on physical refresh latency.
Hardware validation still needs to compare ordinary reading pauses with and
without the candidate and check for sustained active Bluetooth mode, correct
dots, unchanged key handling, and idle power use. The first pan after a minute
without input can still incur the original wake-up delay.
