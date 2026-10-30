# Design Guide

References: [Architecture Constraints](ARCHITECTURE.md) · [Technical specifications](docs/spec/README.md) ·
[Verification reports](docs/verification.md)

## Purpose and Authority

This guide governs the structure, completeness and maintenance of component design contracts. Shared architecture,
technology, formatting, ownership and declaration-change rules belong in ARCHITECTURE.md. Concrete business behavior,
fields, methods and workflows belong in the owning component design, not in this guide.

Keep component designs only in `docs/designs/`. Use `000_xxxxx_design.md`: a unique three-digit number from 001 to 999,
a lowercase English snake_case topic and the `_design.md` suffix. Do not renumber or reuse assigned IDs; retain a
replacement link when a design is superseded. Link each design to this guide, the architecture and relevant contracts.

Keep independent technical specifications in `docs/spec/`, indexed by README.md. SPECs define technical APIs,
sequencing, invariants and acceptance criteria without business entities, permission policies, component links or
application deployment scripts. Designs may reference SPECs; SPECs must not depend on designs for their semantics.
SPECs may use sections suited to their scope rather than the component template. Keep existing specifications in
English; Chinese bodies are also permitted for new technical specifications.

Operating commands belong in `docs/operations.md`; source navigation and current API usage belong in
`docs/architecture.md` and `docs/api.md`. Record implementation status and acceptance evidence in
`docs/verification.md`, not in normative component contracts.

## Section Structure

Use exactly these four top-level sections in this order: `Context`, `Contract`, `Core Logic`, `Corners`.
Keep these headings in English and write component body prose in Chinese. Technical identifiers retain their original
form. Put the title and reference links before the sections; add subsections as needed. This guide is not itself a
component design and does not use that four-section structure.

| Section | Required content |
| --- | --- |
| `Context` | Problem, callers, scope, responsibilities and ownership boundaries |
| `Contract` | Exact declarations, inputs, outputs, defaults, errors, invariants and extension limits |
| `Core Logic` | Method call chains, guards, state transitions, collaborators and failure exits |
| `Corners` | Empty/invalid inputs, failures, concurrency, cancellation, shutdown and unsupported behavior |

Contract covers public entry points and private implementation declarations, including initialization, mutation,
ownership and lifetime. Core Logic contains concrete method-level execution order; a conceptual diagram alone is
insufficient. Describe current operational migration requirements in the relevant section, never as revision history
or as an exception to the architecture or a SPEC.

## Contract Completeness

- Define complete Java declarations before implementation: qualified types, constructors, methods, parameter names,
  types and order, return types, generics, visibility, modifiers, exceptions and effective framework annotations.
- Separate public/protected entry points, package-visible declarations and private helpers. Include every explicitly
  declared method, constructor, field, constant and nested type; document framework-created objects and their owner.
- For each method or constructor, place a complete Java signature code block immediately before its explanation table.
  Use exactly four table columns: `方法`, `作用`, `使用场景`, `具体逻辑`. Do not insert a heading between block and table,
  collect tables elsewhere, or replace these columns with parameter/return/visibility columns.
- Explain who calls the method under what conditions, the steps and branches, results and necessary failure behavior.
  Each overload has its own row. A group may share a signature block and immediately following table, but declarations
  and rows must correspond one-to-one. Javadoc in excerpts is optional and does not replace tables.
- Compiler-generated record constructors, accessors, equals, hashCode and toString need not be enumerated. Keep the
  record declaration and component contracts. Explicit compact/custom constructors, overrides and added methods still
  require signatures and tables.
- Document every member in a table: type, initial value, initialization timing, mutability, mutation authority,
  ownership, purpose, usage scenario and lifetime. Field, configuration and resource tables must include `使用场景`.
- Define configuration keys, defaults, types, units and validation. Declare resource creation, borrowing, handoff,
  cancellation and cleanup; shared access does not transfer shutdown authority.
- Define error semantics, concurrency invariants and lifecycle boundaries. A field inventory or flow diagram is not a
  complete implementation contract. Keep supplemental behavioral rules grouped by method in concise lists.
- Database SQL is the only authority for column definitions, descriptions, constraints, defaults, indexes and
  nullability. Link to the owning migration rather than duplicating DDL or schema-description tables. Java entity/DTO
  declarations remain required; explain their business usage while referencing SQL for database semantics.
- Do not infer signatures from HTTP routes, database columns or existing source. Mark unresolved contract decisions
  explicitly and resolve them before implementation; absence of a declaration does not authorize inventing it.

## Core Logic Call Chains

- Show each method call chain in a code block, in execution order, from HTTP, task or lifecycle entry through the
  declared collaborators to persistence or external I/O. Include inline comments for purpose, guards, state changes,
  commit boundaries, cleanup and failure exits.
- A component with one processing method has one coherent chain; do not split it into unrelated phase workflows.
  Separate genuinely different entry points and explain their branch conditions.
- Use only methods already declared in the applicable contracts. Call-chain notation cannot introduce helpers,
  replace an unapproved implementation, or conceal a missing transaction entry point.
- Supplementary sequence/state diagrams may clarify behavior but do not replace method chains. Keep background
  explanations in Contract or Corners and preserve the observable invariants.

## Change Discipline

- During code implementation, do not edit design documents, SPECs or governing constraints to accommodate code,
  complete missing declarations or pass checks. Report a missing, conflicting or infeasible definition and pause the
  affected implementation. Only explicit user authorization permits a separate design update before implementation.
- Keep documents within the authorized scope. Explain the concrete triggering scenario, evidence and reason before
  proposing a missing rule; do not add speculative requirements or abstractions as design improvements.
- Write contracts as the current authoritative specification. Exclude implementation-progress banners, conversational
  explanations, abandoned proposals and revision histories. Keep history and evidence in commits, reviews and reports.
- Distinguish a target contract from verified behavior in reporting. Do not present planned APIs or build targets as
  implemented features. Existing defects do not become exceptions, and tests cannot authorize undeclared behavior.
- Change declarations only for the authorized task, before implementing them. Preserve names, types, visibility,
  annotations, initialization, mutability, ownership and lifetime unless the confirmed change explicitly updates them.
- Avoid speculative overloads, convenience accessors, compatibility wrappers and extra state. Update affected callers
  and contracts consistently; operational migration requirements never weaken a SPEC or governing rule.

## Verification

Before completion, compare source declarations and behavior with the contract and run an appropriate test, type check,
lint check or focused manual invariant check. Documentation-only work must verify filenames, IDs, section order,
contract coverage, references and the 120-character limit.

Report what changed, what was checked and what remains unverified. Distinguish source review, JVM tests, native
execution, database acceptance and live behavior. Formatting and package tests are not exact contract-completeness
checks; private declarations also need explicit review. Preserve earlier evidence with its date and scope, and record
new source-contract gaps without presenting historical tests as acceptance of revised constraints.

## Component Documents

| ID | Contract | Scope |
| --- | --- | --- |
| 001 | [Gateway][d001] | Protocol entry and repository management |
| 002 | [Storage][d002] | Local image operations and shared models |
| 003 | [Proxy][d003] | Read-only upstream integration and caching |
| 004 | [Cleanup][d004] | Policy, preview, scheduling and recovery |
| 005 | [Authentication][d005] | JWT, login, grants and token exchange |

[d001]: docs/designs/001_docker_registry_controller_design.md
[d002]: docs/designs/002_local_image_storage_design.md
[d003]: docs/designs/003_public_registry_api_proxy_design.md
[d004]: docs/designs/004_scheduled_cleanup_design.md
[d005]: docs/designs/005_user_login_design.md

The next unassigned ID is 006. New implementation decisions belong in the owning design or technical specification.
