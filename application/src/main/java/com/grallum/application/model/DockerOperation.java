package com.grallum.application.model;

/** Typed Docker operations; upload status and cancellation remain write operations. */
public enum DockerOperation {
  TAGS_LIST,
  MANIFEST_READ,
  MANIFEST_WRITE,
  MANIFEST_DELETE,
  BLOB_READ,
  BLOB_DELETE,
  UPLOAD_START,
  UPLOAD_STATUS,
  UPLOAD_APPEND,
  UPLOAD_COMPLETE,
  UPLOAD_CANCEL
}
