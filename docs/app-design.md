# deckhand Android app: design (v1)

The app studies the cards from **one** private cards repo (see the main [README](../README.md)).
It is offline-first, has no backend and no analytics, and only ever talks to `api.github.com`.

## Decisions

| Topic | Decision |
|---|---|
| Tech | Kotlin, Jetpack Compose (Material 3), Room, DataStore, OkHttp, kotlinx.serialization. minSdk 26, target/compile 35. Single `app` module, manual DI. |
| Repos | One repo per install, connected with **Connect with GitHub** (GitHub App device flow, see [github-app.md](github-app.md)) or, under Advanced, a fine-grained token on that repo only. |
| Grading | Two answers. Swipe right (or tap ✓) = knew it, swipe left (or tap ✗) = missed. Tap the card to flip. |
| Progress | Stored on the phone only. Export / Import as a JSON file for backup or moving phones. |
| Demo | "Try the example deck" on Setup loads a bundled sample deck so the app can be tried without a repo. Connecting a repo replaces it. |
| Distribution | Signed APK attached to a GitHub Release by CI on each `app-v*` tag. |

## Screens

1. **Setup**: **Connect with GitHub** (shows a code, then a repo picker), **Try the example deck**, and a collapsed
   **Advanced: use a token** section with repo and token fields. Connecting runs a real sync and only saves on success.
2. **Home**: decks with *due* / *new* / total counts, sync line ("Synced 2 min ago · f65c549" or the last error),
   pull-to-refresh and a **Sync now** action, settings icon. Tap a deck to choose what to study.
3. **Choose** (per deck): section chips and tag chips (multi-select; cards must match a chosen section **and** all chosen tags),
   mode **Due** (due + up to today's new-card allowance), **New** (10 more unseen cards, scheduled normally) or
   **Practice** (every matching card, soonest-due first, no schedule changes), counts update live, **Start**.
4. **Study**: one card at a time. Front = question; tap flips to show the answer and, if present, `why` in smaller text.
   Text in `backticks` renders in monospace. Drag right/left past a threshold to grade (card tints green/red while dragging);
   ✓ / ✗ buttons do the same. **Undo** reverts the last grade. Top bar shows progress (n of m) and a close button.
5. **Summary**: knew / missed counts, **Review missed again** (practice just those cards), **Done**.
6. **Settings**: repo (read-only display) with **Change repo** (back to Setup), **Sync now**, daily new-card limit (0–200, default 20),
   **Export progress**, **Import progress**, **Reset progress** (confirm dialog), **Disconnect** (removes token, cards and progress after confirm),
   app version and content version.

## Data

Room database `deckhand.db`:

- `decks(id PK, name, description, ord)`
- `sections(deck_id, id, name, ord, PK(deck_id, id))`
- `cards(id PK, deck_id, section_id, front, back, why, tags (JSON list), ord)`
- `progress(card_id PK, due_epoch_day, interval_days, ease, reps, lapses, first_seen_epoch_day, last_seen_millis)`

Content tables are replaced by sync; `progress` is never touched by sync except that rows for cards that no longer
exist are deleted in the same transaction (a removed card loses its progress, as the cards repo docs promise).

DataStore (preferences): `repo`, encrypted token, `etag`, `content_version`, `last_sync_millis`, `last_sync_error`,
`new_per_day`, `demo`.

**Token storage:** AES-256-GCM key generated in the Android Keystore (non-exportable); only the ciphertext and IV are
stored in DataStore. The token is never logged or shown again after saving.

## Sync

1. `GET https://api.github.com/repos/{repo}/contents/manifest.json?ref=published` with
   `Accept: application/vnd.github.raw`, `Authorization: Bearer <token>`, `X-GitHub-Api-Version: 2022-11-28`,
   and `If-None-Match: <etag>` when we have one.
   - `304` → nothing changed; record the sync time.
   - Manifest `schema` > 1 → error "This deck needs a newer app version".
   - `version` equals the stored one → record time and etag, done.
2. `GET .../contents/deck.json?ref=published` the same way. Check `sha256(body)` equals the manifest's `sha256`
   and the deck's `schema`/`version` match the manifest.
3. One Room transaction: replace decks/sections, upsert cards, delete cards whose id is gone and their progress.
   Then store `version`, `etag`, time; clear the error.

Errors (shown on Home and Setup; content stays usable offline):

| Cause | Message |
|---|---|
| 401 | Token expired or revoked. Create a new one and update it in Settings. |
| 403 with rate-limit headers | GitHub rate limit hit; try again in a few minutes. |
| 403 otherwise / 404 | Repo or `published` branch not found, or the token can't read this repo. |
| No network / timeout | Offline. Studying from the last synced cards. |
| Bad hash / bad JSON | The published deck looks broken. Check the cards repo's CI. |

Sync runs on app start (if the last sync is older than 5 minutes), on **Sync now**, and on pull-to-refresh.
No background sync in v1.

## Scheduling

Times are in whole local days (`epoch day`). A card with no `progress` row is **new**.

- **Knew it**: `reps == 0 → interval 1`, `reps == 1 → 3`, else `round(interval × ease)` (max 365).
  `reps += 1`, `due = today + interval`.
- **Missed**: `lapses += 1`, `reps = 0`, `interval = 0`, `ease = max(1.3, ease − 0.2)`, `due = today`, and the card is put
  back into the current session 4 cards later (or at the end if fewer remain). Knowing it later in the session then
  schedules it 1 day out.
- New cards start with `ease = 2.5`; `first_seen` is set on the first grade. Today's new-card allowance is
  `new_per_day − (cards first seen today)`.
- **Due session** order: due cards (oldest due first), then new cards taken in turn from each section (each section in card order) up to the allowance, so a day spans every lesson.
- **Practice** (seen cards soonest due first, then unseen in deck order) never changes `progress`; missed cards still come back 4 cards later in the session.
- **Keep going / More** (after today's work, from Home, the summary, or the Choose screen's **New** tab, with the same
  section/tag filters): anything still due plus the next 10 new cards past the daily limit, scheduled normally. When no
  new cards are left, Keep going offers **Practice** on the 10 cards due soonest, graded without touching their schedule,
  because reviewing early would stretch their gaps unfairly.
- **Search** (Home top bar) opens Browse over every deck: every word must appear in the card's front, back, why or
  tags (case-insensitive, backticks ignored); **Practice N matching cards** starts a Practice session with that query.
- **Ask AI** (Study top bar) builds a prompt from the deck name, description and `context`, the section name and
  `context`, then the card (front, back, why, tags); copies it to the clipboard and opens the share sheet.
- **Undo** restores the previous `progress` row (or deletes it if the card was new) and puts the card back in front.

## Export / Import

Export writes `deckhand-progress-YYYY-MM-DD.json` through the system file picker:

```json
{"app": "deckhand", "schema": 1, "repo": "owner/name", "exported_at": "2026-11-26T10:00:00Z",
 "progress": [{"card_id": "...", "due_epoch_day": 20500, "interval_days": 3, "ease": 2.5,
               "reps": 2, "lapses": 0, "first_seen_epoch_day": 20497, "last_seen_millis": 1764150000000}]}
```

Import reads the same format and replaces the progress of cards that exist locally; unknown card ids are skipped and counted
in the result message.

## Code layout

```
android/app/src/main/java/io/github/absolutezero000/deckhand/
  App.kt, MainActivity.kt, AppContainer.kt
  data/        Room entities + DAOs, DeckJson models, SyncRepository, Settings (DataStore), TokenCipher, ProgressIo
  domain/      Scheduler (pure Kotlin, unit-tested), StudySession
  ui/          theme, nav, setup, home, choose, study, summary, settings, CardText (backtick rendering)
```

## Editing cards

- **Where:** pencil on the Study screen (current card), and **Browse** per deck (search, section filter, status pills, **New card**).
- **Write path:** `GET /repos/{repo}/contents/cards/{deck}/{section}.yaml` → parse (snakeyaml-engine) → change the one card →
  write the file back with JSON-quoted strings → `PUT` with the blob `sha` (one automatic re-read and retry on a 409/422 conflict).
  The phone's copy updates immediately; the next published deck replaces it with the canonical version.
- **Rules** match the CLI: `front`/`back` required, tags lowercase-dashed, new ids are `<deck>-<slug(front)>` with `-2`, `-3`… suffixes.
  Existing cards keep their id (and progress); moving a card between sections is done on a computer.
- **CI:** the shared publish workflow runs `deckhand fmt` on publishing pushes and commits the result, so app edits pass.
- **Token:** editing needs Contents → Read and write; with a read-only token the app explains what to change.
- **Example deck:** edits stay on the phone.

## Look and feel

Fixed brand palette (teal/indigo gradient hero, per-deck accent colors), System/Light/Dark theme setting, a card stack
with the next card peeking behind, spring animations, haptic ticks at the swipe threshold and on grading, a daily streak
(`review_days` table), and a mastery ring per deck (each card counts by its review gap, up to 21 days).

## Out of scope for v1

Background sync, several repos, moving cards between sections in the app, iOS, images or audio on cards, statistics charts.
