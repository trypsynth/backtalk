/* Copyright 2026 The Backtalk Authors. Licensed under the Apache License, Version 2.0. */
#include <assert.h>
#include <stdio.h>
#include "../idle_keepalive.h"

int main(void) {
  IdleKeepalive state = {0};
  idleKeepaliveInput(&state, 1000);
  assert(idleKeepaliveDelay(&state, 5000) == -1); // Other transports/drivers.

  state.enabled = 1;
  assert(idleKeepaliveDelay(&state, 5000) == -1); // No input since connecting.
  idleKeepaliveInput(&state, 10000);
  assert(idleKeepaliveDelay(&state, 10000) == 3000);
  assert(idleKeepaliveDelay(&state, 12999) == 1);
  assert(idleKeepaliveDelay(&state, 13000) == 0);
  state.lastWrite = 13000;
  assert(idleKeepaliveDelay(&state, 13000) == 3000);

  // Reading pauses longer than five seconds still get a refresh every three.
  for (int64_t time = 16000; time < 70000; time += 3000) {
    assert(idleKeepaliveDelay(&state, time) == 0);
    state.lastWrite = time;
  }
  assert(idleKeepaliveDelay(&state, 69999) == 1);
  assert(idleKeepaliveDelay(&state, 70000) == -1); // Refreshes do not renew input.

  // Reading resumes after a long break; no immediate stale refresh is sent.
  idleKeepaliveInput(&state, 200000);
  assert(idleKeepaliveDelay(&state, 200000) == 3000);
  idleKeepaliveInput(&state, 202000);
  assert(idleKeepaliveDelay(&state, 203000) == 0);
  state.lastWrite = 203000;
  assert(idleKeepaliveDelay(&state, 260000) == 0);
  state.lastWrite = 260000;
  assert(idleKeepaliveDelay(&state, 261999) == 1);
  assert(idleKeepaliveDelay(&state, 262000) == -1);

  // Disconnect/reconnect must not inherit an active session.
  state = (IdleKeepalive){.enabled = 1};
  assert(idleKeepaliveDelay(&state, 300000) == -1);
  puts("idle_keepalive_test: passed");
  return 0;
}
