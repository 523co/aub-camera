# Aub Camera

Aub Camera is a camera app for Android, built on [Open Camera](https://opencamera.org.uk/) by Mark Harman.
It keeps **every Open Camera feature** and adds four new photo modes:

| Mode | What it does |
|---|---|
| **Portrait** | On-device subject segmentation (MediaPipe) separates the subject from the background, refines the edge against the photo (guided filter, so hair and fingers stay crisp), then renders a lens-like background blur with glowing bokeh highlights and no halo. Works with people first, and falls back to a general model for pets and objects. Works on every lens, including the front camera. |
| **CPL filter** | Two ways to use it (Settings → Aub Camera modes → Filter): **Simulated** imitates a circular polariser in software: deeper blue sky, reduced specular glare on water/glass/leaves, less haze, richer foliage. **Physical filter attached** shows a live *glare meter* in the viewfinder: rotate your clip-on CPL slowly and stop when it says "best". **Physical + simulated boost** does both. |
| **PS2** | Makes a photo look like a frame from a sixth-generation games console: 448-line framebuffer, un-antialiased edges, flattened "low-res texture" surfaces, warm bloom, composite-video colour bleed, 15-bit colour with Bayer dithering, 3x integer upscale with optional CRT scanlines. |
| **2000s** | Early-2000s compact digicam: 2 MP output, soft cheap lens, over-sharpened halos, punchy CCD colour with a cool cast, early hard highlight clipping, purple fringing, blotchy shadow chroma noise, vignetting, JPEG crunch, and the classic orange seven-segment date imprint (today's date, a retro '04 year, or off). |

All four show an approximate **live preview of the look in the viewfinder** (can be turned off), and save
with a filename suffix (`_Portrait`, `_CPL`, `_PS2`, `_Digicam`). Everything runs on the phone; nothing is
uploaded.

> A note on CPL: a real polariser works optically, before light reaches the sensor, so software can only
> imitate its look. The *Physical filter attached* option is there for when you want the real thing.

## Everything from Open Camera

Auto-level, manual focus/ISO/shutter/white balance, RAW (DNG), HDR, DRO, noise reduction / night mode,
panorama, fast burst, exposure and focus bracketing, camera vendor extensions, face detection, histogram,
zebra stripes, focus peaking, pre-shots, timer and repeat modes, voice/audio/remote control, Bluetooth LE
remotes, photo stamps, geotagging, video with log profiles, slow motion and time-lapse, and more. See the
[Open Camera manual](https://opencamera.org.uk/help.html) for those features.

## Installing

Every push to `main` is built by GitHub Actions. On your phone, open this repository's **Releases** page,
download `AubCamera.apk` from the "latest" release, and open it (allow "install unknown apps" for your browser
when asked). New builds install as updates over the old one.

## Building

Requirements: Android Studio (or the Android SDK command line tools) with JDK 17, and internet access the
first time you build.

```sh
git clone https://github.com/523co/aub-camera.git
cd aub-camera
./gradlew assembleDebug        # APK ends up in app/build/outputs/apk/debug/
```

The first build downloads the two MediaPipe segmentation models into
`app/src/main/assets/aub_models/`. They aren't committed to the repository.

To install on a phone with USB debugging enabled: `./gradlew installDebug`, or copy the APK across and open it.
Aub Camera has its own application id (`com.aubergineux.aubcamera`), so it installs alongside Open Camera.

Minimum Android version: 7.0 (API 24, required by MediaPipe). Native code is included for arm64 phones and the
x86_64 emulator.

## Where the new code lives

* `app/src/main/java/net/sourceforge/opencamera/aub/` - the effects. Everything except `AubEffects` and
  `PortraitSegmenter` is plain Java with no Android dependencies, so the algorithms can be run and tested on a
  desktop JVM.
  * `CplFilter` - simulated polariser and the glare score used by the physical-filter meter
  * `Ps2Filter` - console look
  * `DigicamFilter` - 2000s digicam look and the seven-segment date imprint
  * `PortraitCompositor` - mask refinement, bokeh rendering and full-resolution compositing
  * `PortraitSegmenter` - MediaPipe segmentation (selfie multiclass, with DeepLab v3 as fallback)
  * `AubEffects` - settings, Bitmap glue, and the live viewfinder preview
* Photo modes are wired into `MyApplicationInterface`, `ImageSaver` (applied in `postProcessBitmap`),
  `PopupView`, `Preview` and `DrawPreview` (live preview and glare meter).
* Settings: `res/xml/preferences_sub_aub_modes.xml` / `PreferenceSubAubModes`.

Large photos are processed in row strips, so the effects never hold a second full-resolution copy of the photo
in memory.

## Licence

Aub Camera is a modified version of Open Camera. Open Camera is © 2013–2025 Mark Harman; modifications
© 2026 Aubergine-UX. Both are released under the **GNU General Public License v3 or later** - see
[gpl-3.0.txt](gpl-3.0.txt). Mark Harman is not responsible for this modified version; please report Aub Camera
problems here rather than to Open Camera.

Third-party components:

* [MediaPipe Tasks](https://github.com/google-ai-edge/mediapipe) - Apache License 2.0
* MediaPipe image segmentation models (SelfieMulticlass, DeepLab v3), downloaded at build time - see their
  model cards on the [MediaPipe image segmentation page](https://ai.google.dev/edge/mediapipe/solutions/vision/image_segmenter)
* AndroidX libraries - Apache License 2.0
* Google Material Design icons - Apache License 2.0

PlayStation is a registered trademark of Sony Interactive Entertainment. Aub Camera is not affiliated with or
endorsed by Sony; "PS2" describes the visual style only.

## Privacy

See [PRIVACY.md](PRIVACY.md).
