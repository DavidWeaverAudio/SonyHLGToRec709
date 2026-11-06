"""Builds the instrumented-test media: flat patches with known HLG code values.

  patches.ppm -> 16-bit RGB source (BT.2020 / HLG code values)
  hlg_patches.mov -> HEVC Main10 4:2:0, BT.2020 + ARIB STD-B67 (HLG) tags, big-endian
                     16-bit PCM audio ('twos'), like a Sony XAVC HS clip
  hlg_patches_8bit.mov -> H.264 8-bit 4:2:0 tagged the way Sony signals HLG for SDR
                     compatibility (BT.2020 primaries/matrix, transfer "bt2020-10"), for devices
                     (like the emulator) without a 10-bit decoder
  hlg_patches.png -> 8-bit PNG of the same patches (an HLG-profile still)

Patch values must match PATCHES in GradingPipelineTest.kt.
"""
import struct, subprocess, sys, os

PATCHES = [
    (0.10, 0.10, 0.10), (0.25, 0.25, 0.25), (0.38, 0.38, 0.38), (0.50, 0.50, 0.50),
    (0.62, 0.62, 0.62), (0.75, 0.75, 0.75), (0.90, 0.90, 0.90), (1.00, 1.00, 1.00),
    (0.60, 0.30, 0.20), (0.30, 0.50, 0.25), (0.25, 0.30, 0.60), (0.70, 0.70, 0.20),
]
W, H, COLS, ROWS = 640, 360, 4, 3

out = sys.argv[1]
os.makedirs(out, exist_ok=True)
pw, ph = W // COLS, H // ROWS
rows = []
for y in range(H):
    row = bytearray()
    for x in range(W):
        r, g, b = PATCHES[min(y // ph, ROWS - 1) * COLS + min(x // pw, COLS - 1)]
        row += struct.pack(">HHH", round(r * 65535), round(g * 65535), round(b * 65535))
    rows.append(bytes(row))
ppm = os.path.join(out, "patches.ppm")
with open(ppm, "wb") as f:
    f.write(b"P6\n%d %d\n65535\n" % (W, H))
    f.writelines(rows)

ff = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y"]
subprocess.check_call(ff + [
    "-loop", "1", "-framerate", "30", "-i", ppm,
    "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
    "-t", "2", "-map", "0:v", "-map", "1:a",
    "-vf", "scale=out_color_matrix=bt2020nc:out_range=tv:flags=accurate_rnd+full_chroma_int,format=yuv420p10le",
    "-c:v", "libx265", "-preset", "medium", "-crf", "4",
    "-x265-params", "log-level=error:colorprim=bt2020:transfer=arib-std-b67:colormatrix=bt2020nc:range=limited",
    "-color_primaries", "bt2020", "-color_trc", "arib-std-b67", "-colorspace", "bt2020nc", "-color_range", "tv",
    "-tag:v", "hvc1", "-c:a", "pcm_s16be", "-ac", "2",
    os.path.join(out, "hlg_patches.mov"),
])
subprocess.check_call(ff + [
    "-loop", "1", "-framerate", "30", "-i", ppm,
    "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
    "-t", "2", "-map", "0:v", "-map", "1:a",
    "-vf", "scale=out_color_matrix=bt2020nc:out_range=tv:flags=accurate_rnd+full_chroma_int,format=yuv420p,"
           "setparams=color_primaries=bt2020:color_trc=bt2020-10:colorspace=bt2020nc:range=tv",
    "-c:v", "libx264", "-preset", "medium", "-crf", "2", "-profile:v", "high",
    "-color_primaries", "bt2020", "-color_trc", "bt2020-10", "-colorspace", "bt2020nc", "-color_range", "tv",
    "-c:a", "pcm_s16be", "-ac", "2",
    os.path.join(out, "hlg_patches_8bit.mov"),
])
subprocess.check_call(ff + ["-i", ppm, "-pix_fmt", "rgb24", os.path.join(out, "hlg_patches.png")])
os.remove(ppm)
print("ok")
