#!/usr/bin/env bash
# All 27 design states (design states[] / qaHooks.sceneTable) + variants, one qa_shot each, into $SH
# (default ../spatial-app-factory/apps/dose-distance/build/shots). The scene must be built (SCENE built in the log).
# The standard scene goes on the floor 2.4 m ahead (`qa surface=floor`): the emulator room's tables are 5 m away.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export SH="${SH:-$ROOT/../spatial-app-factory/apps/dose-distance/build/shots}"
export SETTLE="${SETTLE:-12}"
Q="bash $ROOT/tools/qa_shot.sh"
g() { echo "--es cmd goto --es s $1${2:+ --es v $2}"; }
$Q setup "--es cmd qa --es surface floor --ef x 2.4" "--es cmd lang --es id zh" >/dev/null; rm -f "$SH/setup.png"
for s in S04 S05; do $Q $s "$(g $s)"; done
$Q S05-en "$(g S05 en)"
$Q - "--es cmd lang --es id zh" >/dev/null; rm -f "$SH/-.png"
$Q S06 "$(g S06)"
$Q S06-extreme "$(g S06 extreme)"
for s in S07 S08 S09 S10 S11 S12; do $Q $s "$(g $s)"; done
$Q S12-24 "$(g S12 24)"
$Q S13 "$(g S13)"
$Q S13-two "$(g S13 two)"
$Q S13-out "$(g S13 out)"
$Q S14 "$(g S14)"
$Q S14-pass "$(g S14 pass)"
$Q S15 "$(g S15)"
$Q S15-read "$(g S15 read)"
$Q S16 "$(g S16)"
$Q S16-out "$(g S16 out)"
$Q S17 "$(g S17)"
$Q S18 "$(g S18)"
$Q S18-time "$(g S18 time)"
$Q S19 "$(g S19)"
$Q S20 "$(g S20)"
$Q S21 "$(g S21)"
for s in S22 S23 S24 S25; do $Q $s "$(g $s)"; done
$Q S26 "$(g S26)"
$Q S26-tc "$(g S26 tc)"
$Q S26-shield "$(g S26 shield)"
$Q S03 "$(g S03)"
$Q S27 "$(g S27)"
$Q S02 "$(g S02)"
$Q S01 "$(g S01)"
$Q - "--es cmd goto --es s release" "$(g S05)" >/dev/null; rm -f "$SH/-.png"
