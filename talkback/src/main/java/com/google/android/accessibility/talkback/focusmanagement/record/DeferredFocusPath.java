/* Copyright 2026 Backtalk contributors. Licensed under the Apache License, Version 2.0. */
package com.google.android.accessibility.talkback.focusmanagement.record;

import androidx.annotation.VisibleForTesting;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/** Completes recovery context only when restoration needs it, without speculative provider I/O. */
final class DeferredFocusPath {
  private final FutureTask<NodePathDescription> task;

  DeferredFocusPath(NodePathDescription identities) {
    this(identities::enrich);
  }

  @VisibleForTesting
  DeferredFocusPath(Callable<NodePathDescription> capture) {
    task = new FutureTask<>(capture);
  }

  NodePathDescription get() {
    // Ancestor identities and original labels were captured when focus moved. Sibling context
    // is only useful for recovery, so do not compete with real navigation for the app's provider.
    // Every record keeps its own task; none is replaced or discarded by a newer focus record.
    task.run();
    boolean interrupted = false;
    try {
      while (true) {
        try {
          return task.get();
        } catch (InterruptedException e) {
          interrupted = true;
        } catch (ExecutionException e) {
          throw new IllegalStateException("Unable to complete the saved focus path", e.getCause());
        }
      }
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
