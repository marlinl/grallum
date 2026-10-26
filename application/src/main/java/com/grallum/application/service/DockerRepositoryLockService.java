package com.grallum.application.service;

import com.grallum.application.exception.*;
import com.grallum.application.model.DockerRepositoryLease;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Service;

/** Shared process-local coordination for the supported single application instance. */
@Service
public class DockerRepositoryLockService {
  private final ConcurrentHashMap<Long, ReentrantLock> locks = new ConcurrentHashMap<>();
  private volatile boolean accepting = true;

  /** Acquires a bounded, interruptible repository lease; does not contact storage. */
  public DockerRepositoryLease acquire(long repositoryId, Duration timeout) {
    if (!accepting)
      throw new BusinessException(BusinessErrorCode.UNAVAILABLE, "Application is stopping");
    var lock = locks.computeIfAbsent(repositoryId, id -> new ReentrantLock());
    try {
      if (!lock.tryLock(timeout.toNanos(), TimeUnit.NANOSECONDS))
        throw new BusinessException(BusinessErrorCode.UNAVAILABLE, "Repository is busy");
      if (!accepting) {
        lock.unlock();
        throw new BusinessException(BusinessErrorCode.UNAVAILABLE, "Application is stopping");
      }
      return new DockerRepositoryLease(lock);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new BusinessException(BusinessErrorCode.CANCELLED, "Request interrupted");
    }
  }

  /** Attempts a lease without waiting; empty means another operation owns this repository. */
  public Optional<DockerRepositoryLease> tryAcquire(long repositoryId) {
    if (!accepting) return Optional.empty();
    var lock = locks.computeIfAbsent(repositoryId, id -> new ReentrantLock());
    if (!lock.tryLock()) return Optional.empty();
    if (!accepting) {
      lock.unlock();
      return Optional.empty();
    }
    return Optional.of(new DockerRepositoryLease(lock));
  }

  /**
   * Rejects new work during shutdown; existing leases remain owned until their callers close them.
   */
  @PreDestroy
  public void stopAccepting() {
    accepting = false;
  }
}
