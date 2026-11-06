# HLG 709

Android app that grades Sony HLG photos and videos to SDR Rec.709 on your phone. It uses either a built-in HLG→709 colour space transform or a `.cube` LUT, and adds an exposure control to pull the image back down.

## Install

1. Copy `dist/HLG709.apk` to the phone and open it (allow "install unknown apps" for your file manager), **or** with USB debugging on, run:
   ```
   adb install -r dist/HLG709.apk
   ```
2. Requires Android 10 or newer.

## Use

1. **Choose from gallery** (the system photo picker) or **Browse files**. Browse files keeps camera file names like `C0001` and can reach SD-card readers.
2. Pick **HLG → 709** or **LUT (.cube)**. Imported LUTs are kept in the app.
3. Set **Exposure**. Hold the preview to compare with the original. For videos, drag the scrubber to preview other frames.
4. **Export**. Photos are saved as JPEGs in `Pictures/HLG709` (EXIF is kept), and videos as MP4s in `Movies/HLG709`. Keep the app open while it runs.

### Exposure: before or after

- **Before transform** (default) changes exposure in scene-linear light ahead of the transform, which works like closing down the lens. Highlights the transform had rolled off come back.
- **After transform** scales the finished Rec.709 image in linear light. It darkens the image, but clipped whites turn grey instead of recovering detail.

### LUTs

Sony's official LUTs (s709, LC-709 and the others) expect **S-Log3** footage. If you use one, set **LUT input curve** to S-Log3 so that "Before transform" exposure is applied correctly. For an HLG→709 LUT, leave it on HLG.

## What the built-in transform does

BT.2100 HLG → scene-linear BT.2020 → 18% grey normalised (HLG 38%, per BT.2408) → BT.2020→709 matrix → soft gamut compression → highlight shoulder → BT.709 OETF.

At 0 EV:

| Input | Output |
|---|---|
| 18% grey | 41% |
| HLG reference white (75%) | ~92% |
| HLG peak | 100% |

The whole grade, including the exposure setting and any LUT, is baked into one 65³ 3D LUT. Photos apply it on the CPU and videos on the GPU, so photos and videos match.

## Shooting tips for phone compatibility

- **Video:** record **XAVC HS 4:2:0 10-bit** (HEVC). Phones can't decode XAVC S 4:2:2 10-bit (H.264 High 4:2:2). The app explains this if you pick such a file.
- **Stills:** HEIF **4:2:0** decodes on Android. HEIF 4:2:2 generally doesn't. A JPEG shot with an HLG picture profile also works, since its pixel values are HLG.
- **Audio:** Sony LPCM (16- or 24-bit) is converted to AAC. 4-channel recordings keep channels 1 and 2.

## Notes

- **Colour accuracy depends on the phone's GPU.** On GPUs with `GL_EXT_YUV_target` (most Adreno and Mali), the app reads the decoder's 10-bit Y′CbCr directly and converts it with the stream's own BT.2020 matrix. Where that's missing, it relies on the GPU driver's conversion and shows a note. Very saturated colours can shift a little on that path.
- **Output format:** video is 8-bit SDR, tagged BT.709. The bitrate is about 0.12 bits/pixel for HEVC and 0.2 for H.264, so 4K30 HEVC is about 30 Mb/s.

## Build and test

```
./gradlew :app:assembleRelease              # app/build/outputs/apk/release/app-release.apk
./gradlew :app:testReleaseUnitTest          # colour maths, LUT parser, baked-LUT accuracy
./gradlew :app:connectedDebugAndroidTest    # end-to-end on a connected phone/emulator
python tools/make_test_media.py app/src/androidTest/assets   # regenerate test clips (needs ffmpeg)
```

The instrumented tests grade synthetic HLG clips that contain flat patches of known code values. They compare each pixel the app produces with the exact maths. The tests that need a 10-bit decoder skip on devices without one, such as the stock emulator. Run them on a real phone to cover the 10-bit path.
