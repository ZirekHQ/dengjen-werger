# Dengjen Werger

Commitment and peer-review layer in front of translation platforms (first target: Kurmanji strings for NVDA on Crowdin). Java 25, Spring Boot 4, Gradle. Read `ARCHITECTURE.adoc` for the current design and `docs/superpowers/specs/2026-09-11-dengjen-werger-design.md` for the reasoning behind it.

## Commands

| Task | Command |
|---|---|
| Run | `./gradlew bootRun` |
| Unit tests, 80% JaCoCo gate, Spotless check | `./gradlew check` |
| Fix formatting | `./gradlew spotlessApply` |
| Integration tests (dev database) | `./gradlew integrationTest` |
| E2E tests (live Crowdin, manual only) | `./gradlew e2e` |

Run a single test with `./gradlew test --tests 'werger.service.CommitmentEvaluatorTest'`. Run `spotlessApply` before committing; CI fails on formatting.

## Layout

Ports and adapters under `src/main/java/werger/`:

- `domain/`: records, enums, sealed types. Pure data, no I/O.
- `ports/`: interfaces the domain depends on (`TranslationSource`, `Result`, `SourceError`).
- `adapters/`: `crowdin`, `googletranslate`, `db`. Implement ports.
- `service/`: pure logic and orchestration.
- `http/`: thin controllers.

`domain` never imports `adapters` or `http`. Only `http` and Spring configuration know a concrete adapter. Nothing enforces this mechanically, so keep to it by hand.

## Code conventions

- Prefer immutable records and sealed interfaces. Prefer `switch` over `instanceof`.
- Return expected failures as `Result<E, T>` with a sealed error type. Throw only for programmer errors.
- Use `Optional` rather than `null`.
- Compiler runs `-Xlint:all -Werror`, so a warning fails the build.
- Comments explain a constraint the code cannot show, never restate the code.
- Database access uses JDBC on HikariCP against Supavisor's transaction pooler. Keep `prepareThreshold=0` in the JDBC URL; named server-side prepared statements break under transaction pooling.
- Dependency locking is on (`gradle.lockfile`). Regenerate lockfiles with Gradle; never edit them by hand.

## Invariants to protect

- No submission reaches a synced or upstream-pushed state without a completed, valid review.
- A user never reviews their own submission.
- Work-item leasing, the review transition and the points ledger are race-prone. Changes there need a test that exercises the race, not only the happy path.

## Testing

Three tiers, each in its own source set (`src/test`, `src/it`, `src/e2e`); `.github/CONTRIBUTING.adoc` has the full table.

- Unit tests stub outbound HTTP with `MockRestServiceServer` and never touch real infrastructure.
- Integration and e2e tests that need credentials call `assumeTrue(...)` inside the test method so they skip cleanly. Read env vars in the test body, never in a `static` initializer.
- Add or update tests with every behavior change.

## Git and PRs

- Branch from `main`. One concern per PR; keep mechanical cleanup apart from behavior changes.
- Commits and PR titles follow Conventional Commits, as the history shows: `feat: ...`, `fix: ...`, `chore(deps): ...`. Squash-merge uses the PR title.
- Link the issue the PR completes with a closing keyword (`Closes #12`). Use one keyword per issue.
- CI must be green: tests, Sonar, CodeQL, zizmor. GitHub Actions are pinned by commit SHA with a version comment; keep that when bumping.
- `ROADMAP.adoc` tracks milestone status. Update the row when a task lands.

## Docs

`README.adoc`, `ARCHITECTURE.adoc` and `ROADMAP.adoc` describe the current state and change with the code. Files under `docs/superpowers/specs/` and `docs/superpowers/plans/` are dated records; do not edit them after the fact.
