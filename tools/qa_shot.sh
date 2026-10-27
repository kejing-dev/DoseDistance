#!/usr/bin/env bash
# One design state -> one screenshot: tools/qa_shot.sh NAME ["extras"...]
#   tools/qa_shot.sh S13 "--es cmd goto --es s S13"
#   tools/qa_shot.sh S13-two "--es cmd goto --es s S13 --es v two"
#   tools/qa_shot.sh x "--es cmd ui --es s more"      (any commands, one broadcast per argument)
# Sends the broadcasts, un-throttles the emulator to 60 fps (harness `fast on`) so the mesh queue and the pooled
# panels catch up, captures a full frame with the emulator console after SETTLE s, throttles again.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEV=${DEV:-emulator-5560}
PKG=io.embodit.dosedistance
export PATH="$PATH:${LOCALAPPDATA}/Android/Sdk/platform-tools"
H="bash $ROOT/tools/harness/emu.sh"
export DEV
SH="${SH:-$ROOT/.scratch/shots}"; mkdir -p "$SH/emu"
name="$1"; shift
cmd() { MSYS_NO_PATHCONV=1 adb -s $DEV shell "am broadcast -a $PKG.DEBUG -p $PKG $1" >/dev/null 2>&1; }
cap() { before=$(ls -1 "$SH/emu" 2>/dev/null | wc -l); adb -s $DEV emu screenrecord screenshot "$(cygpath -w "$SH/emu")" >/dev/null 2>&1
  for i in $(seq 1 12); do sleep 0.5; after=$(ls -1 "$SH/emu" 2>/dev/null | wc -l); [ "$after" -gt "$before" ] && break; done; sleep 0.5
  newest=$(ls -1t "$SH/emu"/*.png 2>/dev/null | head -1); [ -n "$newest" ] && mv -f "$newest" "$SH/$1.png" && echo "OK $SH/$1.png" || echo "capture FAILED"; }
adb -s $DEV logcat -c
for c in "$@"; do cmd "$c"; sleep "${GAP:-0.4}"; done
sleep "${PRE:-1.5}"
$H fast on >/dev/null 2>&1
sleep "${SETTLE:-9}"
cmd "--es cmd state"
sleep 0.5
cap "$name"
$H fast off >/dev/null 2>&1
adb -s $DEV logcat -d -s DoseDistance:V 2>/dev/null | grep -E "STATE |FATAL|CMD goto" | tail -2 | cut -c32-420
