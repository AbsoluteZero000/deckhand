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

The app reads cards straight from this repo with your token, so the repo never needs to be public.

## Connect the app

1. Push to `main` once, and check that the **cards** workflow created a `published` branch.
2. Create a fine-grained personal access token (GitHub → Settings → Developer settings →
   Fine-grained tokens):
   - **Repository access:** only this repository
   - **Permissions:** Contents → Read and write to edit cards in the app, or Read-only to just study (nothing else)
   - **Expiration:** your choice; the app tells you when it stops working
3. In the app's Settings, enter this repo as `owner/{{NAME}}` and paste the token.
   The app stores the token encrypted on the phone.
