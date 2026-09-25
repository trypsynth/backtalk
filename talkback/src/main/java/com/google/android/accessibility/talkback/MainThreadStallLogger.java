/*
 * Copyright 2026 Backtalk contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.Printer;

/**
 * Debug tool that logs the main thread stack while one message blocks it for too long. Filter
 * logcat by the "BacktalkStall" tag to find what TalkBack was waiting on during lag.
 */
final class MainThreadStallLogger implements Printer {
  private static final String TAG = "BacktalkStall";
  private static final long STALL_THRESHOLD_MS = 100;

  private final Thread mainThread = Looper.getMainLooper().getThread();
  private final Handler watchdog;
  private final Runnable logStack = this::logMainThreadStack;
  private long dispatchStartMs;

  private MainThreadStallLogger(Handler watchdog) {
    this.watchdog = watchdog;
  }

  static void install() {
    HandlerThread watchdogThread = new HandlerThread(TAG);
    watchdogThread.start();
    Looper.getMainLooper()
        .setMessageLogging(new MainThreadStallLogger(new Handler(watchdogThread.getLooper())));
  }

  @Override
  public void println(String message) {
    if (message.startsWith(">>>>>")) {
      dispatchStartMs = SystemClock.uptimeMillis();
      watchdog.postDelayed(logStack, STALL_THRESHOLD_MS);
    } else if (message.startsWith("<<<<<")) {
      watchdog.removeCallbacks(logStack);
      long blockedMs = SystemClock.uptimeMillis() - dispatchStartMs;
      if (blockedMs >= STALL_THRESHOLD_MS) {
        Log.w(TAG, "Main thread was blocked for " + blockedMs + " ms by " + message);
      }
    }
  }

  private void logMainThreadStack() {
    StringBuilder stack = new StringBuilder();
    for (StackTraceElement element : mainThread.getStackTrace()) {
      stack.append("\n  at ").append(element);
    }
    long blockedMs = SystemClock.uptimeMillis() - dispatchStartMs;
    Log.w(TAG, "Main thread blocked for " + blockedMs + " ms so far:" + stack);
    watchdog.postDelayed(logStack, STALL_THRESHOLD_MS);
  }
}
