#!/usr/bin/env bash
# Prints the real signatures of the Minecraft classes this mod relies on, to help fix compile errors
# after a Minecraft update. Runs after the build in CI; it does not affect the jar.
set -u
jars=$(find "$HOME/.gradle/caches/fabric-loom" .gradle/loom-cache -name "*.jar" 2>/dev/null | grep -iE "minecraft|lwjgl|sdl" | grep -v sources | sort -u)
echo "== jars"; echo "$jars"
cp=$(echo "$jars" | tr '\n' ':')
echo "== class search"
for j in $jars; do unzip -Z1 "$j" 2>/dev/null; done | grep -iE "FileDialog|tinyfd|Dialogs|JOrbis|filedrop|FilesDrop" | sort -u | head -40
for c in "$@"; do
  echo "== $c"
  javap -cp "$cp" "$c" 2>&1 | head -150
done
