# Product Design Overview

This is the product-design index. System boundaries and Java style are defined in
[ARCHITECTURE.md](ARCHITECTURE.md); detailed behavior lives in the numbered documents below.
Product designs under `docs/design/` use Chinese body text and the English section headings defined below.
Root overview documents and specifications remain in English.

## Design catalog

| ID | Product design | Scope | Current status |
|---|---|---|---|
| 001 | [Docker gateway controller](docs/design/001-docker-registry-controller.md) | Layered entry points, repository management, protocol use cases | Implemented in the layered source; see verification evidence |
| 002 | [Local Docker image storage](docs/design/002-local-image-storage.md) | Durable blobs, manifests, references, uploads, local metadata | Implemented in the layered source; see verification evidence |
| 003 | [Public Registry API proxy](docs/design/003-public-registry-api-proxy.md) | Upstream access, read-only proxying, local cache, failures | Implemented in the layered source; see verification evidence |
| 004 | [Scheduled cleanup](docs/design/004-scheduled-cleanup.md) | Per-repository policy, preview, scheduling, safe deletion, audit | Implemented in the layered source; see verification evidence |
| 005 | [User login and authorization](docs/design/005-user-login.md) | Spring-rendered login, JWT lifecycle, grants, Docker token exchange | Implemented in the layered source; see verification evidence |

These are normative target designs, not inventories of current code. Existing implementation defects must be corrected
to match the architecture, contracts, and SPEC; they do not justify exceptions or preservation of the current layout.
A design is not implementation evidence. Current validation and unverified deployment boundaries are recorded in
[docs/verification.md](docs/verification.md).

## Protocol specifications

| Specification | Authority |
|---|---|
| [Docker Registry API](docs/spec/docker-registry-api.md) | Client-visible routes, authentication, headers, statuses, errors, and compatibility boundaries |

Product designs reference the API specification rather than copying its wire contract.
`docs/api.md` describes current management REST endpoints; it is not the Docker Registry protocol specification.

## Capability relationships

- **001** applies **005** identity/grants and chooses **002** private storage or **003** public proxying.
- **003** uses **002** local storage semantics for its cache; it never permits client writes to the public upstream.
- **004** uses local metadata/deletion from **002/003** and shared repository coordination with **001**.
- **005** supplies browser/API/Registry identities; every product rechecks authorization in its use cases.
- All management pages use Spring MVC + Thymeleaf and share services with REST and scheduled entry points.

## Document conventions

| Rule | Requirement |
|---|---|
| Filename | `docs/design/NNN-short-kebab-case-name.md`, where `NNN` is a unique ID from **001 to 999** |
| Language | Chinese body text with the required English section headings for `docs/design/`; English for root overviews and `docs/spec/` |
| ID stability | Never renumber or reuse an assigned ID; retain a superseded design with a replacement link |
| Required sections | Context, Contract, Core Logic, Corners, in that order; see [Section Structure](#section-structure) |
| Cross-references | Link dependencies and relevant specs; update this index when adding or retiring a design |
| Change boundary | Designs prescribe the corrected implementation. Mention existing defects only to explain caller-visible changes within the relevant section; never as SPEC exceptions |
| Class and method detail | Follow the target packages in ARCHITECTURE.md. Define fully qualified business classes, exact method signatures, typed inputs/results, effects, and call chains. Do not turn existing code into the target contract. |
| Verification | Each design owns its acceptance evidence; shared Java rules remain in `ARCHITECTURE.md` |

The next unassigned design ID is **006**. Keep this file an overview and put new implementation decisions in the
owning product design or protocol specification.

## Section Structure

Component designs use four sections in this order: Context, Contract, Core Logic, and Corners.
Keep these English headings without translation. Body text may use Chinese, and subsections may be added as needed.
Place the document title, status, and reference links before the four sections.
This guide organizes design documents and does not itself follow the component design structure.

| Section | What to describe |
|---|---|
| Context | The problem the component solves, its callers and scope, and its responsibility and ownership boundaries with other components. |
| Contract | Inputs, outputs, configuration, defaults, error semantics, invariants, constrained type/extension scope, and exact method declarations callers can rely on. Reference the architecture or SPEC for shared constraints. |
| Core Logic | Main workflows, state transitions, integration, and lifecycle from the perspective of callers and collaborating components, without internal algorithm details or implementation walkthroughs. |
| Corners | Observable behavior for empty values, invalid input, failures, concurrency, shutdown, and other edge cases, including explicitly unsupported scenarios. |

Place migration notes under the relevant section and describe caller-visible differences.
Do not create a separate migration section or use migration notes as exceptions to the SPEC.
