package com.grallum.application.service;

import static org.junit.jupiter.api.Assertions.*;

import com.grallum.application.exception.BusinessException;
import com.grallum.application.model.*;
import java.io.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Verifies request allowlists and per-repository response ownership without a running backend. */
class DockerBoundaryTest {
  private final DockerRepositoryRef repository =
      new DockerRepositoryRef(
          1, new DockerRepositoryName("private", "team/image"), DockerRegistryMode.PRIVATE);

  /** Constructs a typed upload request whose raw credentials must never reach the registry. */
  private DockerRequest upload() {
    return new DockerRequest(
        repository.name(),
        DockerOperation.UPLOAD_START,
        DockerHttpMethod.POST,
        Optional.empty(),
        Optional.empty(),
        Map.of(
            "mount",
            List.of("sha256:" + "a".repeat(64)),
            "from",
            List.of("private/source"),
            "_state",
            List.of("a+b/c=")),
        Map.of(
            "Authorization",
            List.of("Bearer secret"),
            "Cookie",
            List.of("secret"),
            "Content-Type",
            List.of("application/octet-stream")),
        InputStream.nullInputStream());
  }

  /**
   * Cross-repository mount requests fall back safely and opaque state is encoded once without
   * credential forwarding.
   */
  @Test
  void filtersCredentialsAndMounts() {
    var request =
        DockerStorageService.build(
            URI.create("http://localhost:5000"), upload(), Duration.ofSeconds(1));
    assertTrue(request.headers().firstValue("Authorization").isEmpty());
    assertTrue(request.headers().firstValue("Cookie").isEmpty());
    assertEquals("_state=a%2Bb%2Fc%3D", request.uri().getRawQuery());
    assertEquals("/v2/team/image/blobs/uploads/", request.uri().getPath());
  }

  /** A repository mismatch fails before backend I/O. */
  @Test
  void rejectsRepositoryMismatch() {
    assertThrows(
        BusinessException.class,
        () ->
            DockerStorageService.validate(
                new DockerRepositoryRef(
                    2, new DockerRepositoryName("private", "other"), DockerRegistryMode.PRIVATE),
                upload()));
  }

  /**
   * Closing a response closes its stream and releases its lease, including repeated close calls.
   */
  @Test
  void responseOwnsLease() throws Exception {
    var service = new DockerRepositoryLockService();
    var lease = service.acquire(1, Duration.ofSeconds(1));
    var closed = new java.util.concurrent.atomic.AtomicBoolean();
    var stream =
        new ByteArrayInputStream(new byte[0]) {
          /** Records stream closure so the test can verify response ownership. */
          @Override
          public void close() {
            closed.set(true);
          }
        };
    var response = new DockerResponse(200, Map.of(), stream).own(lease);
    response.close();
    response.close();
    assertTrue(closed.get());
    try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      assertTrue(
          executor
              .submit(
                  () -> {
                    var acquired = service.tryAcquire(1);
                    acquired.ifPresent(DockerRepositoryLease::close);
                    return acquired.isPresent();
                  })
              .get());
    }
  }

  /** Shutdown rejects new leases instead of silently queueing requests behind stopped services. */
  @Test
  void shutdownRejectsNewWork() {
    var service = new DockerRepositoryLockService();
    service.stopAccepting();
    assertThrows(BusinessException.class, () -> service.acquire(1, Duration.ofSeconds(1)));
    assertTrue(service.tryAcquire(1).isEmpty());
  }

  /** A body deadline interrupts reading but leaves lock release on the owning request thread. */
  @Test
  void deadlineDoesNotReleaseAnotherThreadsLease() throws Exception {
    var locks = new DockerRepositoryLockService();
    var response =
        new DockerResponse(
                200, Map.of(), new ByteArrayInputStream(new byte[] {1}), Duration.ofMillis(10))
            .own(locks.acquire(5, Duration.ofSeconds(1)));
    try (response;
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      Thread.sleep(30);
      assertThrows(java.net.SocketTimeoutException.class, () -> response.body().read());
      assertFalse(executor.submit(() -> locks.tryAcquire(5).isPresent()).get());
    }
    try (var lease = locks.acquire(5, Duration.ofSeconds(1))) {
      assertNotNull(lease);
    }
  }
}
