package com.grallum.application.model;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Single-use backend response that owns its body and optional repository lease. */
public final class DockerResponse implements AutoCloseable {
  private static final ScheduledThreadPoolExecutor DEADLINES = deadlines();
  private final int status;
  private final Map<String, List<String>> headers;
  private final InputStream body;
  private DockerRepositoryLease lease;
  private boolean closed;

  /** Retains response data; ownership of body transfers to this response. */
  public DockerResponse(int status, Map<String, List<String>> headers, InputStream body) {
    this(status, headers, body, null);
  }

  /**
   * Retains a backend response with an optional body deadline; timer expiry closes only the stream.
   */
  public DockerResponse(
      int status, Map<String, List<String>> headers, InputStream body, Duration timeout) {
    this.status = status;
    Map<String, List<String>> copy = new LinkedHashMap<>();
    headers.forEach((key, values) -> copy.put(key, List.copyOf(values)));
    this.headers = Map.copyOf(copy);
    this.body = timeout == null ? Objects.requireNonNull(body) : new DeadlineStream(body, timeout);
  }

  /** Creates a daemon deadline executor whose cancelled tasks are removed immediately. */
  private static ScheduledThreadPoolExecutor deadlines() {
    var executor =
        new ScheduledThreadPoolExecutor(
            1, Thread.ofPlatform().daemon().name("docker-body-deadline").factory());
    executor.setRemoveOnCancelPolicy(true);
    return executor;
  }

  /** Bounds stream reads while leaving repository-lease release to the owning request thread. */
  private static final class DeadlineStream extends FilterInputStream {
    private final AtomicBoolean expired = new AtomicBoolean();
    private final ScheduledFuture<?> deadline;

    /** Schedules closure of only the backend body, never a thread-owned repository lease. */
    private DeadlineStream(InputStream body, Duration timeout) {
      super(Objects.requireNonNull(body));
      deadline =
          DEADLINES.schedule(
              () -> {
                expired.set(true);
                try {
                  body.close();
                } catch (IOException ignored) {
                }
              },
              Math.max(1, timeout.toMillis()),
              TimeUnit.MILLISECONDS);
    }

    /** Reads one byte and reports deadline expiry even if closing the backend returns EOF. */
    @Override
    public int read() throws IOException {
      checkDeadline();
      int value = in.read();
      checkDeadline();
      return value;
    }

    /**
     * Reads a bounded chunk and rejects an expired stream instead of reporting successful
     * truncation.
     */
    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      checkDeadline();
      int count = in.read(bytes, offset, length);
      checkDeadline();
      return count;
    }

    /**
     * Copies through deadline-checked reads without bypassing the wrapper via FilterInputStream.
     */
    @Override
    public long transferTo(OutputStream output) throws IOException {
      byte[] buffer = new byte[65536];
      long total = 0;
      int count;
      while ((count = read(buffer)) >= 0) {
        output.write(buffer, 0, count);
        total += count;
      }
      return total;
    }

    /** Cancels pending expiry and closes the backend stream; safe for repeated close calls. */
    @Override
    public void close() throws IOException {
      deadline.cancel(false);
      in.close();
    }

    /** Turns timer expiry into an observable I/O failure. */
    private void checkDeadline() throws IOException {
      if (expired.get())
        throw new java.net.SocketTimeoutException("Registry body deadline exceeded");
    }
  }

  /** Returns the backend status; pure. */
  public int status() {
    return status;
  }

  /** Returns immutable headers; values originate from immutable backend headers. */
  public Map<String, List<String>> headers() {
    return headers;
  }

  /** Returns the owned one-shot stream; consuming it has transport side effects. */
  public InputStream body() {
    return body;
  }

  /** Transfers an acquired lease to this response; caller then closes only this response. */
  public DockerResponse own(DockerRepositoryLease value) {
    if (lease != null) throw new IllegalStateException("Response already owns a lease");
    lease = value;
    return this;
  }

  /**
   * Closes backend transport and always releases the lease once; suppresses close-only I/O
   * failures.
   */
  @Override
  public void close() {
    if (closed) return;
    closed = true;
    try {
      body.close();
    } catch (IOException ignored) {
    } finally {
      if (lease != null) lease.close();
    }
  }
}
