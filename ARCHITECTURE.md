# Architecture Constraints

References: [Design Guide](DESIGN.md) · [Component contracts](DESIGN.md#component-documents) ·
[Technical specifications](docs/spec/README.md)

## Authority and Scope

This document defines project-wide technical architecture and implementation constraints. It is not a component
introduction or an operating manual. Concrete business rules, classes, methods, fields, state machines and call chains
belong in their owning component contracts. [DESIGN.md](DESIGN.md) governs how those contracts are written.

Confirmed contracts and applicable SPECs govern implementation. Existing code, passing tests and migration notes do
not establish exceptions. Resolve missing, conflicting or infeasible definitions before implementing the affected
part. Explain the problem and obtain explicit authorization for a separate design change; do not rewrite documents
while implementing code to justify undeclared behavior or weaken checks to make implementation pass.

Keep both governing documents in English. Component bodies use Chinese with the prescribed English headings.
Operating procedures and implementation evidence belong in [operations](docs/operations.md) and
[verification](docs/verification.md), respectively; neither overrides a contract.

## Technical Specifications

- Keep independent technical specifications in `docs/spec/`, indexed by its README.md.
- A SPEC defines technical APIs, wire behavior, sequencing, state machines, concurrency, resource ownership and
  acceptance criteria. It must be understandable without application business context.
- Do not put business entities, permission policies, component call chains, product design references or deployment
  scripts in a SPEC. Designs reference SPECs and define business integration; the dependency remains one-way.
- SPECs may reference other technical specifications, standards and official protocol documentation.
- Define identity, event delivery, errors, completion and cancellation/control semantics for technical response
  abstractions. Transport acknowledgment or a completed future does not establish application completion.

## Runtime and Delivery

- Use Java 25, Spring Boot, Maven, PostgreSQL and GraalVM Native Image. Pin compatible framework and plugin versions;
  version changes must be intentional. Use Spring AOT and Native Build Tools for native delivery.
- The root Maven project aggregates `application/`; the module remains independently buildable. Derive IDE roots and
  dependencies from Maven rather than manually maintaining them.
- Keep only the bootstrap class `Application` directly in `com.grallum.application`.
- Use Spring MVC and Thymeleaf for server-rendered pages. JavaScript is optional enhancement. Templates belong under
  `application/src/main/resources/templates/`, and static assets under `application/src/main/resources/static/`.
- JWT is the authentication scheme. Validate signatures, intended audiences and current account/grant state. Retain
  CSRF protection for cookie-authenticated writes; concrete token, cookie and endpoint contracts belong in designs.
- Keep credentials in externally supplied configuration; never log passwords, tokens, private keys or payload bodies,
  including through generated `toString()` methods.
- Builds and AOT processing must not connect to business databases, start business tasks or cause real external side
  effects. Declare resources and reflection metadata only for demonstrated needs.
- Keep build intermediates under `application/target/`; packaging must exclude credentials and local runtime state.
- JVM success is not native acceptance. Verify templates, typed JSON binding, JDBC, external integration and bounded
  shutdown on each claimed native target. Record source, JVM, native, database and live acceptance separately.

## Packages and Dependencies

Use these responsibility-based packages under `com.grallum.application`:

| Package | Responsibility |
| --- | --- |
| `controller` | HTTP binding, validation, responses and server-rendered page entry points |
| `service` | Authorization, business use cases and resource coordination |
| `repository` | Parameterized SQL, row mapping, transactions and atomic persistence operations |
| `entity` | Complete database record representations, one owning table per entity |
| `dto` | Typed requests, responses and page-view data |
| `model` | Business values, enums and internal operation results |
| `config` | Container assembly and immutable configuration holders |
| `security` | Credential extraction, JWT codecs and security-context adaptation |
| `task` | Scheduled entry points delegating to services |
| `exception` | Typed failures and surface-specific error mapping |

- Controllers and scheduled tasks call services; services call repositories or the designated integration service.
  Controllers and tasks do not access SQL, backend HTTP or locks. Repositories do not depend on services, controllers,
  external clients, token signing or permission policy.
- Services receive typed identities and business values, not Servlet objects or Spring Security `Authentication`.
  Keep HTTP/Servlet types out of services and repositories. Only repositories access JDBC.
- Entities stay within persistence and service mapping boundaries; never expose them directly as HTTP responses.
  DTOs contain no business logic or database access. Business models have no MVC or JDBC dependency.
- Use constructor injection, appropriate Spring stereotypes and explicit imports. Use descriptive business names with
  responsibility suffixes. Do not expose JDBC/HTTP fields or use service-locator lookups.
- Do not introduce generic gateway/client/port/adapter package hierarchies or speculative interface/implementation
  pairs. Use the declared concrete components. Shared types have one owning contract and are referenced elsewhere.
- Do not reintroduce catch-all persistence/management objects or direct controller SQL under new names.
  Concrete backend access, authorization and coordination owners are defined in component contracts.

## Transactions and Persistence

- Repositories own transaction creation, propagation, commit and rollback. Services coordinate business operations
  outside SQL transactions and must not own `@Transactional` boundaries or `TransactionTemplate` execution.
- An atomic operation spanning multiple writes must have one declared repository transaction owner. Do not split it
  into separately committed calls when moving transaction ownership. Define exact aggregate entry points, participating
  writes, row-lock order and failure behavior in the component contract before implementation.
- Repositories may coordinate persistence collaborators within that declared boundary, never invoke business services
  or accept arbitrary business callbacks to recreate a service-owned transaction.
- Keep transactions short. No SQL transaction may span external streaming, deletion or another network operation.
  A database rollback cannot undo an external side effect. Persist intent and reconcile uncertain outcomes according
  to the component contract; do not automatically replay uncertain writes or claim exactly-once delivery.
- Publish externally visible configuration/state only after commit. Persisted state, local acknowledgment and external
  completion are distinct evidence. A required failure-state commit must survive a later business error response.
- Validate external identity and response completeness before writes. Preserve declared identity and binding scope;
  derive observed timestamps from source facts rather than inventing them.
- Migration SQL is the single source for table/column definitions, meanings, nullability, defaults, indexes and
  constraints. Designs declare Java entities and DTOs and explain usage, but reference SQL for database semantics.
  The existing migration directory is `application/src/main/resources/db/migration/`.
- Do not introduce application-managed DDL or an alternative database schema authority.

## Development Schema Lifecycle

- During development, edit the existing creation/migration scripts for the affected tables directly. Do not append
  migration versions merely for design iterations or keep compatibility paths for obsolete development schemas.
- Update related constraints, SQL comments, rollback procedures and tests together. Keep existing migration execution
  order coherent when a table is created in one script and altered in another.
- Apply changed scripts only to a clean or explicitly approved rebuilt local project database. Never modify Flyway
  checksums or run repair merely to hide a mismatch; do not rebuild shared databases or other project data.
- A documentation or schema edit does not authorize dropping a database. Preserve required local data unless its
  disposal has been explicitly authorized. Define and verify backup, retention and upgrade procedures before rollout.
- Released/applied production migrations are immutable. Do not use the development rebuild workflow in production.
- Cover every table with isolated database checks. Verify clean initialization, required rollback/reinitialization and
  consistency of constraints before claiming schema acceptance; report unverified paths explicitly.

## Container Configuration and Shared Resources

- Only `config.AppConfig` and `config.WebConfig` may be application-owned container configuration classes. Keep
  `@Configuration`, `@Bean` factories and infrastructure assembly there. The bootstrap class and framework
  auto-configuration are not additional application configuration classes.
- AppConfig owns general infrastructure, clocks, mappers, executors, native metadata assembly and non-Web settings.
  It creates, configures, injects and destroys shared resources; it does not implement business workflows.
- WebConfig owns MVC, security chains, JWT/cookie/CSRF assembly, HTTP converters and Web API documentation assembly.
  It consumes shared dependencies from AppConfig rather than constructing replacements.
- Inject every application-owned external configuration value through Spring `@Value`, preferably on constructor or
  `@Bean` method parameters. Declare keys, defaults, types, units, validation and usage scenarios in the owning design.
- Do not use `@ConfigurationProperties`, configuration-property scanning, `Environment.getProperty`,
  `System.getProperty`, `System.getenv`, manual property readers or programmatic binders as alternative value sources.
  Framework-internal configuration processing is outside this rule.
- Immutable typed holders may group values. AppConfig or WebConfig constructs them from `@Value` parameters; holders
  only validate and expose values. They do not read sources, create dependencies or host hidden bean factories.
- Borrowing a resource does not transfer creation, reconfiguration or shutdown authority. Keep cleanup with its owner
  or an explicitly documented recipient. Consumers cancel their work before supporting resources are destroyed.

### Dependency Names and Identity

- Name injected fields after the complete declared type in lower camel case. Constructor parameters and corresponding
  `private final` fields have identical names and types with explicit assignment; do not create aliases.
- Inject each Bean identity once per component, including inherited dependencies. Do not mix direct, provider,
  setter/field injection or container lookup for the same identity.
- Components are singletons unless their contract says otherwise. Do not register duplicate instances or aliases to
  satisfy alternate dependency names.
- Deferred injection uses the target type name plus `Provider`; do not retain a second injected target reference.
- Multiple same-type Beans require documented scenarios, canonical names and matching `@Qualifier` declarations used
  consistently by consumers. Keep dependency names consistent in contracts, Javadoc and call chains.

### Jackson Ownership and Typed JSON

- AppConfig creates and configures all application-used Jackson mappers before publication. Inject them into consumers,
  including objects created outside Spring. Consumers must not new, copy, rebuild or mutate a mapper or find one via
  the container. WebConfig uses the injected HTTP mapper for message conversion.
- Multiple mappers require named scenarios and centrally declared feature differences with explicit qualification.
  Define the default HTTP mapper. Tests may use scoped fixtures but must verify production feature sets separately.
- Bind JSON directly to declared records/classes with typed nested fields and collections. Typed header projection
  followed by exact message binding is allowed.
- Do not use JsonNode, ObjectNode, ArrayNode, readTree, untyped Object, raw Map/List or `Map<String, Object>` as
  protocol
  models or intermediate representations. Do not parse JSON using casts, tree traversal or string slicing.
  Typed collections and non-JSON runtime maps remain allowed; malformed-wire fixtures may use generic containers.
- Model optional scalars with nullable boxed values; validate required fields and completeness. Define coercion,
  unknown-field and trailing-value behavior in the protocol contract, without opaque fallback payloads.
- Include required binding types in native metadata and test each mapper scenario on JVM and Native Image.

### Executor Ownership

- AppConfig exclusively creates and manages application-owned executors, including virtual-thread, scheduled and
  Spring task executors. Define names, workloads, concurrency/capacity, rejection and bounded shutdown behavior.
- Consumers receive executors through constructors and submit/cancel only their own tasks. They must not instantiate
  local/static pools, use implicit common-pool async execution to bypass ownership, or shut down shared executors.
- WebConfig injects the MVC executor from AppConfig. Originating components retain task and stream ownership and
  complete cancellation/cleanup before container shutdown of supporting executors. AOT starts no business work.

## Concurrency and Consistency

- Choose the weakest consistency model satisfying the declared invariant. Prefer single-owner execution, immutable
  snapshots, atomic operations and bounded expiring caches where sufficient. Document acceptable stale reads,
  eviction, restart and race behavior.
- Use atomic check-and-reserve operations for single-key reservations. Do not introduce synchronization or locks
  merely because several threads can access data.
- When strict consistency requires a lock, first define the invariant, protected scope, ordering, contention and timeout
  behavior in the component design. This rule does not remove already required transactions or coordination locks.
- Library-internal synchronization does not imply an application-owned lock, and atomic APIs do not prove an entirely
  lock-free call chain. Do not rewrite libraries as custom CAS loops for appearance.
- Local caches do not provide durable or distributed exactly-once guarantees. Define keys, isolation and expiration.
  Deployment topology and the limits of local coordination belong in the owning design.

## Formatting and Documentation

- Use lowercase packages, UpperCamelCase types and lowerCamelCase methods. One public top-level type belongs in a
  matching filename. Keep shared DTO/entity/model types in their designated packages.
- Use `Objects.isNull`, `Objects.nonNull` and `Objects.requireNonNull` for null checks. Do not compare references with
  `== null` or `!= null`, including reversed operands or test code.
- Use two-space indentation in hand-maintained files where syntax permits. Tab inserts spaces; literal tabs are
  permitted only where a format requires them. Use consistent four-space or aligned Java continuations.
- Keep source, configuration and documentation lines at most 120 characters. Wrap prose and declarations; split long
  tables or use reference-style links rather than silently exempting Markdown tables.
- Use multi-line Chinese Javadoc for every explicitly declared type, method and constructor, including nested and
  private declarations. Describe responsibility, ownership, parameters, results, effects, failures and relevant
  lifecycle/concurrency constraints. Use Chinese `@param`, `@return` and `@throws` descriptions where applicable.
- Keep an empty line at the start of a non-empty Java type body and before the first Javadoc block tag.
  Compiler-generated record boilerplate follows the exemptions in DESIGN.md.
- `.editorconfig` supplies shared editor defaults. Checkstyle runs on production and test sources during Maven
  `validate`; generated sources are excluded. Run lint and relevant checks, and do not weaken them to accept drift.
  Formatting commands belong in [operations](docs/operations.md), not in component contracts.

## Declaration and Change Discipline

- Implementation must match every applicable contract declaration, including constructors, private helpers, fields,
  constants, DTOs and effective framework annotations. Document members with type, initialization, mutation authority,
  ownership, usage and lifetime; verify private behavior through source review and tests.
- Do not add files, types, configuration or lifecycle components without design authorization. Do not add or change
  declarations for convenience, speculative reuse, compatibility wrappers or unrelated cleanup.
- Preserve parameter names/types/order, return types, generics, visibility, modifiers, exceptions, annotations and
  inheritance/interface boundaries unless an authorized design change explicitly updates them.
- Preserve member and variable names, types, scope, mutability, initialization and ownership. Do not invent caches,
  stored state, setters or accessors to fill a design gap.
- Contract changes require explicit authorization and precede implementation as a separate design task. Update callers
  consistently after confirmation. Never rewrite a contract retrospectively to legitimize implementation drift.
- Compare source and behavior with contracts before completion. Lint, package checks and successful tests do not prove
  complete declaration coverage or authorize undocumented behavior. Record unresolved gaps in verification reports.
