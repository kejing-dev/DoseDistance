#!/usr/bin/env bash
# Panel stress test, "in and out 30 times" (design verification A4 / factory rule A4):
#   S03 <-> S05 (compact <-> dosimeter) 30 times, the More menu open / close 30 times, drill 3 start / quit 30 times;
# then reports whether the process is alive. Capture S05 / S17 afterwards (qa_shot.sh) to check the glass and the
# lead-glass window still draw. tools/stress_panels.sh [ROUNDS]
set -u
DEV=${DEV:-emulator-5560}
PKG=io.embodit.dosedistance
export PATH="$PATH:${LOCALAPPDATA}/Android/Sdk/platform-tools"
rounds=${1:-30}
cmd() { MSYS_NO_PATHCONV=1 adb -s $DEV shell "am broadcast -a $PKG.DEBUG -p $PKG $1" >/dev/null 2>&1; }
adb -s $DEV logcat -c
for i in $(seq 1 "$rounds"); do
  cmd "--es cmd goto --es s S03"; sleep 0.6
  cmd "--es cmd goto --es s S05"; sleep 0.6
done
echo "S03<->S05 x$rounds"
for i in $(seq 1 "$rounds"); do
  cmd "--es cmd ui --es s more"; sleep 0.4
  cmd "--es cmd ui --es s more"; sleep 0.4
done
echo "menu x$rounds"
for i in $(seq 1 "$rounds"); do
  cmd "--es cmd ui --es s drill_shield"; sleep 0.5
  cmd "--es cmd ui --es s drill_quit"; sleep 0.5
done
echo "drill start/quit x$rounds"
adb -s $DEV shell pidof $PKG >/dev/null && echo "process alive" || echo "PROCESS DIED"
adb -s $DEV logcat -d 2>/dev/null | grep -E "FATAL EXCEPTION|Process: $PKG" | head -3
echo "S22 entries: $(adb -s $DEV logcat -d -s DoseDistance:V 2>/dev/null | grep -c 'STATE S22')  S16 entries: $(adb -s $DEV logcat -d -s DoseDistance:V 2>/dev/null | grep -c 'STATE S16')"
n=$(adb -s $DEV shell "dumpsys window windows | grep -c 'Application Not Responding'" | tr -d '\r'); echo "anr dialogs=$n"
