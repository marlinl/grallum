package com.grallum.application.task;

import com.grallum.application.config.CleanupProperties;
import com.grallum.application.service.DockerCleanupService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Adapts scheduler ticks to cleanup use cases without accessing repositories or backends. */
@Component
public class DockerCleanupTask {
  private final DockerCleanupService service;
  private final CleanupProperties properties;

  /** Retains the cleanup use-case service; performs no work during construction. */
  public DockerCleanupTask(DockerCleanupService service, CleanupProperties properties) {
    this.service = service;
    this.properties = properties;
  }

  /** Executes enabled policies using the configured explicit scheduler time zone. */
  @Scheduled(cron = "${app.cleanup.cron:0 0 3 * * *}", zone = "${app.cleanup.zone}")
  public void execute() {
    if (properties.schedulingEnabled()) service.runScheduled();
  }

  /** Reconciles a bounded batch of interrupted tasks without resuming untouched deletions. */
  @Scheduled(
      fixedDelayString = "${app.cleanup.recovery-delay:60000}",
      initialDelayString = "${app.cleanup.recovery-delay:60000}")
  public void recover() {
    if (properties.schedulingEnabled()) service.recoverIncompleteRuns();
  }
}
