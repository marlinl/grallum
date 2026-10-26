package com.grallum.application.service;

import com.grallum.application.entity.AuditEventEntity;
import com.grallum.application.model.AuditEvent;
import com.grallum.application.repository.AuditEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Business audit use case; persists only explicitly supplied safe fields. */
@Service
public class AuditService {
  private final AuditEventRepository events;

  /** Retains the audit repository; no I/O. */
  public AuditService(AuditEventRepository events) {
    this.events = events;
  }

  /** Persists an audit event in a short transaction, joining an existing mutation when present. */
  @Transactional
  public void record(AuditEvent event) {
    events.insert(
        new AuditEventEntity(
            event.occurredAt(), event.actor(), event.action(), event.target(), event.outcome()));
  }
}
