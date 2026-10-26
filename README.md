<div align="center">

<img src="fastlane/metadata/android/en-US/images/icon.png" width="112" alt="Aub Camera icon">

# Aub Camera

**Open Camera, plus five new ways to take a photo.**<br>
Portrait bokeh · polariser · PS2 · 2000s digicam · disposable film. All on-device, no ads, no tracking.

[![Build APK](https://github.com/523co/aub-camera/actions/workflows/build-apk.yml/badge.svg)](https://github.com/523co/aub-camera/actions/workflows/build-apk.yml)
[![Download APK](https://img.shields.io/badge/download-AubCamera.apk-7b3fa0?logo=android&logoColor=white)](https://github.com/523co/aub-camera/releases/download/latest/AubCamera.apk)
[![Android 7.0+](https://img.shields.io/badge/Android-7.0%2B-3ddc84?logo=android&logoColor=white)](#building)
[![Licence: GPL v3+](https://img.shields.io/badge/licence-GPL%20v3%2B-blue)](gpl-3.0.txt)

[**Download**](https://github.com/523co/aub-camera/releases/download/latest/AubCamera.apk) ·
[Modes](#-the-five-new-modes) ·
[Building](#building) ·
[Privacy](PRIVACY.md)

</div>

---

Aub Camera is a camera app for Android, built on [Open Camera](https://opencamera.org.uk/) by Mark Harman.
It keeps **every Open Camera feature**, wraps it in a clean [GrapheneOS Camera](https://github.com/GrapheneOS/Camera)
style screen, and adds five photo modes plus a QR / barcode scanner.

## 📷 The camera screen

Laid out like GrapheneOS Camera: flash on the left, a ⌄ button in the middle for quick settings, and the
settings gear on the right. The viewfinder sits below them. At the bottom are the zoom chips (0.5× / 1× /
2× / 5×, as the phone allows), then a strip of **mode tabs**:
Portrait · CPL · PS2 · 2000s · 35mm · Photo · Video · Scan (and Night, on phones with a vendor night mode).
Tap a tab or swipe across the viewfinder to change mode. Below them are the round gallery thumbnail, the
shutter and the switch camera button. The screen stays in portrait and the icons turn to stay upright.
The other Open Camera photo modes (HDR, DRO, panorama, bursts...) are still in the quick settings and count as Photo.

**Scan** reads QR codes, Data Matrix, Aztec, PDF417 and common barcodes, on the phone, from the square in
the middle of the viewfinder. You can then open a link, copy the text, or share it.

## ✨ The five new modes

<table>
<tr>
<td width="50%" valign="top">

### 🌸 Portrait
On-device subject segmentation (MediaPipe) separates the subject from the background, then refines the edge
against the photo with a guided filter, so hair and fingers stay crisp. The background gets a lens-like blur
with glowing bokeh highlights and no halo.

Works with people first, and falls back to a general model for pets and objects. Works on every lens,
including the front camera.

</td>
<td width="50%" valign="top">

### 🌅 CPL filter
Three ways to use it (*Settings → Aub Camera modes → Filter*):

- **Simulated:** imitates a circular polariser in software. Deeper blue sky, less glare on water, glass and
  leaves, less haze, richer foliage.
- **Physical filter attached:** a live *glare meter* in the viewfinder. Rotate your clip-on CPL slowly and
  stop when it says "best".
- **Physical + simulated boost:** both.

</td>
</tr>
<tr>
<td width="50%" valign="top">

### 🎮 PS2
A frame from a sixth-generation games console: 448-line framebuffer, un-antialiased edges, flattened
"low-res texture" surfaces, warm bloom, composite-video colour bleed, 15-bit colour with Bayer dithering,
and a 3× integer upscale with optional CRT scanlines.

</td>
<td width="50%" valign="top">

### 📸 2000s
An early-2000s compact digicam: 2 MP output, soft cheap lens, over-sharpened halos, punchy CCD colour with a
cool cast, hard highlight clipping, purple fringing, blotchy shadow noise, vignetting, JPEG crunch, and the
classic orange seven-segment date imprint (today's date, a retro '04 year, or off).

</td>
</tr>
<tr>
<td colspan="2" valign="top">

### 🎞️ 35mm (disposable camera)
A single-use film camera, back from the chemist: warm consumer colour film with yellow highlights, slightly
green shadows and faded blacks, highlights that roll off gently instead of clipping, clumpy film grain, a
red-orange halation glow around bright lights, and a plastic lens with soft, colour-fringed corners and heavy
vignetting. Optional **light leaks** (an orange streak from the end of the frame, different on every shot) and
an optional **flash look**: bright subject, background falling into darkness, for indoor and night shots.

</td>
</tr>
</table>

All five show an approximate **live preview of the look in the viewfinder** (you can turn it off) and save
with a filename suffix: `_Portrait`, `_CPL`, `_PS2`, `_Digicam` or `_Disposable`. Everything runs on the phone. Nothing is
uploaded.

> [!NOTE]
> A real polariser works optically, before light reaches the sensor, so software can only imitate its look.
> The *Physical filter attached* option is there for when you want the real thing.

## 🧰 Everything from Open Camera

Auto-level, manual focus/ISO/shutter/white balance, RAW (DNG), HDR, DRO, noise reduction and night mode,
panorama, fast burst, exposure and focus bracketing, camera vendor extensions, face detection, histogram,
zebra stripes, focus peaking, pre-shots, timer and repeat modes, voice/audio/remote control, Bluetooth LE
remotes, photo stamps, geotagging, video with log profiles, slow motion, time-lapse, and more. The
[Open Camera manual](https://opencamera.org.uk/help.html) covers all of these.

## 📲 Installing

Every push to `main` is built by GitHub Actions and published as the
[**latest** release](https://github.com/523co/aub-camera/releases/tag/latest).

1. On your phone, download [`AubCamera.apk`](https://github.com/523co/aub-camera/releases/download/latest/AubCamera.apk).
2. Open it, and allow "install unknown apps" for your browser when asked.
3. Later builds install as updates over the old one.

Aub Camera has its own application id (`com.aubergineux.aubcamera`), so it installs **alongside** Open Camera
rather than replacing it.

## Building

You need Android Studio (or the Android SDK command line tools) with **JDK 17**, and internet access the first
time you build.

```sh
git clone https://github.com/523co/aub-camera.git
cd aub-camera
./gradlew assembleDebug        # APK ends up in app/build/outputs/apk/debug/
```

The first build downloads the two MediaPipe segmentation models into `app/src/main/assets/aub_models/`.
They aren't committed to the repository.

To install on a phone with USB debugging enabled, run `./gradlew installDebug`, or copy the APK across and
open it.

| | |
|---|---|
| **Minimum Android** | 7.0 (API 24, required by MediaPipe) |
| **Target SDK** | 35 |
| **Native code** | arm64 phones and the x86_64 emulator |

<details>
<summary><b>🗺️ Where the new code lives</b></summary>

<br>

* `app/src/main/java/net/sourceforge/opencamera/aub/` holds the effects. Everything except `AubEffects` and
  `PortraitSegmenter` is plain Java with no Android dependencies, so the algorithms can be run and tested on a
  desktop JVM.

  | Class | Does |
  |---|---|
  | `CplFilter` | Simulated polariser, and the glare score used by the physical-filter meter |
  | `Ps2Filter` | Console look |
  | `DigicamFilter` | 2000s digicam look and the seven-segment date imprint |
  | `DisposableFilter` | Disposable film camera look: film colour, grain, halation, flash and light leaks |
  | `PortraitCompositor` | Mask refinement, bokeh rendering and full-resolution compositing |
  | `PortraitSegmenter` | MediaPipe segmentation (selfie multiclass, with DeepLab v3 as fallback) |
  | `AubEffects` | Settings, Bitmap glue, and the live viewfinder preview |

* Photo modes are wired into `MyApplicationInterface`, `ImageSaver` (applied in `postProcessBitmap`),
  `PopupView`, `Preview` and `DrawPreview` (live preview and glare meter).
* Settings: `res/xml/preferences_sub_aub_modes.xml` and `PreferenceSubAubModes`.

Large photos are processed in row strips, so the effects never hold a second full-resolution copy of the
photo in memory.

</details>

## 🔒 Privacy

No analytics, no ads, no tracking, and photos are never uploaded for processing. As in GrapheneOS Camera,
location tagging is off and device details (make, model, camera settings) are removed from photo metadata by
default; both can be changed in Settings. The full policy is in [PRIVACY.md](PRIVACY.md).

## Licence

Aub Camera is a modified version of Open Camera. Open Camera is © 2013–2025 Mark Harman; modifications
© 2026 Aubergine-UX. Both are released under the **GNU General Public License v3 or later**; see
[gpl-3.0.txt](gpl-3.0.txt).

Mark Harman is not responsible for this modified version. Please
[report Aub Camera problems here](https://github.com/523co/aub-camera/issues) rather than to Open Camera.

<details>
<summary>Third-party components</summary>

<br>

* [MediaPipe Tasks](https://github.com/google-ai-edge/mediapipe): Apache License 2.0
* [ZXing](https://github.com/zxing/zxing) (QR / barcode scanning): Apache License 2.0
* MediaPipe image segmentation models (SelfieMulticlass, DeepLab v3), downloaded at build time: see their
  model cards on the [MediaPipe image segmentation page](https://ai.google.dev/edge/mediapipe/solutions/vision/image_segmenter)
* AndroidX libraries: Apache License 2.0
* Google Material Design icons: Apache License 2.0

</details>

<sub>PlayStation is a registered trademark of Sony Interactive Entertainment. Aub Camera is not affiliated with
or endorsed by Sony; "PS2" describes the visual style only.</sub>
