"""Convert lofcz/ai-music-detector's logistic-regression weights to the app's binary format.

    git clone https://github.com/lofcz/ai-music-detector
    python tools/reference/export_weights.py ai-music-detector/src/models/weights.npz

Format (little-endian): b"FPLR" | int32 version=1 | int32 n | float32 bias | float32[n] weights.
The upstream ONNX file is sigmoid(w.x + b) with these exact weights; this
script checks that against onnxruntime when it is installed.
"""

import struct
import sys
from pathlib import Path

import numpy as np

src = Path(sys.argv[1])
dst = Path(sys.argv[2] if len(sys.argv) > 2 else "core/src/main/resources/models/fakeprint_lr_v1.bin")

data = np.load(src)
w = data["weights"][0].astype("<f4")
b = float(data["bias"][0])

try:
    import onnxruntime as ort

    sess = ort.InferenceSession(str(src.with_name("ai_music_detector.onnx")))
    x = (np.random.default_rng(0).random((8, w.size)) * 0.01).astype(np.float32)
    onnx_p = sess.run(None, {sess.get_inputs()[0].name: x})[0][:, 0]
    lr_p = 1 / (1 + np.exp(-(x @ w + b)))
    assert np.allclose(onnx_p, lr_p, atol=1e-5), (onnx_p, lr_p)
    print("ONNX graph matches sigmoid(w.x + b)")
except ImportError:
    print("onnxruntime not installed; skipping equivalence check")

with open(dst, "wb") as f:
    f.write(b"FPLR")
    f.write(struct.pack("<iif", 1, w.size, b))
    f.write(w.tobytes())
print(f"wrote {dst} ({w.size} weights)")
