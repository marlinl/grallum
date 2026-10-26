ALTER TABLE app_user ADD COLUMN id uuid NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE app_user ADD COLUMN credential_version bigint NOT NULL DEFAULT 0;
ALTER TABLE repo_permission ADD COLUMN user_id uuid;
UPDATE repo_permission p SET user_id=u.id FROM app_user u WHERE u.username=p.username;
ALTER TABLE repo_permission ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE repo_permission DROP CONSTRAINT repo_permission_username_fkey;
ALTER TABLE repo_permission DROP CONSTRAINT repo_permission_pkey;
ALTER TABLE repo_permission DROP COLUMN username;
ALTER TABLE app_user DROP CONSTRAINT app_user_pkey;
ALTER TABLE app_user ADD PRIMARY KEY(id);
ALTER TABLE app_user ADD CONSTRAINT app_user_username_key UNIQUE(username);
ALTER TABLE repo_permission ADD PRIMARY KEY(repo_id,user_id);
ALTER TABLE repo_permission ADD FOREIGN KEY(user_id) REFERENCES app_user(id) ON DELETE CASCADE;
ALTER TABLE cleanup_policy ADD COLUMN version bigint NOT NULL DEFAULT 1;
CREATE TABLE user_refresh_family (
  id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES app_user(id), credential_version bigint NOT NULL,
  absolute_expires_at timestamptz NOT NULL, revoked_at timestamptz
);
CREATE INDEX user_refresh_family_user_idx ON user_refresh_family(user_id);
CREATE TABLE user_refresh_token (
  id uuid PRIMARY KEY, family_id uuid NOT NULL REFERENCES user_refresh_family(id),
  secret_hash varchar(64) NOT NULL UNIQUE, expires_at timestamptz NOT NULL,
  consumed_at timestamptz, replacement_id uuid REFERENCES user_refresh_token(id) DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX user_refresh_token_family_idx ON user_refresh_token(family_id);
CREATE TABLE user_token_revocation (token_id uuid PRIMARY KEY, expires_at timestamptz NOT NULL);
CREATE TABLE docker_cleanup_run (
  id uuid PRIMARY KEY, repository_id bigint NOT NULL REFERENCES repository(id), actor varchar(64) NOT NULL,
  dry_run boolean NOT NULL, policy_version bigint NOT NULL, policy_snapshot text NOT NULL,
  status varchar(16) NOT NULL CHECK(status IN ('PENDING','RUNNING','SUCCEEDED','PARTIAL','FAILED','SKIPPED')),
  started_at timestamptz NOT NULL, finished_at timestamptz, safe_error text NOT NULL DEFAULT ''
);
CREATE INDEX docker_cleanup_unfinished_idx ON docker_cleanup_run(status,started_at);
CREATE TABLE docker_cleanup_item (
  run_id uuid NOT NULL REFERENCES docker_cleanup_run(id), digest varchar(100) NOT NULL,
  status varchar(20) NOT NULL CHECK(status IN ('PLANNED','INTENT','DELETED','ALREADY_ABSENT','UNCERTAIN')),
  confirmed_at timestamptz, safe_error text NOT NULL DEFAULT '', PRIMARY KEY(run_id,digest)
);
