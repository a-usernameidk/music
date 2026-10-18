#!/usr/bin/env bash
# Starts a real dedicated server with the mod, waits until the world has loaded, then fails if the log shows
# any problem with the mod's recipes, advancements, loot tables, jukebox song, mixins or registries.
set -u
mkdir -p run
echo "eula=true" > run/eula.txt
printf 'online-mode=false\nlevel-type=minecraft\\:flat\nspawn-protection=0\n' > run/server.properties

./gradlew runServer --args="--nogui" > server.log 2>&1 &
gradle_pid=$!

started=false
for _ in $(seq 1 300); do
  if grep -q 'Done (' server.log; then started=true; break; fi
  if ! kill -0 "$gradle_pid" 2>/dev/null; then break; fi
  sleep 2
done

# Give data loading a moment to finish logging, then shut everything down.
sleep 5
pkill -f 'net.fabricmc.devlaunchinjector' 2>/dev/null || true
pkill -f 'KnotServer' 2>/dev/null || true
kill "$gradle_pid" 2>/dev/null || true
sleep 2

problems=$(grep -nE 'custom_discs|custom-discs|CustomDiscs' server.log | grep -iE 'error|exception|fail|could ?n.t|invalid|unknown|missing' || true)
mixin=$(grep -nE 'Mixin (apply|transformation) failed|InvalidInjectionException|InjectionError' server.log || true)

echo "== server log (mod lines)"
grep -nE 'custom_discs|custom-discs|CustomDiscs' server.log | head -50

if [ "$started" != true ]; then
  echo "::error::The server did not finish starting"
  tail -80 server.log
  exit 1
fi

if [ -n "$problems" ] || [ -n "$mixin" ]; then
  echo "::error::The server logged problems with the mod"
  echo "$problems"
  echo "$mixin"
  exit 1
fi

echo "Server started cleanly with the mod."
