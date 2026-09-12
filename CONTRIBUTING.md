# Contributing

This repo follows [ZirekHQ's org-wide contribution guide](https://github.com/ZirekHQ/.github/blob/main/CONTRIBUTING.md).

Repo-specific notes:
- Read the [spec](docs/superpowers/specs/2026-09-11-dengjen-werger-design.md)
  before picking up a story — it's the source of truth the implementation
  plan argues from.
- Every change lands through a PR; CI must be green before merge.

## Testing pyramid

Tests are split into three tiers:

| Tier | Directory | Command | Covers |
|---|---|---|---|
| Unit | `src/test/scala` | `sbt test` (`Test / test`) | Pure functions and I/O stubbed in-process — no real network or database. Runs on every PR. |
| Integration | `src/it/scala` | `sbt it:test` (`IntegrationTest / test`) | Real infrastructure this project controls — the dev Supabase Postgres via Supavisor. Runs on every PR using repo secrets; on a fork PR (no secrets available) it skips cleanly instead of failing. |
| E2E | `src/e2e/scala` | `sbt e2e:test` (`E2e / test`) | Live third-party services (the real Crowdin API) or the fully deployed system. Run manually with the relevant credentials — not run in CI. |

A test in the integration or e2e tier that needs credentials checks for
them with `sys.env.get(...)` *before* touching any `IO`, substituting a
"skipped — set FOO to run against real X" test when they're absent.
Reading a missing env var inside an `IO` block throws a fatal JVM `Error`
that Cats Effect's runtime doesn't catch as a normal failure, which hangs
the whole test run instead of failing or skipping it.
