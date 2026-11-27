# Working on the cards

This repo holds flashcard **content** as YAML, studied with the deckhand Android app.
The tool and app live in [deckhand](https://github.com/{{DECKHAND_REPO}}).
Pushing to `main` publishes the content; every installed app picks it up on its next sync.

## Layout

```
cards/<deck>/deck.yaml          one folder per deck (top-level group): id, name, description, order, context
cards/<deck>/<section>.yaml     sections inside a deck, each holding a list of cards
dist/                           build output (deck.json, manifest.json); never commit
```

A section file:

```yaml
section:
  id: l4-routing          # must equal the file name
  name: L4 Routing        # shown in the app
  order: 4                # sort order inside the deck
  context: Static and dynamic routing, and how a route is chosen.   # optional
cards:
- id: net-route-selection-rule-1
  front: 'Route selection rule #1?'
  back: Most specific route wins (longest prefix).
  why: Bigger number after the / = smaller, more exact area.   # optional
  tags: [routing, exam-trap]                                   # optional
```

Card fields: `id`, `front`, `back` are required; `why` and `tags` are optional. Nothing else is allowed.

`context` (optional, on a deck and on a section) is background for the app's **Ask AI** button: it copies
the deck context, then the section context, then the card, so an AI can answer questions about it.
Deck context: what the deck is for (course, exam, product version, level). Section context: what this
lesson covers. One to three sentences each; don't repeat card content.
Text can use `` `backticks` `` for code/IPs/commands; the app renders them in monospace.

## Rules

1. **Never change or reuse a card id.** The app stores study progress by id.
   Changing an id resets that card's progress; reusing an old id attaches old
   progress to a different question. To fix a card, edit its text and keep the id.
2. **Prefer the tool over hand-editing.** It keeps files valid and canonically formatted.
   If you hand-edit, run `deckhand fmt` and `deckhand validate` afterwards.
3. **Always run `deckhand validate` before committing.** CI rejects invalid content.
4. Tags: lowercase letters, digits, dashes (`exam-trap`, `central-nat`). Reuse existing tags;
   run `deckhand tags` to see them before inventing a new one.
5. Keep cards small: one fact or one decision per card. `back` is the short answer;
   `why` is one or two sentences of reasoning.
6. Removing a card removes it from every phone at next sync, along with its progress.

## Commands

Setup once: `pip install git+https://github.com/{{DECKHAND_REPO}}@{{DECKHAND_REF}}`
(this gives the `deckhand` command; `python -m deckhand` works too). Run it anywhere inside this repo.

```powershell
deckhand tree                                   # decks, sections, counts
deckhand tags                                   # tags in use
deckhand list --deck example --tag learning     # filter by deck/section/tag/text
deckhand list --search "repetition" --json
deckhand show example-what-is-spaced-repetition

deckhand add --deck example --section basics `
  --front "What does ECMP stand for?" --back "Equal Cost Multi-Path." `
  --why "Same prefix, distance and priority: traffic is shared." --tags routing

deckhand edit <id> --back "New answer" --add-tag exam-trap --remove-tag weak
deckhand edit <id> --move-to other-section      # or deck/section
deckhand rm <id> [<id> ...]

deckhand new-deck ccna --name "CCNA" --description "Cisco CCNA notes" --context "Cisco CCNA 200-301 exam prep."
deckhand new-section --deck ccna --id l1-basics --name "L1 Basics" --order 1 --context "OSI model, cabling, switching basics."

deckhand import new-cards.yaml --dry-run        # bulk add, then without --dry-run
deckhand validate
deckhand fmt                                    # canonical layout
deckhand build                                  # writes dist/ (CI does this too)
```

Bulk import file (YAML or JSON list):

```yaml
- deck: example
  section: basics
  front: What does SSL deep inspection need on clients?
  back: The CA certificate installed as trusted.
  why: Otherwise browsers show certificate warnings.
  tags: [certificates, exam-trap]
```

## Publishing

This repo is **private**; keep it that way and never copy card content to public places
(gists, Pages, public repos, pastebins, or issues/PRs on the deckhand repo).

Commit and push to `main`. The `cards` workflow validates, builds and force-pushes
`manifest.json` + `deck.json` to the `published` branch of this repo. The app reads that
branch through the GitHub API with a read-only token. Pull requests are validated but not
published. Never commit to `published` by hand; CI overwrites it.
