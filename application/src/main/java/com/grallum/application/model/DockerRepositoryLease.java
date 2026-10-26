package com.grallum.application.model;

import java.util.concurrent.locks.ReentrantLock;

/** Single-thread repository lock ownership with idempotent release. */
public final class DockerRepositoryLease implements AutoCloseable {
  private final ReentrantLock lock;
  private boolean closed;

  /** Retains an already-held lock; caller must close on the owning thread. */
  public DockerRepositoryLease(ReentrantLock lock) {
    this.lock = lock;
  }

  /** Releases the held lock exactly once; no database or network I/O. */
  @Override
  public void close() {
    if (!closed) {
      lock.unlock();
      closed = true;
    }
  }
}
