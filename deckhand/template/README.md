# {{NAME}}

Private flashcards for the [deckhand](https://github.com/{{DECKHAND_REPO}}) app.
Cards are plain YAML in `cards/`, so people and AI agents can add, edit and remove them easily.
See [AGENTS.md](AGENTS.md) for the format and commands.

```powershell
pip install git+https://github.com/{{DECKHAND_REPO}}@{{DECKHAND_REF}}
deckhand add --deck example --section basics --front "..." --back "..." --tags learning
deckhand validate
git commit -am "Add card"; git push
```

## Keep this repo private

The app reads cards straight from this repo through the GitHub API, so the repo never needs to be public.

## Connect the app

1. Push to `main` once, and check that the **cards** workflow created a `published` branch.
2. In the app, tap **Connect with GitHub**, enter the code on github.com, install the deckhand app on
   **only this repository**, and pick it in the app.

No GitHub App available? Use **Advanced: use a token** on the setup screen with a fine-grained token limited to
this repository (Contents → Read and write to edit cards in the app, Read-only to just study).
