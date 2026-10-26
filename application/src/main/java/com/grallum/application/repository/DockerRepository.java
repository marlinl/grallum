package com.grallum.application.repository;

import com.grallum.application.entity.*;
import com.grallum.application.model.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Parameterized persistence for DockerRepository; no HTTP or authorization decisions. */
@Repository
public class DockerRepository {
  private final JdbcClient db;

  /** Retains the JDBC executor; construction performs no queries. */
  public DockerRepository(JdbcClient db) {
    this.db = db;
  }

  /** Looks up a stable repository id; absent data returns empty. */
  public Optional<DockerRepositoryEntity> findById(long id) {
    return db.sql("select id,registry,name from repository where id=:id")
        .param("id", id)
        .query(DockerRepositoryEntity.class)
        .optional();
  }

  /** Looks up a canonical alias/name pair; performs a database read. */
  public Optional<DockerRepositoryEntity> findByName(DockerRepositoryName name) {
    return db.sql("select id,registry,name from repository where registry=:r and name=:n")
        .param("r", name.registry())
        .param("n", name.name())
        .query(DockerRepositoryEntity.class)
        .optional();
  }

  /** Inserts a repository selected by an authorized service; returns its generated id and name. */
  public DockerRepositoryEntity insert(String registry, String name) {
    return db.sql("insert into repository(registry,name) values(:r,:n) returning id,registry,name")
        .param("r", registry)
        .param("n", name)
        .query(DockerRepositoryEntity.class)
        .single();
  }

  /** Returns ordered visible rows using a service-validated identity and cursor; reads only. */
  public List<DockerRepositoryEntity> findVisible(
      UUID userId, boolean administrator, Optional<DockerRepositoryName> after, int limit) {
    return db.sql(
            "select r.id,r.registry,r.name from repository r where (:admin or exists (select 1 from"
                + " repo_permission p where p.repo_id=r.id and p.user_id=:u)) and (r.registry ||"
                + " '/' || r.name)>:after order by r.registry || '/' || r.name limit :lim")
        .param("admin", administrator)
        .param("u", userId)
        .param("after", after.map(DockerRepositoryName::externalName).orElse(""))
        .param("lim", limit)
        .query(DockerRepositoryEntity.class)
        .list();
  }
}
