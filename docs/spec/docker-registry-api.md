# Docker Registry API Specification

## Status and authority

This is the target client-facing contract for Grallum. It does not certify the existing Session/Basic gateway as
conformant. The [controller design](../design/001-docker-registry-controller.md) owns implementation responsibilities;
the [login design](../design/005-user-login.md) owns credentials and token lifecycle.

Wire semantics follow [Distribution HTTP API V2](https://distribution.github.io/distribution/spec/api/) and
[Registry token authentication](https://docs.docker.com/reference/api/registry/auth/).
The tables below select supported operations and add Grallum routing, permission, and deployment requirements.
For OCI media types, consult the [OCI distribution specification](https://github.com/opencontainers/distribution-spec/blob/v1.1.1/spec.md).
Grallum does not claim complete OCI conformance: referrers, arbitrary artifact workflows, and legacy schema-1
manifests are outside this initial profile.

## Addressing and compatibility

- The public origin is configured by the operator, for example `https://registry.example.com`.
- `<name>` is `<alias>/<repository>`, for example `private/team/app` or `dockerhub/library/alpine`.
- The alias selects a configured backend. The remaining repository name must already be registered and authorized.
- The alias remains present in public names and links; it is removed only when selecting the backend path.
- Reject ambiguous encoding, traversal, duplicate separators, and invalid names before routing or authorization.
- `sha256` digests are supported. Accept Docker schema-2 manifests/lists and OCI image manifests/indexes;
  preserve the selected representation and its digest, including for multi-platform images.
- Management operations under `/api/**` and HTML pages are separate contracts. They cannot substitute for `/v2/**`.

## Authentication

Registry requests use Registry-audience Bearer JWTs. Browser cookies and management-audience JWTs do not authenticate
this surface. Missing, expired, malformed, or wrong-audience tokens produce a Bearer challenge, never an HTML redirect.

```http
HTTP/1.1 401 Unauthorized
Docker-Distribution-Api-Version: registry/2.0
WWW-Authenticate: Bearer realm="https://registry.example.com/auth/registry/token",service="grallum-registry",scope="repository:private/team/app:pull"
```

`realm` comes from trusted configuration, not an arbitrary Host or forwarded header. The target token endpoint is
`GET /auth/registry/token?service=grallum-registry&scope=repository:private/team/app:pull`.
It accepts Docker credentials using HTTP Basic over TLS, validates the account, and returns JSON containing
`token`, `access_token` (the same value), `expires_in`, and `issued_at`. A scope-free request may obtain a ping token;
that token grants no repository access. Invalid credentials return 401; invalid service/scope syntax returns 400.

Requested scopes are intersected with current grants and mode restrictions. Tokens must never expand those grants.
The controller checks both signed token scope and current authorization on every operation; permission revocation
does not wait for token expiry. `pull` maps to READ, `push` to WRITE, and Grallum's `delete` action to MANAGE.
WRITE includes READ, MANAGE includes WRITE, and administrator access still obeys repository mode restrictions.
A valid identity lacking required permissions receives 403. Token issuance and revocation details are in design 005.

## Supported operations

The successful status codes below follow the linked Distribution contract. Permissions are Grallum requirements.
`P` means private mode; `P/C` means private or public-cache mode. HEAD never returns a response body.

| Method | Path below `/v2/` | Success | Required permission | Mode |
|---|---|---|---|---|
| GET, HEAD | empty path | 200 | Authenticated identity | P/C |
| GET | `_catalog` | 200 | Filter by current READ grants | P/C |
| GET | `<name>/tags/list` | 200 | READ / pull | P/C |
| GET, HEAD | `<name>/manifests/<reference>` | 200 | READ / pull | P/C |
| PUT | `<name>/manifests/<reference>` | 201 | WRITE / push | P |
| DELETE | `<name>/manifests/<digest>` | 202 | MANAGE / delete | P |
| GET, HEAD | `<name>/blobs/<digest>` | 200; ranged GET 206 | READ / pull | P/C |
| DELETE | `<name>/blobs/<digest>` | 202 | MANAGE / delete | P |
| POST | `<name>/blobs/uploads/` | 202; completed mount 201 | WRITE / push | P |
| GET | `<name>/blobs/uploads/<uuid>` | 204 | WRITE / push | P |
| PATCH | `<name>/blobs/uploads/<uuid>` | 202 | WRITE / push | P |
| PUT | `<name>/blobs/uploads/<uuid>?digest=<digest>` | 201 | WRITE / push | P |
| DELETE | `<name>/blobs/uploads/<uuid>` | 204 | WRITE / push | P |

Uploads are classified by operation, not just HTTP method: upload status is a write workflow, and cancellation
does not require MANAGE. Authenticated writes to cache-mode `/v2/` paths return 405 with `UNSUPPORTED`.
Local cache deletion is a separate management operation that cannot mutate the public upstream.
Manifest deletion accepts a digest, not a tag; physical space reclamation remains an offline storage operation.
Direct blob deletion must respect the storage engine's repository-reference rules and never delete shared files directly.

## Headers and streaming

Preserve content negotiation, content digests, upload identifiers, lengths, byte ranges, and retry information.
In particular, do not collapse multiple `Accept` values or replace a selected manifest media type with generic JSON.
The gateway streams image bodies with bounded memory and propagates client cancellation to the backend.
It must not reserialize manifests or transform blob bytes. Error bodies generated by the gateway are Registry JSON.

`Location` and pagination `Link` values must remain usable at the configured public origin with the external alias.
Preserve opaque upload query state when rewriting links. Never expose internal service hostnames or direct backend
addresses. Client credentials, cookies, and hop-by-hop headers are not forwarded to storage or public upstreams.
Public provider redirects and credentials are handled inside the proxy adapter under operator-controlled egress rules.

## Uploads, ranges, and mounts

The supported baseline is POST to start, optional PATCH chunks, and PUT with a digest to commit. The final PUT may
carry the entire blob or the remaining bytes. Clients use the most recently returned upload location, preserving
its existing query parameters when adding `digest`. An interrupted client can query status and resume at the
reported offset. Wrong offsets produce 416 with the current range; they must not silently append corrupt bytes.
Digest mismatch cannot publish a completed blob. Completed content becomes visible before a manifest can reference it.

Mount requests use `mount=<digest>&from=<source-name>` with external, alias-qualified source names. Grallum checks
READ/pull on the source and WRITE/push on the target. The adapter translates names only after both checks and only
within a compatible storage backend. Otherwise it falls back to ordinary upload without revealing source existence.
Never pass an unchecked client `from` value to Distribution.

For downloads, forward supported byte-range requests and preserve 206/416 semantics. Do not convert a partial body
to a 200 full-body response. Resume and checksum behavior must be exercised through the gateway, not only at storage.

## Listings and pagination

The catalog is Grallum's authorized repository inventory, not an unfiltered backend catalog. Names are external
alias-qualified names. Cache-mode tag listings describe locally known cached content, not every public upstream tag.
These choices are deliberate Grallum product constraints.

Listings use `n` and `last`, lexicographic ordering, and a next-page `Link` when another visible page exists.
The target default page size is 100 with a maximum of 1000; malformed or out-of-range limits return 400.
Filter authorization before page construction so hidden repositories cannot appear through cursors or counts.
Tag responses retain the external repository name. Concurrent changes may alter later pages; clients must not
interpret pagination as a transactional snapshot. Cursor processing cannot select another backend or arbitrary URL.

## Failure contract

Use the Distribution error envelope and its standard codes; do not expose stack traces, SQL, credentials, or internal
addresses in `message` or `detail`. HEAD errors carry status/headers without a body. Preserve safe backend errors
where they remain accurate after alias translation.

| Condition | HTTP behavior |
|---|---|
| No acceptable Registry identity | 401, `UNAUTHORIZED`, Bearer challenge |
| Current grant or token scope denies operation | 403, `DENIED` |
| Invalid name, digest, manifest, or upload state | 400 with the corresponding Registry code |
| Missing authorized repository, manifest, blob, or upload | 404 with the corresponding Registry code |
| Wrong upload offset or unsatisfiable download range | 416; upload response includes current range |
| Operation unavailable in this mode | 405, `UNSUPPORTED` |
| Rate limit | 429, `TOOMANYREQUESTS`; retain safe `Retry-After` |
| Dependency unavailable or upstream timeout | 502/503/504 as applicable; safe `UNKNOWN` envelope |

Repository existence is checked after access authorization; unauthenticated callers receive the same challenge
whether a name exists or not. Dependency failures are not fabricated as missing images. After streaming headers
have been committed, terminate a failed transfer; do not append a JSON error to image bytes.

## Acceptance criteria

1. Real Docker login, push, pull, and multi-platform pull pass through the public gateway with Registry JWTs.
2. Browser JWTs, revoked accounts/grants, malformed paths, alias confusion, and hidden catalog entries are rejected.
3. Chunked upload, interrupted resume, incorrect offsets, cancellation, digest mismatch, and authorized mount/fallback
   preserve both bytes and permissions. Upload GET/DELETE require the correct push scope.
4. Manifest negotiation, HEAD, ranged downloads, and pagination preserve statuses, headers, and public links.
5. Proxy cache misses/hits, public provider failures, and local cache eviction never allow upstream mutations.
6. Large image transfers use bounded memory; failed dependencies produce protocol errors rather than login pages.
7. Run the same protocol suite against JVM and Native deployments before declaring this target profile implemented.
