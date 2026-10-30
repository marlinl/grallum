# Docker Registry API Specification

## Authority and Scope

This specification defines a technical Registry HTTP API profile: wire operations, authentication challenges,
streaming, pagination, failures and acceptance criteria. Repository policy and application integration are outside
its scope. It does not claim complete OCI conformance; referrers, arbitrary artifact workflows and legacy schema-1
manifests are unsupported.

Wire semantics follow [Distribution HTTP API V2][distribution] and [Registry token authentication][token-auth].
For OCI media types, consult the [OCI distribution specification][oci].

[distribution]: https://distribution.github.io/distribution/spec/api/
[token-auth]: https://docs.docker.com/reference/api/registry/auth/
[oci]: https://github.com/opencontainers/distribution-spec/blob/v1.1.1/spec.md

## Addressing and Compatibility

- Registry operations are rooted at `/v2/`. Repository names may contain slash-separated components.
- Reject ambiguous encoding, traversal, duplicate separators and invalid names before forwarding a request.
- Support `sha256` digests, Docker schema-2 manifests/lists and OCI image manifests/indexes. Preserve the selected
  representation and its digest, including for multi-platform images.
- Other HTTP interfaces cannot substitute for this protocol. Public links must resolve at the trusted public origin.

## Authentication

Registry requests use audience-bound Bearer JWTs. Credentials for a different audience or a browser cookie do not
establish a Registry identity. Missing, expired, malformed or wrong-audience tokens produce a Bearer challenge,
never an HTML login redirect.

```http
HTTP/1.1 401 Unauthorized
Docker-Distribution-Api-Version: registry/2.0
WWW-Authenticate: Bearer realm="https://auth.example/token",service="registry",scope="repository:team/app:pull"
```

The realm and service are trusted server configuration, never arbitrary client-supplied addresses. The token endpoint
accepts `service` and repeated `scope` parameters. Basic credentials may be exchanged over TLS at that endpoint;
Basic is not a resource-request fallback. Successful responses contain `token`, `access_token` with the same value,
`expires_in` and `issued_at`. Invalid credentials return 401; malformed service/scope syntax returns 400.

A scope-free token may authorize only the `/v2/` probe. Requested scopes are intersected with allowed scopes; token
issuance never expands authorization. Resource requests require both an accepted identity and the appropriate scope.
Token lifecycle, grant sources and role mappings are outside this protocol specification.

## Supported Operations

HEAD never returns a response body. Authentication and authorization failures follow the Failure Contract below.

| Method | Path below `/v2/` | Success |
| --- | --- | --- |
| GET, HEAD | empty path | 200 |
| GET | `_catalog` | 200 |
| GET | `<name>/tags/list` | 200 |
| GET, HEAD | `<name>/manifests/<reference>` | 200 |
| PUT | `<name>/manifests/<reference>` | 201 |
| DELETE | `<name>/manifests/<digest>` | 202 |
| GET, HEAD | `<name>/blobs/<digest>` | 200; ranged GET 206 |
| DELETE | `<name>/blobs/<digest>` | 202 |
| POST | `<name>/blobs/uploads/` | 202; completed mount 201 |
| GET | `<name>/blobs/uploads/<uuid>` | 204 |
| PATCH | `<name>/blobs/uploads/<uuid>` | 202 |
| PUT | `<name>/blobs/uploads/<uuid>?digest=<digest>` | 201 |
| DELETE | `<name>/blobs/uploads/<uuid>` | 204 |

Reads require pull scope; the upload workflow, including status and cancellation, requires push scope. An unsupported
operation returns 405 with `UNSUPPORTED`. Manifest deletion accepts a digest, not a tag. Logical deletion does not
promise immediate physical byte reclamation and must respect storage-engine reference semantics.

## Headers and Streaming

Preserve content negotiation, content digests, upload identifiers, lengths, byte ranges and retry information.
Do not collapse multiple Accept values or replace a selected manifest media type with generic JSON. Stream image
bodies with bounded memory and propagate cancellation to the backend; do not reserialize manifests or transform blobs.

Location and pagination Link values must remain usable at the configured public origin with the external repository
name. Preserve opaque upload query state when rewriting links. Never expose internal service addresses. Do not forward
client credentials, cookies or hop-by-hop headers to a backend. Redirect handling must respect the trusted egress
policy.

The forwarding layer borrows incoming request streams and owns acquired backend responses until an explicit handoff.
The recipient of a response releases its stream and associated resources on completion, cancellation or failure.
No second reader may compete for the same stream, and cleanup must not report incomplete transfers as successful.

## Uploads, Ranges, and Mounts

POST starts an upload, optional PATCH requests append chunks, and PUT with a digest commits it. The final PUT may carry
the entire blob or remaining bytes. Use the most recently returned location and preserve existing query parameters when
adding digest. After interruption, query status and resume at the reported offset. Incorrect offsets produce 416 with
the current range; they must not append corrupt bytes. Digest mismatch must not publish completed content.
Completed content becomes visible before a manifest may reference it.

Mount requests use `mount=<digest>&from=<source-name>`. Require source pull and target push authorization before
translating names or forwarding. Mount only within a compatible backend; otherwise fall back to ordinary upload
without disclosing inaccessible source content. Never forward unchecked source names.

Downloads preserve supported byte-range requests and 206/416 semantics. Do not turn a partial response into a 200
full-body response. Verify resume and checksums through the forwarding endpoint, not only at storage.

## Listings and Pagination

Use n and last, lexicographic ordering and a next-page Link when another visible page exists. Define and validate
page-size limits at the integration boundary. Filter access before constructing pages, counts or cursors.
Tag responses retain the external repository name. Concurrent changes may alter later pages; pagination is not a
transactional snapshot. A cursor cannot select a different backend or arbitrary URL.

## Failure Contract

Use the Distribution error envelope and standard codes. Do not expose stack traces, SQL, credentials or internal
addresses in message/detail. HEAD errors contain status and headers without a body. Preserve safe backend failures
when they remain accurate after name translation.

| Condition | HTTP behavior |
| --- | --- |
| No acceptable identity | 401, UNAUTHORIZED, Bearer challenge |
| Scope or authorization denial | 403, DENIED |
| Invalid name, digest, manifest or upload state | 400, corresponding Registry code |
| Missing authorized content or upload | 404, corresponding Registry code |
| Invalid upload offset or unsatisfiable range | 416; current upload range where applicable |
| Unsupported operation | 405, UNSUPPORTED |
| Rate limit | 429, TOOMANYREQUESTS; safe Retry-After |
| Dependency unavailable or timeout | 502/503/504 as applicable; safe UNKNOWN envelope |

Do not fabricate dependency failures as missing content. Once streaming headers are committed, terminate a failed
transfer rather than appending a JSON error to image bytes. Do not replay uncertain writes automatically.

## Acceptance Criteria

1. Exercise real Docker login, push, pull and multi-platform pull through the public protocol endpoint.
2. Reject invalid credentials, wrong audiences, malformed names and unauthorized scopes with protocol errors.
3. Verify chunked uploads, interrupted resume, offsets, cancellation, digest mismatch and authorized mount/fallback.
   Upload GET/DELETE require push scope, and failures must preserve bytes and authorization boundaries.
4. Verify manifest negotiation, HEAD, ranged downloads and pagination statuses, headers and external links.
5. Verify large transfers use bounded memory and cancellation closes resources; failed streams never append JSON.
6. Verify dependency failures and unsupported operations retain their distinct status and error semantics.
