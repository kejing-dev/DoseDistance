# Dose Distance (剂量与距离)

Radiation-protection teaching app for PICO OS 6. Kotlin, PICO Spatial SDK 6.1.9, one Mixed Stage (Full Space,
passthrough), package `io.embodit.dosedistance`. A virtual source stands on your real desk; the dose rate at your
head follows the inverse square as you step closer or back; a life-size 1.8 m mobile lead shield casts a green
"safe shadow" on the floor, and standing in it drops the reading. Three drills: distance, time, shielding.

Design package (binding): `../spatial-app-factory/apps/dose-distance/design-package.json` (revision 3, archetype D,
STAGE_MIXED, single_panel_with_popup, states S01 - S27, rules, drills, panels, entities, palette, copy). Approved
sample (gate B, no comments; every open item at its default: drill 2 gaze = head direction looking into the pot,
budgets 2 / 5 / 10 µSv, no low panel, no fourth isotope): `../spatial-app-factory/apps/dose-distance/sample.html`.
Skeleton: cam-coverage `../cam-coverage` (itself from Theater Planner / Spatial Ruler `../spatial-ruler`): panel pool
(PanelRig), Stage root, plane colliders + backstop, system-drag conversion (DragLine), lazy follow, theme and fonts,
debug receiver, thumbstick input. Reference repos were only read.

Built by the factory's build station on 2026-10-25 (v0.1.0, debug). Not yet run on the emulator or a headset (this
round only built the APK; see "Not verified").

## Structure

```
app/src/main/java/io/embodit/dosedistance/
  Main.kt                    DefaultStage { DoseTheme { DoseScreen() } }
  domain/                    pure Kotlin, unit-tested
    Vec3.kt Geometry.kt      vectors, quaternions, P2, HeadPose, PanelFacing, PlaneSurface (two-sided ray hits)
    Dose.kt                  Isotope (h1, HVL), Zone, Dose (rate, attenuation, blocked %, formats, Geiger rate),
                             SourceFrame (QA coordinates), Shield (segment / panel crossing), Reading, Shadow (the
                             safe-shadow quad), Rings (72 segments x 3), Drills (targets, criteria, spot, gaze)
    Settings.kt DragLine.kt  preferences; system-drag model (Spatial Ruler round 5c)
  data/SettingsStore.kt      SharedPreferences (isotope, default thickness, Geiger, rings, language, coach seen)
  platform/                  DdGraph (services + debug head / aim), DdLog, HeadTracker, ControllerStick, PlaneTracker,
                             audio/Geiger (AudioTrack clicks / tone), LaunchActivity, SpatialApplication
  ui/dose/                   DoseUiState (+ designState S01..S27), DoseEvent, DoseViewModel (state machine, drills),
                             DoseScreen
  ui/dose/scene/             DoseScene (ECS: one registered System; per-frame dose at the head, 10 Hz Tick, rings and
                             shadows <= 10 Hz, taps, drags + snap, thumbstick turn, panel placement), Views (SourceView,
                             ShieldView, RingsView, ShadowView, AidsView), SceneExtras (materials, meshes, decal bitmaps,
                             MeshBuilder, DynMesh), SceneKit, PanelRig
  ui/dose/components/        SpatialUI panels: DosimeterPanel (+ CompactPanel), Overlays (menu, dialogs, snackbar,
                             coach, result card, plates), DoseStage (SpatialView + attachments), Common, Strings
  ui/theme/                  DoseTheme (PicoTheme + 4 semantic roles + Noto Sans / Noto Sans SC subset), DdColors
app/src/debug/               DebugCommandReceiver + DebugCommands (QA hooks)
app/src/test/                DoseModelTest (design model.unitTests: rates, shielding, standard scene, isotopes,
                             formats, six shadow cases + slanted-panel cross-check, ring segment, drill criteria)
tools/                       make_icons.py (vector icons, launcher, 3D icon layers, 512 export), subset_cjk_font.py
```

One direction of data: panel controls, scene taps / drags, the frame system's conclusions -> `DoseViewModel.onEvent`
-> `DoseUiState` -> `DoseScene.render` (entities, panels). Per frame (`DoseScene.onFrame`, the registered
`DoseFrameSystem`): head pose (or the injected debug head), H = h1 / max(0.3, d)² × Π 2^(−t/HVL) + 0.1 with the
plates the source -> head segment crosses, dose integrated, gaze on the pot, Geiger rate every 0.25 s, live drags
and the thumbstick turn; a `Tick` goes to the ViewModel every 100 ms (reading, dose since the last tick, judged head,
gaze ms). Rings (3 x 72 segments, one mesh per zone colour) and shadows rebuild when the source, the shields, the
isotope or the head height (> 10 cm) change, at most every 100 ms, through the mesh queue (2 meshes per frame:
cam-coverage's ANR lesson).

## What the room contains (design `entities3d`)

- Source (`SourceView`): lead pot Ø 12 cm (0 - 10 cm) + 2 cm rim tube around an Ø 8 cm opening, dark opening floor,
  magenta core Ø 2 × 2 cm flush with the rim (core point = surface + 0.11), yellow band with a run-time trefoil decal
  facing the user, the 3-digit code decal on the opening floor (far half, upright for a user looking down from the
  front; "— — —" outside drill 2). Yellow ring on the surface when selected, grey while drill 3 locks it.
- Shield (`ShieldView`, ≤ 2): base 0.60 × 0.05 × 0.40, lead panel 0.05 - 1.20, lead-glass window 1.20 - 1.85
  (glassBlue 35 %), posts, "Pb" patch; drawn ≥ 10 mm thick; a passable 15 % overlay while the source -> head segment
  crosses it; yellow frames on the panel and around the base when selected.
- Rings (`RingsView`): 1 / 2 / 3 m, 1.5 cm wide, 0.5 cm off the floor, every 5° segment coloured by the rate a head at
  the current height reads there (with the shields), dashed inside a shadow. Menu switch "show rings".
- Safe shadow (`ShadowView`, per shield): the panel's foot segment scaled from the source by k_near .. k_far, where
  x(y)/L = (y_h − y_s)/(y − y_s) is the same ratio for every panel point; cut at 4 m (scaled by the centre's
  distance), three height cases of design `model.shadow`. Passable 22 % fill + 1 cm dashed edge 60 %.
- Aids (`AidsView`): source ghost (30 %) + landing ring (green on TABLE / FLOOR, grey elsewhere) following the head
  gaze, the nearest table's Ø 20 cm snap circle (solid when snapped); the shield's 0.60 × 0.40 snap frame (dashed,
  solid + shake when snapped, heading line while aiming); drill 3's Ø 0.6 m spot; the drag follow line (S09).

## Interaction (design `rules`, `interactions`; taste profile D)

- Ready (a FLOOR plane) -> aiming the source at once: tap a TABLE / FLOOR = place (within 15 cm of a table centre =
  centre, otherwise pushed 7 cm inside the table); tap anything else = cancel -> compact panel with [放源] (S27). The
  frame of the QA coordinates is set at placement (x = towards the user).
- Tap the source / a shield = select (outline + dosimeter page); tap empty = deselect (menu open: closes the menu
  first). Drag = slide on its surface (`DragLine`, the pointing line meets the surface level); table source clamped
  inside the table, snaps to its centre.
- Shield: [放铅屏] -> tap the floor; the snap point is 0.5 m from the source towards you (drill 3: the source - spot
  midpoint); within 10 cm the shield snaps there and faces the source (snackbar 「吸附到位」, sound), released beyond
  15 cm while dragging. Max 2 (a third press only shows 「最多 2 块铅屏」). Thickness 1 / 3 / 6 / 12 / 24 mm on the
  selected / last placed shield (or the next one).
- Thumbstick X turns the selected source / shield about its vertical: dead zone 0.2, up to 90°/s, 0.1 s low-pass,
  committed 150 ms after the stick returns; hint 「摇杆左右转动」 on the source page and 「摇杆左右转动 · 按住拖动」 on
  the shield page.
- Drills: 1 distance (shields hidden first, start read at ≥ 0.5 m, target a quarter (background kept), hold 3 s,
  budget 2 µSv, "x.x m to go" = √(4 d0² − Δy²) − r); 2 time (random code in the pot, read = head ray within 5 cm of
  the opening centre for 1 s at ≤ 0.8 m, then back to the start reading, budget 5 µSv, 60 s to read); 3 shielding
  (spot at your floor point clamped to 1.0 - 2.5 m, source locked, pass = A ≤ 0.1275 for 3 s inside the spot, out
  of the spot 0.5 s pauses + 「回到圈里」, budget 10 µSv). The panel stays on the Drills page, isotope / reset /
  remove / clear are disabled. Result card: again / next (done) / not now.
- Tracking lost: panel greyed, reading "—", Geiger stops, drill clock paused. Background: every tracker and the
  Geiger thread stop.

## Panels (all SpatialUI inside PicoTheme; no Material)

Pooled attachment panels, declared once with fixed slot sizes: `dosimeter` 400 × 444 and `compact` 400 × 120 at 0.75
(lazy follow 28° left, 30° down, 0.6 m), `menu` 300 × 296 (above the dosimeter; Switch × 2, language segment, About,
Clear all, Exit), `dialog` 400 × 300 (clear / unavailable / About, 0.8 m ahead), `snack` 360 × 56 (opaque hintInk,
head follow 14° up), `coach` 360 × 160 (30 cm above the source, once), `result` 360 × 220 (opaque hintInk, 0.8 m,
12° below the gaze), plates 240 × 40 (`plateSource` 12 cm above the pot, `plateShield1/2` at the window's lower edge on
your side, `plateRing0..2` outside each ring on the user side at placement, `plateSpot`), constant visual size. The
footer 「教学近似，非剂量评估」 is always on the dosimeter. Copy in `res/values` (en) and `res/values-zh`, read through
`rs()` so the in-app language switch works without a restart. Fonts: Noto Sans + Noto Sans SC subset (294 chars;
re-run `python tools/subset_cjk_font.py --source ../fire-plan/app/src/main/res/font/noto_sans_sc.ttf` after
changing zh copy).

## Debug commands (debug build only)

```
adb -s <serial> shell "am broadcast -a io.embodit.dosedistance.DEBUG -p io.embodit.dosedistance --es cmd NAME [extras]"
```

Coordinates: x / z relative to the source core's foot point, y relative to the core, metres; x = from the source
towards the user at placement; shield yaw 0 faces +x (the user), 180 faces the source.

| cmd | extras | effect |
|---|---|---|
| `goto` | `--es s S01..S27 [--es v VARIANT]` | that design state with the scene table (standard scene: source on the centre of the table nearest to you, Cs-137, head injected) |
| `state` | (none) | print the `STATE` line (H, shown value, zone, d, A, blocked %, cumulative, head, shields, drill) |
| `place` | `--es surface table\|floor [--ef x --ef z --ef yaw]` | place the source (table centre / floor 1.2 m ahead + offsets) |
| `head` | `--ef x --ef y --ef z [--ef pitch -65]` / `--es v off` | inject the head relative to the source core (every judgement: dose, rings, shadows, drills, gaze) |
| `shield` | `--ei n 1\|2 --ef x --ef z --ef yaw [--ei t 6]` | place / move a shield through the drag-release path (snaps within 10 cm) |
| `thickness` | `--ei n 1 --ei t 24` | set a shield's thickness |
| `iso` · `lang` | `--es id tc99m\|cs137\|co60` · `--es id zh\|en` | isotope; language |
| `drill` | `--es id distance\|time\|shield --es do start\|pass\|fail\|code\|read` | start; force the result card; print the code; count the code as read |
| `dose` | `--ef cum 4.8` | set the drill's (or the total) cumulative dose |
| `drag_pose` | `--es what source\|shield --ei on 1\|0` | the dragging look without a drag (S09) |
| `lost` | `[--ei on 0]` | tracking lost on / off |
| `aim` | `--ef x --ef z` / `--es v off` | where the shield-aiming frame lands (S08) |
| `stick` | `--es a x,ms` | the thumbstick reads x for ms (emulator QA of the turn) |
| `select` `--es what source\|shield [--ei n]` · `tap` · `ui --es s CONTROL` · `coach_reset` · `snack --es v long` | | |
| `dump` · `pose` · `panels` · `planes` · `bounds` · `hidemode --es s auto\|park` | | reading back |

Variants: `S05 v=en` English · `S06 v=extreme` head (0.2, 0.15, 0) · `S12 v=24` · `S13 v=two` (12 + 12 mm, 4.3) /
`v=out` (head (0.6, 0.85, 0.8), 54.1) · `S14 v=pass` head to (2.5, 0.85, 0) · `S15 v=read` head (0.2, 0.55, 0) pitch −65
· `S16 v=out` head (1.6, 0.85, 0) · `S18 v=time` · `S26 v=tc` / `v=shield` · `goto release` lets go of held states.

Control names: `more place_source remove_source place_shield delete_shield thickness_minus thickness_plus reset
tab_source tab_shield tab_drill iso_tc99m iso_cs137 iso_co60 drill_distance drill_time drill_shield drill_quit
result_again result_next result_later menu_geiger menu_rings lang_zh lang_en menu_about menu_clear menu_exit
dialog_cancel dialog_ok dialog_confirm blocked_exit blocked_retry coach_ok`.

Log tag `DoseDistance`: `STATE Sxx` on every design-state change, `PLANES`, `PLACE`, `MOVE`, `SELECT`, `SNAP`, `TAP`,
`DRAG`, `STICK`, `DRILL`, `SNACK`, `GEIGER`, `TEXTURE`, `CMD`, `DUMP`, `POSE`, `PANEL`, `FRAME` (every 5 s, with H and
the Geiger click count).

## QA screenshot list (design `states[].qaCapture`)

S01 spinner · S02 scanning · S03 aiming text, ghost + table circle · S04 7.3 · Low, three rings and plates · S05 54 ·
Medium (+ `v=en`) · S06 610 · High / 1.03 mSv/h · Extreme with the red nameplate capsule · S07 source selected,
「摇杆左右转动」 · S08 shield frame at (0.6, 0) · S09 lifted shield, dashed snap frame, follow line · S10 slanted shield
(1.2, 0.6) yaw 30, shadow, reading 54 · S11 snapped shield, 「吸附到位」 · S12 shield page (`v=24`: 92 %) · S13 28.6,
47 %, green overlay (`v=two` 4.3 / 92 %, `v=out` 54) · S14 distance drill · S15 code in the pot (`v=read`: 「读到了 · 退！」)
· S16 spot + fixed snap frame (`v=out`: 「回到圈里」) · S17 pass card 3.7 layers, 54 -> 4.3 · S18 fail card · S19 lost ·
S20 unavailable dialog · S21 coach above the source · S22 menu · S23 clear dialog · S24 「最多 2 块铅屏」 · S25 About ·
S26 Co-60 203 / rings 203 · 74 · 36 (`v=tc`: 12, rings 12 · 4.3 · 2.2). Panel screenshots: take two in a row (the
first often shows the previous frame); the idle emulator runs at 1 - 2 fps (theater-planner `tools/harness/emu.sh`).

"In and out 30 times" self-test (design `verification`, A4): `goto S03` / `goto S04` alternately 30 times,
`ui more` 30 times, `drill … start` / `ui drill_quit` 30 times, then capture S05 and S17: no black blocks on the
dosimeter, snackbar and result card, the lead-glass window still translucent.

## Build

```
# PowerShell (Git Bash fails on gradlew: CRLF)
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug --no-daemon
.\gradlew.bat testDebugUnitTest --no-daemon
adb -s <serial> install -r app\build\outputs\apk\debug\app-debug.apk
adb -s <serial> shell monkey -p io.embodit.dosedistance -c android.intent.category.LAUNCHER 1
```

Material gate: `grep -rEf ../spatial-app-factory/bin/material-grep.txt --include=*.kt --include=*.kts app/src` must be
empty. Icons: `python tools/make_icons.py [export.png]`.

## Deviations from the design package (recorded in the factory's state.json)

- The compact panel (400 × 120) and the full dosimeter (400 × 444) are two pooled slots at the same place instead of
  one panel that changes height (pooled panels have fixed sizes).
- The shield plate sits at the window's lower edge on the side facing you (the design says the panel's front, which
  faces the source after snapping and would put the plate behind the panel).
- The 3-digit code lies on the far half of the opening floor (the design's "half away from the core" is ambiguous:
  both halves are equally far from a centred core); it is upright for a user looking down from the front.
- Drill 2's gaze target is a Ø 10 cm sphere at the opening's centre (the head ray must pass within 5 cm): the design's
  QA pose (0.2, 0.55, 0) pitch −65 misses a flat Ø 10 cm disc by 2 mm.

## Emulator findings (emulator-5560, Pico_Emulator_6_1_fresh, 2026-10-26)

- 6 clean starts watched 60 - 70 s each: no "isn't responding". The scene builds in ~33 s on the idle emulator
  because meshes are created one per 2.5 s while frames are slow (`pace`, `runMeshWork`; sibling apps hit startup
  ANRs creating many meshes at once).
- All 27 states captured with `tools/shots_all.sh` (+ `tools/shots_redo.sh` for the timed ones) into
  `../spatial-app-factory/apps/dose-distance/build/shots/`; every STATE line matches design `model.unitTests`.
  `qa surface=floor x=2.4` puts the standard scene 2.4 m ahead on the floor: this room's TABLE planes are 5 m away at
  the window. The big dark disc on the floor in every shot is the room's rug, not app content.
- Camera walk with the head injection off (`head v=off`, `emu.sh key w/s`): 13.3 -> 51.2 -> 5.7 µSv/h at d 2.66 /
  1.35 / 4.08 m (inverse square).
- Decal quads: the engine samples v from the bottom (`Pb` drew as `bP` with v = 0 at the top); fixed in
  `ExtraMeshes.unitQuadUv`. SpatialUI SegmentItem draws ~48 dp: segments are 48 dp high.
- Stress (`tools/stress_panels.sh 30`): compact <-> dosimeter, menu and drill start / quit 30 times each, no ANR,
  the glass and the lead-glass window still draw (`shots/stress-after-*.png`).
- The emulator window is minimized (UIA camera buttons not found), so no camera pitch: floor content close to the
  viewer is out of frame; posted W/S keys still move the camera (with `emu.sh fast on`).
- Install over a running Stage: `am force-stop io.embodit.dosedistance` first (`tools/cold_start.sh N install`).

## Not verified

On a headset: the walk itself, Geiger audio (the AudioTrack runs, `geiger` click count rises in FRAME lines; not
listened to), the controller thumbstick (the emulator's controller is DEVICE_NOT_SUPPORTED; `stick` debug only), real
pinch drags of the source and the shields, the 1.8 cm pot code's readability when looking down (on the emulator the
pot is 2.4 m away), plane detection of a real desk.
