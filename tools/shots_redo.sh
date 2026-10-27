#!/usr/bin/env bash
# Re-captures the states whose look changed after the first emulator pass (decal v, shield plate offset, source
# ghost on `aim`) and the timed ones with long snackbars / coachmark (S11 「吸附到位」, S21 coach, S24 「最多 2 块铅屏」).
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export SH="${SH:-$ROOT/../spatial-app-factory/apps/dose-distance/build/shots}"
export SETTLE="${SETTLE:-12}"
Q="bash $ROOT/tools/qa_shot.sh"
g() { echo "--es cmd goto --es s $1${2:+ --es v $2}"; }
$Q setup "--es cmd qa --es surface floor --ef x 2.4" "--es cmd lang --es id zh" "--es cmd snack --es v long" >/dev/null; rm -f "$SH/setup.png"
$Q S03 "$(g S03)"
for s in S09 S10 S11 S12; do $Q $s "$(g $s)"; done
$Q S13 "$(g S13)"
$Q S21 "$(g S21)"
$Q S24 "$(g S24)"
$Q S26-shield "$(g S26 shield)"
$Q - "--es cmd snack --es v normal" "$(g S05)" >/dev/null; rm -f "$SH/-.png"
