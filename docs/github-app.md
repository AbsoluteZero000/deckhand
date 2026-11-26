# The deckhand GitHub App ("Connect with GitHub")

"Connect with GitHub" in the app uses a GitHub App and the OAuth **device flow**: the app shows a short code,
the person enters it on github.com, then picks their cards repo from a list. No token to create or paste.

Access is limited to the repositories the person **installs the app on**, with one permission:
**Contents: read and write** (to sync cards and save edits). The app's public client ID ships inside the APK;
the device flow needs no client secret.

## Registering it (once, by the maintainer)

Open <https://github.com/settings/apps/new> (or an organization's *Developer settings → GitHub Apps*) and set:

| Setting | Value |
|---|---|
| GitHub App name | anything unique, e.g. `deckhands-flashcards` |
| Homepage URL | `https://github.com/AbsoluteZero000/deckhand` |
| Callback URL | leave empty |
| Expire user authorization tokens | **off** (the app has no client secret to refresh tokens with) |
| Request user authorization (OAuth) during installation | off |
| Enable Device Flow | **on** |
| Webhook → Active | **off** |
| Repository permissions → Contents | **Read and write** (Metadata: read-only is added automatically) |
| Everything else | No access |
| Where can this GitHub App be installed? | **Any account** (so anyone can use it with their own repo) |

After creating it, copy the **Client ID** (starts with `Iv`) and the app's **slug** (the last part of its public
URL, `https://github.com/apps/<slug>`) into `android/gradle.properties`:

```properties
deckhand.github.clientId=Iv23li...
deckhand.github.appSlug=deckhands-flashcards
```

Both values are public. Don't generate a client secret or private key: nothing uses them.
Forks can register their own app and set their own values; with both empty, the button is hidden and people paste
a fine-grained token instead.

## What people see

1. **Connect with GitHub** → a code like `ABCD-1234` and **Copy code and open GitHub**.
2. On github.com: paste the code, authorize deckhand.
3. Back in the app: their repos that have the app installed. If none, **Install on your cards repo** opens
   `https://github.com/apps/<slug>/installations/new`, where they choose *Only select repositories* → the cards repo.
4. Tap the repo; the app checks its `published` branch and loads the cards.

Revoking: GitHub → Settings → Applications → *Authorized GitHub Apps* (sign-in) or *Installed GitHub Apps* (repo access).
