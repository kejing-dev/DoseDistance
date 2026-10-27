#!/usr/bin/env bash
# v0.1.1 audit shots (taste profile D14 / D15): floor map, shield list, inside-the-shield fade, bring to front, menu,
# dialogs. The idle / loaded emulator runs at 1 - 3 fps and the mesh queue then builds one mesh per 2.5 s: a full
# floor-map rebuild (8 meshes + rings + shadows) needs ~45 s, hence SETTLE=45.
#   DEV=emulator-5566 SH=<dir> bash tools/shots_v011.sh
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export SETTLE="${SETTLE:-45}"
Q="bash $ROOT/tools/qa_shot.sh"
g() { echo "--es cmd goto --es s $1${2:+ --es v $2}"; }
$Q - "--es cmd qa --es surface floor --ef x 2.4" "--es cmd lang --es id zh" "--es cmd snack --es v long" >/dev/null; rm -f "$SH/-.png"
$Q S04 "$(g S04)"
$Q S13-two-list "$(g S13 two)" "--es cmd ui --es s tab_shield"
$Q S07 "$(g S07)"
$Q S22 "$(g S22)"
$Q S16 "$(g S16)"
$Q S08 "$(g S08)"
$Q S24 "$(g S24)"
$Q S20 "$(g S20)"
# walk into a shield: shield 1 15 cm in front of the real eyes -> see-through; then bring it in front from the list
$Q inside "$(g S13)" "--es cmd ui --es s tab_shield" "--es cmd inside --ei n 1" "--es cmd fade"
SETTLE=8 $Q front "--es cmd ui --es s shield_front_1" "--es cmd fade"
$Q - "--es cmd snack --es v normal" >/dev/null; rm -f "$SH/-.png"
