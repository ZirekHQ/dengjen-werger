# Dengjen Werger

Volunteer translation doesn't fail for lack of a translation tool — it fails
because volunteers don't sustain time commitment over months or years.
Dengjen Werger is a commitment and peer-review layer that sits in front of
existing translation platforms and turns a one-time favor into a habit:
a personal time pledge, reminders, mandatory peer review, points, streaks,
and a leaderboard.

It's for contributors who want structure and accountability around
volunteer translation work, and for reviewers and maintainers who need
confidence that a live translation was actually checked by someone with
standing to check it — not just typed once and shipped.

The initial target is translation for accessibility tooling that visually
impaired users depend on, starting with Kurmanji-language strings for the
NVDA screen reader on Crowdin. Nothing in the design is Kurmanji- or
Crowdin-specific: `Language` is a first-class field throughout the domain
model, and translation platforms are integrated through one
`TranslationSource` port, so later work can point the same commitment and
review loop at other languages, other platforms, and apps beyond NVDA
without a rewrite.

## How it works

1. **Onboarding** — pick a language, a commitment tier (Light/Medium/Heavy),
   and a timezone.
2. **Reminders** — a scheduled job checks recorded progress against the
   tier's quota and emails a nudge only when a contributor is falling
   behind.
3. **Submission** — translating a work item creates a submission and a
   provisional points entry, held pending review.
4. **Review** — another contributor claims the next queued item
   (first-in-first-out, not hand-picked) and approves or rejects it.
   Reviewing earns points immediately; approving pays out the submitter's
   points and updates their streak.
5. **Leaderboard** — ranked by confirmed points, per language. A
   contributor's own streak and personal best always show, so the loop
   still motivates someone before a leaderboard has enough entries to
   matter.

No submission ships to the source platform without passing review — see the
design spec's Anti-abuse section for how that gate resists gaming.

## Roadmap

See [`ROADMAP.md`](ROADMAP.md) for current milestone status. Details and
open questions for each stage live in the spec and plan linked below.

## Getting started

Prerequisites:

- JDK 21
- sbt (this repo pins `sbt.version=1.13.0` in `project/build.properties`;
  any recent sbt launcher fetches it automatically)

Run the scaffold:

```
sbt run
```

Confirm it's serving:

```
curl localhost:8080/healthz
```

The command prints a JSON body: `{"status":"ok","version":"...","commit":"...","builtAt":"..."}`.

Run the test suite:

```
sbt test
```

## Docs

- **Architecture:** [`ARCHITECTURE.md`](ARCHITECTURE.md)
- **Roadmap:** [`ROADMAP.md`](ROADMAP.md)
- **Spec:** [`docs/superpowers/specs/2026-09-11-dengjen-werger-design.md`](docs/superpowers/specs/2026-09-11-dengjen-werger-design.md)
- **Plan:** [`docs/superpowers/plans/2026-09-11-dengjen-werger-v1.md`](docs/superpowers/plans/2026-09-11-dengjen-werger-v1.md)
- **Contributing:** see [`.github/CONTRIBUTING.md`](.github/CONTRIBUTING.md)
- **License:** [GPL-3.0-or-later](LICENSE)

Part of the [Sustainable Contribution](https://github.com/ZirekHQ/.github/blob/main/THEMES.md#sustainable-contribution)
theme.
