#!/bin/bash
# Compila l'APK di Hommaterial e lo pubblica come release su GitHub.
# Si avvia con un doppio click dal Finder.

REPO="carellice/hommaterial"
GRADLE_FILE="app/build.gradle.kts"

cd "$(dirname "$0")" || exit 1

# Tiene aperta la finestra del Terminale finché non si legge l'esito.
finish() {
    echo
    read -r -p "Premi Invio per chiudere..."
    exit "$1"
}

fail() {
    echo
    echo "ERRORE: $1"
    finish 1
}

echo "=== Hommaterial: pubblicazione release ==="
echo

command -v gh >/dev/null || fail "GitHub CLI non trovata. Installala con: brew install gh"
gh auth status >/dev/null 2>&1 || fail "GitHub CLI non è autenticata. Esegui: gh auth login"

# Una release ha bisogno di almeno un commit a cui agganciare il tag.
if [ "$(gh repo view "$REPO" --json isEmpty --jq .isEmpty 2>/dev/null)" != "false" ]; then
    fail "Il repository $REPO è vuoto o non raggiungibile. Carica prima il codice sorgente."
fi

# Gradle richiede un JDK: senza JAVA_HOME si usa quello incluso in Android Studio.
STUDIO_JDK="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
if [ -z "$JAVA_HOME" ] && [ -d "$STUDIO_JDK" ]; then
    export JAVA_HOME="$STUDIO_JDK"
fi

version_name=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' "$GRADLE_FILE")
version_code=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' "$GRADLE_FILE")
[ -n "$version_name" ] && [ -n "$version_code" ] || fail "Versione non trovata in $GRADLE_FILE"

# Se la versione attuale è già stata pubblicata si passa da sola alla successiva (1.0 -> 1.1).
# Una versione non ancora pubblicata, come la prima o quella di un tentativo fallito, resta com'è.
old_name=$version_name
old_code=$version_code
while gh release view "v$version_name" --repo "$REPO" >/dev/null 2>&1; do
    major=${version_name%%.*}
    minor=${version_name#*.}
    minor=${minor%%.*}
    version_name="$major.$((minor + 1))"
    # Android accetta un aggiornamento solo se versionCode aumenta.
    version_code=$((version_code + 1))
done

if [ "$version_name" != "$old_name" ]; then
    sed -i '' "s/versionName = \"$old_name\"/versionName = \"$version_name\"/" "$GRADLE_FILE"
    sed -i '' "s/versionCode = $old_code/versionCode = $version_code/" "$GRADLE_FILE"
    echo "Versione $old_name già pubblicata: passo alla $version_name (codice $version_code)."
    echo
fi

tag="v$version_name"

echo "Compilazione di Hommaterial $version_name..."
./gradlew assembleRelease || fail "Compilazione non riuscita"

built_apk="app/build/outputs/apk/release/app-release.apk"
[ -f "$built_apk" ] || fail "APK non trovato in $built_apk"

apk="app/build/outputs/apk/release/Hommaterial-$tag.apk"
cp "$built_apk" "$apk"
echo

# Il tag della release viene creato sul codice presente su GitHub: perché corrisponda all'APK,
# il cambio di versione va salvato e caricato prima di pubblicare.
if [ -d .git ]; then
    if ! git diff --quiet -- "$GRADLE_FILE"; then
        git commit --quiet -m "Versione $version_name" -- "$GRADLE_FILE" || fail "Commit della versione non riuscito"
    fi
    if [ -n "$(git status --porcelain)" ]; then
        echo "ATTENZIONE: ci sono altre modifiche non salvate su git, che non finiranno nel"
        echo "codice sorgente della release $tag."
        echo
    fi
    echo "Caricamento del codice su GitHub..."
    git push --quiet || fail "Caricamento su GitHub non riuscito"
    echo
fi

echo "Pubblicazione di $tag su $REPO..."
gh release create "$tag" "$apk" \
    --repo "$REPO" \
    --title "Hommaterial $version_name" \
    --generate-notes || fail "Pubblicazione non riuscita"

# Dopo una pubblicazione riuscita si eliminano le release precedenti, tag compresi.
echo
echo "Eliminazione delle vecchie release..."
old_tags=$(gh release list --repo "$REPO" --limit 1000 --json tagName --jq '.[].tagName')
for old_tag in $old_tags; do
    [ "$old_tag" = "$tag" ] && continue
    if gh release delete "$old_tag" --repo "$REPO" --yes --cleanup-tag >/dev/null 2>&1; then
        echo "  eliminata $old_tag"
    else
        echo "  impossibile eliminare $old_tag"
    fi
done

echo
echo "Fatto: https://github.com/$REPO/releases/tag/$tag"
finish 0
