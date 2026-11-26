# deckhand

Swipe flashcards on Android, with the cards kept as plain YAML in **your own private GitHub repo**
so you and AI agents can add, edit and remove them easily.

- **Bring your own repo.** deckhand is the tool, the CI workflow and the app. Your cards live in a
  separate private repo that only you and the app you connect can read.
- **Decks and sections** group the cards; each card can carry any number of **tags**.
- **Offline app.** It keeps everything in a local database, syncs new content from your repo, and
  never loses study progress when cards change.
- **Spaced repetition.** Missed cards come back within a few cards; known cards come back after
  longer and longer gaps.

## Quick start

```powershell
pip install git+https://github.com/AbsoluteZero000/deckhand
deckhand init my-cards
cd my-cards
git init -b main; git add -A; git commit -m "Start cards"
gh repo create my-cards --private --source . --push
```

`deckhand init` creates a cards repo with an example deck, a CI workflow, and `AGENTS.md` /
`CLAUDE.md` so agents know how to edit it. Then add cards:

```powershell
deckhand new-deck ccna --name "CCNA"
deckhand new-section --deck ccna --id l1-basics --name "L1 Basics"
deckhand add --deck ccna --section l1-basics --front "..." --back "..." --tags routing
deckhand validate
git add -A; git commit -m "Add CCNA cards"; git push
```

Run `deckhand` anywhere inside a cards repo; it finds the nearest folder with `cards/`.
Use `--root <path>` or `DECKHAND_ROOT` to point it elsewhere. `deckhand --help` lists every command.

## How updates reach the phone

1. Push to `main` of your cards repo.
2. Its `cards` workflow calls deckhand's shared [publish workflow](.github/workflows/publish.yml),
   which validates the cards and force-pushes `manifest.json` and `deck.json` to the `published`
   branch of **your** repo. Nothing is copied anywhere else.
3. The app reads the manifest on open (or on **Sync now**) from
   `GET https://api.github.com/repos/<owner>/<repo>/contents/manifest.json?ref=published`
   with `Accept: application/vnd.github.raw` and your token. If the version changed it downloads
   `deck.json` the same way, checks its sha256, and applies it by card id: new cards are added,
   edited cards updated, removed cards deleted.

## The app

Install the APK from the latest `app-v*` [release](https://github.com/AbsoluteZero000/deckhand/releases).
Tap a card to flip it, swipe right if you knew it and left if you missed it. You can study a whole deck,
some sections, or only cards with certain tags, the cards due today (**Due**), more new cards past the daily limit (**New**), or everything without touching
the schedule (**Practice**).
Progress stays on the phone; **Settings → Export progress** saves a backup file.
**Try the example deck** on the first screen shows the app without connecting a repo.
Design: [docs/app-design.md](docs/app-design.md). Releasing: [docs/releasing.md](docs/releasing.md).

## Connecting the app

Tap **Connect with GitHub**: enter the short code it shows on github.com, install the deckhand GitHub App on
**only your cards repo**, then pick that repo in the app. The app can only see repos you install it on, and only
their contents. (Maintainers: see [docs/github-app.md](docs/github-app.md) for how the app is registered.)

No GitHub App configured (e.g. your own fork)? Under **Advanced: use a token** on the setup screen, enter:

- **Repository:** `owner/name` of your cards repo
- **Token:** a fine-grained personal access token (GitHub → Settings → Developer settings →
  Fine-grained tokens) with **only that repository** and **Contents → Read and write** if you want to
  edit cards in the app, or **Read-only** to only study. The app stores it encrypted on the phone and
  tells you when it expires.

## Editing cards on the phone

Tap the pencil on a card while studying, or the edit icon on a deck to browse, search, add and delete cards.
Each save commits that card's section file to your cards repo. The app writes valid YAML in a simple
quoted style; the cards workflow then formats it canonically (committing `Format cards`) and republishes,
so other phones get the change about a minute later. Pull requests are still checked strictly.

## Using your own fork

Fork deckhand, then create cards repos with
`deckhand init my-cards --deckhand-repo you/deckhand --deckhand-ref main` so their CI uses your fork.

## Data contract (`deck.json`)

```json
{
  "schema": 1,
  "version": "cf9c2a70998e",
  "generated_at": "2026-11-26T10:00:00+00:00",
  "decks": [{"id": "ccna", "name": "...", "description": "...", "order": 1,
             "sections": [{"id": "l1-basics", "name": "L1 Basics", "order": 1}]}],
  "cards": [{"id": "ccna-...", "deck": "ccna", "section": "l1-basics",
             "front": "...", "back": "...", "why": "...", "tags": ["routing"], "order": 0}]
}
```

`manifest.json` carries `schema`, `version`, `sha256` of `deck.json`, `deck_url` (relative), and counts.
