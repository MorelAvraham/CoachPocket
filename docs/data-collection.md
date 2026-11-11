# Recording and evaluating sets

How a labelled set gets from the gym into a precision/recall table. Everything here runs with the
code in `app/`; nothing needs the cloud and no video is stored.

## 1. Record

1. Phone upright on the tripod, 2 to 3 m away, whole body in frame. For knee valgus the camera
   must be **in front** of the lifter.
2. Connect the pod (or a second phone in "Be the pod" mode) and wait for `SYNC locked`.
3. Press **Record** and fill in the dialog *before* the set:
   - **Lifter ID**: a code such as `S01`. Not a name.
   - **What the lifter will do**: clean, or the fault they will perform on purpose.
   - **Planned repetitions**: lets the tools report rep-count accuracy.
4. The lifter stands tall for a second, performs the set, stands tall again. Press **Stop rec**.

One set = one condition. That is what makes labelling cheap: every repetition in the set inherits
the set's label, so 900 repetitions do not need 900 manual annotations.

## 2. Fix the labels that are wrong

Deliberate faults do not always come out as intended. If the trainer says repetition 3 of a
"knee valgus" set was actually clean, add a `labels.csv` to that session folder:

```
rep,label
3,clean
5,knee_valgus+too_fast_descent
```

Repetitions not listed keep the set's label. Labels: `clean`, `knee_valgus`, `too_fast_descent`,
joined with `+`.

## 3. Copy the sessions off the phone

```bash
adb pull /sdcard/Android/data/com.coachpocket.app/files/sessions ./sessions
```

Each session is a folder:

| File | Content |
|---|---|
| `meta.properties` | lifter, condition, planned reps, camera view, phone, pose model, pod, thresholds in force |
| `imu.csv` | every IMU sample: phone-clock time, pod-clock time, packet arrival time, 6 axes (SI units) |
| `pose.csv` | every pose frame: capture time, result arrival time, 33 keypoints (image and world) |
| `events.csv` | what the app said live. Informational; evaluation re-runs the engine. |
| `labels.csv` | optional per-repetition corrections (step 2) |

All times are microseconds since the phone booted.

## 4. Evaluate

From `app/`:

```bash
./gradlew :tools:run --args="eval ../sessions --out ../docs/eval.md"
```

This replays every session through the same engine the phone runs, three times: camera only, pod
only, fused. Per fault it reports precision, recall and F1 (R4), and for each mode the false cues
per 10 clean repetitions (R7) and the onset-to-decision latency. The three modes are scored on
identical repetitions, so the table is the ablation of R5. A table per lifter follows.

Other commands:

```bash
./gradlew :tools:run --args="replay ../sessions/<name> --mode imu_only"
```

```bash
./gradlew :tools:run --args="link ../sessions/<name>"
```

```bash
./gradlew :tools:run --args="sync ../sessions/<name>"
```

- `replay` prints repetitions, faults and cues for one session.
- `link` is test **U1**: record 10 minutes with the pod streaming, then read sample rate, lost
  samples and packet-interval jitter.
- `sync` is test **U2**: record 5 to 10 firm taps of the pod hand on a table, in view of the
  camera, then read the alignment error.

To try the whole chain before any real data exists:

```bash
./gradlew :tools:run --args="simulate /tmp/sim"
```

```bash
./gradlew :tools:run --args="eval /tmp/sim"
```

The simulated sessions are marked `synthetic=true` and the report says so. They prove the pipeline
runs. They are not results and must not appear in a report as results.

## What the numbers do and do not mean

- **F1 is per repetition and per fault type**: did the engine raise fault X during a repetition
  labelled X. A fault whose cue was held back by the anti-nagging cooldown still counts as detected.
- **False cues count what the lifter hears**, on clean repetitions, per 10 of them.
- **Latency here ends at the decision to speak.** Audio output is not included; R6 needs the
  240 fps video test (U6).
- **The detectors are rules, not trained models**, so there is no train/test leakage to guard
  against yet: every lifter is an unseen lifter. That changes the day a classifier is trained on
  these recordings (M3). From then on the test lifter's sessions must be excluded from training.
- **Tuning thresholds on the same sets you report on is overfitting**, even without a model. Keep
  one lifter's sessions aside, tune on the others, report on the one held out.
