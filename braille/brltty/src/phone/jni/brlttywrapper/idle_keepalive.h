/* Copyright 2026 The Backtalk Authors. Licensed under the Apache License, Version 2.0. */
#ifndef BACKTALK_IDLE_KEEPALIVE_H
#define BACKTALK_IDLE_KEEPALIVE_H

#include <stdint.h>

/* Below the five-second RFCOMM idle timeout observed on Samsung. Only user
 * commands extend the reading session; acknowledgements and refreshes do not. */
#define IDLE_KEEPALIVE_INTERVAL_MS 3000
#define IDLE_KEEPALIVE_SESSION_MS 60000

typedef struct {
  int enabled;
  int active;
  int64_t lastInput;
  int64_t lastWrite;
} IdleKeepalive;

static inline void idleKeepaliveInput(IdleKeepalive *state, int64_t now) {
  if (!state->enabled) return;
  if (!state->active) state->lastWrite = now;
  state->active = 1;
  state->lastInput = now;
}

static inline int idleKeepaliveDelay(IdleKeepalive *state, int64_t now) {
  if (!state->enabled || !state->active) return -1;
  int64_t remaining = IDLE_KEEPALIVE_SESSION_MS - (now - state->lastInput);
  if (remaining <= 0) {
    state->active = 0;
    return -1;
  }
  int64_t delay = IDLE_KEEPALIVE_INTERVAL_MS - (now - state->lastWrite);
  if (delay < 0) delay = 0;
  return (int)(delay < remaining ? delay : remaining);
}

#endif
