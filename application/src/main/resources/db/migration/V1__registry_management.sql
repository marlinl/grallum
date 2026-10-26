CREATE TABLE app_user (
  username varchar(64) PRIMARY KEY,
  password_hash varchar(100) NOT NULL,
  admin boolean NOT NULL DEFAULT false,
  enabled boolean NOT NULL DEFAULT true
);
CREATE TABLE repository (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  registry varchar(64) NOT NULL,
  name varchar(255) NOT NULL,
  UNIQUE(registry, name)
);
CREATE TABLE repo_permission (
  repo_id bigint NOT NULL REFERENCES repository(id) ON DELETE CASCADE,
  username varchar(64) NOT NULL REFERENCES app_user(username) ON DELETE CASCADE,
  permission varchar(10) NOT NULL CHECK(permission IN ('READ','WRITE','MANAGE')),
  PRIMARY KEY(repo_id, username)
);
CREATE TABLE cleanup_policy (
  repo_id bigint PRIMARY KEY REFERENCES repository(id) ON DELETE CASCADE,
  keep_latest integer NOT NULL DEFAULT 10 CHECK(keep_latest >= 1),
  older_than_days integer NOT NULL DEFAULT 30 CHECK(older_than_days >= 1),
  protected_pattern varchar(200) NOT NULL DEFAULT 'latest,stable,release-*',
  enabled boolean NOT NULL DEFAULT false
);
CREATE TABLE tag_observation (
  repo_id bigint NOT NULL REFERENCES repository(id) ON DELETE CASCADE,
  tag varchar(128) NOT NULL,
  digest varchar(100) NOT NULL,
  first_seen timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(repo_id,tag)
);
CREATE TABLE audit_event (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  occurred_at timestamptz NOT NULL DEFAULT now(),
  actor varchar(64) NOT NULL,
  action varchar(64) NOT NULL,
  target text NOT NULL,
  detail text NOT NULL
);
