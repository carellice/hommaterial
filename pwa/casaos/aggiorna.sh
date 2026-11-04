#!/usr/bin/env bash
# Aggiorna Hommaterial (PWA) all'ultima versione pubblicata su GitHub. Non fa domande, quindi va bene
# anche per il timer notturno che installa.sh può attivare (hommaterial-aggiorna.timer).
#
#   sudo hommaterial-aggiorna              aggiorna solo se c'è una versione nuova
#   sudo hommaterial-aggiorna --controlla  dice soltanto se c'è una versione nuova
#   sudo hommaterial-aggiorna --forza      ricostruisce Hommaterial anche se la versione è la stessa
#
# La versione è quella di pwa/server/package.json: cambiarla su GitHub è il rilascio.
# Prima di aggiornare salva una copia del database; se la nuova versione non parte, torna a quella di prima.

set -euo pipefail

REPO="carellice/hommaterial"
BRANCH="${APP_BRANCH:-main}"
APP="hommaterial"
IMAGE="hommaterial-pwa:latest"
NEW_IMAGE="hommaterial-pwa:nuova"
OLD_IMAGE="hommaterial-pwa:precedente"
KEEP_BACKUPS=5
DB_NAME="hommaterial.db"

# Gira dentro il container: copia accounts.db e il database di ogni account nella cartella indicata.
BACKUP_JS="const s=require('node:sqlite'),fs=require('node:fs'),p=require('node:path');
const out=process.argv[1];fs.mkdirSync(out,{recursive:true});
const dbs=[['accounts.db','/data/accounts.db'],['$DB_NAME','/data/$DB_NAME']];
for(const id of fs.existsSync('/data/users')?fs.readdirSync('/data/users'):[])dbs.push(['user-'+id+'.db','/data/users/'+id+'/$DB_NAME']);
(async()=>{for(const[n,f]of dbs){if(!fs.existsSync(f))continue;const d=new s.DatabaseSync(f);await s.backup(d,p.join(out,n));d.close()}})()
.catch((e)=>{console.error(e);process.exit(1)})"

if [ -t 1 ]; then
  B=$'\e[1m'; R=$'\e[0m'; V=$'\e[35m'; G=$'\e[32m'; Y=$'\e[33m'; E=$'\e[31m'
else
  B=""; R=""; V=""; G=""; Y=""; E=""
fi
step() { printf "${V}${B}==> %s${R}\n" "$1"; }
info() { printf "    %s\n" "$1"; }
ok() { printf "    ${G}✓${R} %s\n" "$1"; }
warn() { printf "    ${Y}!${R} %s\n" "$1"; }
fail() { printf "${E}${B}ERRORE:${R} %s\n" "$1" >&2; exit 1; }

json_version() { grep -o '"version": *"[^"]*"' | head -n1 | sed 's/.*"\([^"]*\)"$/\1/'; }
running_version() { curl -fs --max-time 5 "http://127.0.0.1:$PORT/api/session" 2>/dev/null | json_version || true; }

recreate() {
  (cd "$APP_DIR" && "${COMPOSE[@]}" --env-file "$ENV_FILE" up -d --force-recreate --remove-orphans) >/dev/null 2>&1
}

wait_for() { # wait_for versione: true quando Hommaterial risponde con quella versione
  for _ in $(seq 1 60); do
    [ "$(running_version)" = "$1" ] && return 0
    sleep 2
  done
  return 1
}

# Tutto dentro main: bash legge lo script per intero prima di eseguirlo, così può sostituire
# se stesso in /usr/local/bin mentre gira.
main() {
  local mode=update
  case "${1:-}" in
    "") ;;
    --controlla|--check) mode=check ;;
    --forza|--force) mode=force ;;
    *) fail "Opzione sconosciuta: $1 (usa --controlla oppure --forza)" ;;
  esac

  [ "$(id -u)" -eq 0 ] || fail "Lancia come amministratore: sudo hommaterial-aggiorna"
  command -v docker >/dev/null || fail "Docker non trovato."
  command -v curl >/dev/null || fail "Serve curl."
  if docker compose version >/dev/null 2>&1; then COMPOSE=(docker compose)
  elif command -v docker-compose >/dev/null; then COMPOSE=(docker-compose)
  else fail "Docker Compose non trovato."
  fi

  APP_DIR=""
  for dir in "/var/lib/casaos/apps/$APP" "/opt/$APP"; do
    [ -f "$dir/.env" ] && { APP_DIR="$dir"; break; }
  done
  [ -n "$APP_DIR" ] || fail "Hommaterial non è installata qui: usa prima installa.sh"
  ENV_FILE="$APP_DIR/.env"
  # shellcheck disable=SC1090
  set -a; . "$ENV_FILE"; set +a

  # Un aggiornamento alla volta, per esempio il timer e un lancio a mano.
  if command -v flock >/dev/null; then
    exec 9>/run/hommaterial-aggiorna.lock
    flock -n 9 || fail "C'è già un aggiornamento in corso."
  fi

  local installed latest
  installed=$(running_version)
  latest=$(curl -fsSL --max-time 30 "https://raw.githubusercontent.com/$REPO/$BRANCH/pwa/server/package.json" | json_version) \
    || fail "Non riesco a leggere l'ultima versione da GitHub. Controlla la connessione."
  [ -n "$latest" ] || fail "Versione su GitHub non trovata."
  info "Installata: ${installed:-Hommaterial non risponde} · ultima: $latest"

  if [ "$installed" = "$latest" ] && [ "$mode" != force ]; then
    ok "Hommaterial è già aggiornata"
    return 0
  fi
  if [ "$mode" = check ]; then
    info "C'è una versione nuova: aggiorna con  sudo hommaterial-aggiorna"
    return 0
  fi

  step "Scarico Hommaterial $latest"
  WORK="$(mktemp -d)"
  trap 'rm -rf "$WORK"' EXIT
  curl -fsSL --max-time 300 "https://codeload.github.com/$REPO/tar.gz/refs/heads/$BRANCH" | tar -xz -C "$WORK" \
    || fail "Download da GitHub non riuscito."
  local src version
  src="$(find "$WORK" -maxdepth 4 -type d -path "*/pwa" | head -n1)"
  [ -n "$src" ] || fail "Cartella PWA non trovata nel download."
  version=$(json_version <"$src/server/package.json")
  ok "Scaricato"

  # La nuova immagine ha un nome a parte: se la build non riesce, Hommaterial resta com'è.
  step "Preparo la nuova versione (su un Raspberry servono alcuni minuti)"
  local log="$WORK/build.log"
  if ! docker build --pull -t "$NEW_IMAGE" "$src" >"$log" 2>&1; then
    tail -n 25 "$log" | sed 's/^/    /'
    fail "Build non riuscita: Hommaterial resta alla versione ${installed:-di prima}."
  fi
  ok "Immagine pronta"

  if [ -n "$installed" ]; then
    step "Salvo una copia dei database"
    local name="$installed-$(date +%Y%m%d-%H%M%S)"
    mkdir -p "$DATA_DIR/backup"
    # Copia coerente anche a server acceso: gli account e il database di ogni account.
    if docker exec "$APP" node --disable-warning=ExperimentalWarning -e "$BACKUP_JS" "/data/backup/$name" >/dev/null 2>&1; then
      ok "Copia in $DATA_DIR/backup/$name"
      ls -1dt "$DATA_DIR"/backup/*/ 2>/dev/null | tail -n +$((KEEP_BACKUPS + 1)) | xargs -r rm -rf || true
    else
      warn "Copia non riuscita, continuo comunque."
    fi
  fi

  step "Avvio Hommaterial $version"
  local had_old=0
  if docker image inspect "$IMAGE" >/dev/null 2>&1; then
    docker tag "$IMAGE" "$OLD_IMAGE"
    had_old=1
  fi
  docker tag "$NEW_IMAGE" "$IMAGE"
  docker rmi "$NEW_IMAGE" >/dev/null 2>&1 || true
  recreate || true

  if ! wait_for "$version"; then
    if [ "$had_old" = 1 ]; then
      warn "La nuova versione non parte: torno a quella di prima."
      docker tag "$OLD_IMAGE" "$IMAGE"
      recreate || true
      docker rmi "$OLD_IMAGE" >/dev/null 2>&1 || true
    fi
    fail "Hommaterial $version non risponde. Log con: docker logs $APP"
  fi
  ok "Hommaterial $version è in funzione"

  # Pulizia: la vecchia immagine e la cache della build occupano spazio sulla scheda SD.
  docker rmi "$OLD_IMAGE" >/dev/null 2>&1 || true
  docker builder prune -f >/dev/null 2>&1 || true

  # Anche questo comando si aggiorna, per la prossima volta.
  if [ -f "$src/casaos/aggiorna.sh" ] && [ -d /usr/local/bin ]; then
    cp "$src/casaos/aggiorna.sh" /usr/local/bin/.hommaterial-aggiorna.new
    chmod 755 /usr/local/bin/.hommaterial-aggiorna.new
    mv -f /usr/local/bin/.hommaterial-aggiorna.new /usr/local/bin/hommaterial-aggiorna
  fi
  ok "Aggiornamento completato. I telefoni prendono la nuova versione alla prossima apertura."
}

main "$@"
