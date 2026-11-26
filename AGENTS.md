# Working on deckhand

This is the **public** repo for the deckhand tool, its shared CI workflow and the Android app.
Flashcard content does **not** live here: each user keeps their cards in their own private
cards repo (created with `deckhand init`). Card YAML for real decks must never be committed
here, pasted into issues or PRs, or used as test data. Use the example deck in the template.

## Layout

```
deckhand/cli.py                   the tool: init, add, edit, rm, validate, fmt, build, ...
deckhand/template/                files `deckhand init` writes into a new cards repo
                                  (a leading "dot-" becomes "." when written)
.github/workflows/publish.yml     shared workflow that cards repos call to validate and publish
.github/workflows/ci.yml          checks the tool by scaffolding and exercising a demo cards repo
pyproject.toml                    pip package; installs the `deckhand` command
android/                          the app (reads <owner>/<repo>@published; edits commit to main)
```

## Developing

```powershell
pip install -e .
deckhand init $env:TEMP\demo
cd $env:TEMP\demo; deckhand validate; deckhand build
```

## Rules

1. Keep the card format and `deck.json` contract backward compatible. If it must change, bump
   `SCHEMA_VERSION` and keep the app able to read the previous schema.
2. Card ids are the app's key for study progress; nothing in the tool may rename them.
3. Template placeholders: `{{NAME}}`, `{{DECKHAND_REPO}}`, `{{DECKHAND_REF}}`. Leave GitHub
   expressions (`${{ ... }}`) untouched.
4. Changes to `publish.yml` affect every cards repo that calls it at `@main`; keep its inputs
   backward compatible.
5. Run the steps in `ci.yml` locally (or let CI run them) before pushing.
