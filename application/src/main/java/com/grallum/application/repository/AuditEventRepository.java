package com.grallum.application.repository;

import com.grallum.application.entity.*;
import com.grallum.application.model.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Parameterized persistence for AuditEventRepository; no HTTP or authorization decisions. */
@Repository
public class AuditEventRepository {
  private final JdbcClient db;

  /** Retains the JDBC executor; construction performs no queries. */
  public AuditEventRepository(JdbcClient db) {
    this.db = db;
  }

  /** Appends a sanitized business audit record in the caller's short transaction. */
  public void insert(AuditEventEntity event) {
    db.sql(
            "insert into audit_event(occurred_at,actor,action,target,detail)"
                + " values(:at,:actor,:action,:target,:outcome)")
        .param("at", Timestamp.from(event.occurredAt()))
        .param("actor", event.actor())
        .param("action", event.action())
        .param("target", event.target())
        .param("outcome", event.outcome())
        .update();
  }
}
