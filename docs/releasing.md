# Releasing the app

Release APKs are built and signed by the `android` workflow when you push a tag named `app-v<version>`:

```powershell
git tag app-v0.1.0; git push origin app-v0.1.0
```

The workflow attaches `deckhand-<version>.apk` to a GitHub Release. Phones can install it from there
(Android asks to allow installs from the browser the first time).

## Signing key (once)

Android only installs an update if it's signed with the same key as the installed app, so create the key once
and keep a backup somewhere safe (a password manager). **Never commit it.** The repo's `.gitignore` blocks
`*.jks` and `*.keystore`.

```powershell
keytool -genkeypair -v -keystore deckhand-release.jks -alias deckhand -keyalg RSA -keysize 4096 -validity 10000
```

Then add four repository secrets (GitHub → Settings → Secrets and variables → Actions), or with `gh`:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("deckhand-release.jks")) | gh secret set DECKHAND_KEYSTORE_B64
gh secret set DECKHAND_KEYSTORE_PASSWORD
gh secret set DECKHAND_KEY_ALIAS --body deckhand
gh secret set DECKHAND_KEY_PASSWORD
```

## Building locally

```powershell
cd android
./gradlew testDebugUnitTest assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
```

Debug builds install next to release builds (`.debug` package suffix) and need no key.
