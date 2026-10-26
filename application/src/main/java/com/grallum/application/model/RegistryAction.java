package com.grallum.application.model;

/** Actions permitted inside a repository-scoped Docker token. */
public enum RegistryAction {
  PULL,
  PUSH,
  DELETE
}
