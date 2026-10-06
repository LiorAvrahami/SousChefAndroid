# Sous Chef (Android)

A kitchen-counter recipe app that walks you through a recipe one big step at a time,
runs reliable timers for waiting steps, and **optimizes the recipe over time** from your
ratings: every cooking is a *trial* of a recipe *version*, and the optimizer (classical or
AI-assisted) proposes the next tweak to try.

## Getting the app

Every push to `main` builds a signed release APK on GitHub Actions and attaches it to a
GitHub release. Download the latest APK from the **Releases** page and open it on your
phone. The first time, Android asks you to allow installing apps from your browser or
files app ("install unknown apps"). Updates install over the previous version and keep
your recipes.

## How it works

- **Recipes → Versions → Trials.** A version is a list of steps. Numbers written as
  `1.75[cups]` are parameters the optimizer may tweak (lock any of them in the editor).
  Wait steps have a timer picked on an egg-timer dial. Each cooking is a trial with
  concrete values and, afterwards, a rating.
- **Cook best** uses the best-rated values so far. **Explore** asks the optimizer for a
  new tweak (local nudge or global jump) or, with an OpenRouter key in Settings, asks an
  AI suggester whose proposal is vetted by a second AI checker.
- **Timers** are scheduled as exact system alarms, so they fire with the screen off and
  the app in the background, and keep ringing until you stop them.
- **Data** lives in an on-device database that survives updates, is included in Android
  backups, and can be exported/imported as a JSON file. Deleted recipes go to a trash;
  emptying it requires typing a confirmation.

## Project layout

- `app/src/main/java/com/lioravrahami/souschef/data` — Room database, models, settings
- `.../domain/recipe` — step parsing (`1.75[cups]`), rendering, value substitution
- `.../domain/optimizer` — classical optimizer
- `.../domain/llm` — OpenRouter client and the two-agent AI optimizer
- `.../domain/timer` — exact alarms, alarm service, notifications
- `.../ui` — Jetpack Compose screens
- `.github/workflows/build.yml` — CI build and release
- `keystore/souschef.jks` — release signing key (committed on purpose so every build
  installs over the previous one)
