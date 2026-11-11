# CoachPocket app (edge device)

Android app that runs the whole pipeline on the phone: camera, pose estimation, BLE receive,
clock alignment, squat analysis and the voice cue. First slice: the demo-day MVP path, **squat
end to end** (knee valgus by camera, too-fast descent by pod, cue in the same repetition).

## Toolchain (pinned)

| Tool | Version |
|---|---|
| JDK | 17 or newer (built and tested with 21) |
| Gradle | 8.14.3 (wrapper) |
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 2.2.21 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 31 (Android 12) |
| MediaPipe Tasks Vision | 0.10.29 |
| CameraX | 1.5.3 |

All library versions live in `gradle/libs.versions.toml`.

## Build and run

```bash
cd app
./gradlew :core:test :tools:test # logic tests, no Android SDK or phone needed
./gradlew :mobile:assembleDebug  # APK in mobile/build/outputs/apk/debug/
./gradlew :mobile:installDebug   # onto a phone with USB debugging enabled
```

Every pull request that touches `app/` runs the tests, Android lint and the APK build in GitHub
Actions (`.github/workflows/app.yml`); the APK is attached to the run as an artifact.

Or open the `app/` folder in Android Studio. The first build downloads the two pose models
(lite 5.5 MB, full 9 MB) into `mobile/src/main/assets/`; they are not committed.

## Modules

```
app/
├── core/     Pure Kotlin, no Android. All the logic, unit-tested on the JVM.
│   ├── protocol/  BLE packet format, decoder, loss counters (R1)
│   ├── sync/      pod-to-phone clock alignment (R2)
│   ├── pose/      keypoint types and geometry
│   ├── squat/     rep segmentation and knee-valgus detection from keypoints
│   ├── imu/       vertical-velocity tracking and descent tempo from the pod
│   ├── cue/       cue arbitration: cooldown, priority, one at a time (R7)
│   ├── engine/    CoachEngine: glues the channels together, emits events
│   ├── session/   recorded-session file format, reader and writer
│   ├── eval/      replay, per-fault scoring, ablation, link (U1) and tap-sync (U2) analysis
│   └── sim/       SimulatedPod and synthetic sessions: the firmware's exact packets, no hardware
├── tools/    Command-line front end to core/eval. Runs on a laptop.
└── mobile/   Android app: thin shell around core.
    ├── camera/    CameraX + MediaPipe Pose Landmarker (GPU, CPU fallback)
    ├── ble/       GATT client for the pod, and a pod emulator for a second phone
    ├── audio/     pre-synthesised cue clips on a SoundPool
    ├── record/    session recorder (keypoints + IMU + events, never video)
    └── ui/        coach screen, record and threshold dialogs, pod-emulator screen
```

The split matters: `core` has no Android dependency, so a recorded session can be replayed through
exactly the code that runs on the phone, from a laptop. That is how the camera-only vs pod-only vs
fused comparison (R5) is run: see [docs/data-collection.md](../docs/data-collection.md).

## Using it

1. Tripod 2 to 3 m away, phone upright, whole body in frame. **In front of the lifter** for knee
   valgus; from the side the app still counts reps and says so on screen.
2. Stand tall for a moment (calibrates standing hip height), then lift.
3. `Connect pod` scans for the CoachPocket BLE service. Without a pod the app runs camera-only and
   measures descent tempo from the camera instead.
4. `Record` asks who is lifting and what they will do on purpose (clean, or a deliberate fault),
   then saves the set under `Android/data/com.coachpocket.app/files/sessions/`. That answer is the
   set's ground-truth label. Keypoints and pod data only, never video.
5. `Tune` changes the thresholds and switches channels (fused / camera only / pod only) without
   rebuilding. Values persist on the phone and are written into every recording.
6. `Be the pod` turns a **second phone** into a stand-in pod: it streams its own IMU over BLE in the
   pod packet format, with the time beacon. Strap it to the wrist and the BLE link, clock sync and
   fusion can be exercised today, before the breadboard pod exists. It is a development aid, not a
   substitute: phone IMUs give 200 to 500 Hz, not 1 kHz.
7. `Sim pod` feeds synthetic pod packets (two controlled squats, then one dive-bomb, repeating) to
   bench-test the receive path on one phone. It is not linked to what the camera sees.

The top panel is the measurement harness: pose fps and inference time (R3), pod sample rate and
packet loss (R1), sync state, and cue latency from fault onset to the audio play call.

## How the detection works today

| Fault | Channel | Rule |
|---|---|---|
| Knee valgus | Camera | Knee sits more than 8% of leg length inside the hip-ankle line for 3 consecutive frames, while in the rep. Frontal view only. |
| Too-fast descent | Pod | Downward speed above 0.8 m/s (caught mid-descent), or eccentric phase under 1.0 s. Only cued if the camera confirms a rep is in progress. |
| Too-fast descent (no pod) | Camera | Eccentric phase under 1.0 s, known at the bottom. |

Fusion is rule-based for now and runs both ways: the camera tells the IMU integrator when the
lifter is moving (stops false zero-velocity resets in a slow descent), and vetoes pod faults that
happen outside a rep (walking the bar out, re-racking).

## Known limits: read before trusting a number

- **Not yet run on a phone.** The logic is covered by JVM tests and the APK builds and passes
  lint, but camera, GPU inference, BLE (both roles) and audio have not been exercised on hardware.
  Expect first-run fixes.
- **Squat only.** Deadlift and overhead press are not started, on purpose: their faults have not
  been defined with the trainer, and detectors written before that would be guesses.
- **Lumbar rounding cannot be measured with this pose model.** BlazePose gives shoulders and hips
  and nothing along the spine, so a rounded back and a flat back hinged at the hip look the same.
  The README lists it as a camera fault; it needs either a different model or a different fault.
- **Every threshold is a placeholder** (`SquatConfig`, `TempoConfig`). They are reasoned defaults,
  not fitted values. They get fitted on the labelled data (U4, M2/M3) and reviewed with the trainer.
- **The latency shown is a lower bound.** It ends at the `play()` call. Speaker output adds tens of
  milliseconds, Bluetooth earphones add 100 to 250 ms. R6 can only be signed off with the 240 fps
  video test (U6).
- **Pose model is float16 on the GPU, not INT8 on the NPU.** MediaPipe's published bundles are
  float. INT8 and the Hexagon NPU are M3/M5 work and will need a different runtime (LiteRT + QNN).
- **The pod protocol is defined here and the firmware does not exist yet.** See
  `docs/pod-ble-protocol.md`; it needs BK's review before it is frozen.
- **Clock sync without the time beacon is late by the minimum BLE delay** (several ms). The
  beacon or the tap test (U2) removes it.
- The app has no `INTERNET` permission; it is stripped from the merged manifest on purpose (R8).
