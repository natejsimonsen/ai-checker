"""Fetch a few AI-generated songs from the SONICS dataset (Suno + Udio) for CI positive controls.

The dataset's fake songs live in multi-GB zip parts on Hugging Face. remotezip reads the
zip's central directory with HTTP range requests, so only the chosen members are downloaded.

    pip install remotezip
    python tools/ci/fetch_sonics_samples.py out_dir [count]

SONICS is CC BY-NC 4.0 (https://huggingface.co/datasets/awsaf49/sonics); samples are only
used transiently in CI and are not redistributed.
"""

import sys
from pathlib import Path

from remotezip import RemoteZip

URL = "https://huggingface.co/datasets/awsaf49/sonics/resolve/main/fake_songs/part_10.zip"

out = Path(sys.argv[1])
count = int(sys.argv[2]) if len(sys.argv) > 2 else 6
out.mkdir(parents=True, exist_ok=True)

with RemoteZip(URL) as z:
    songs = sorted(n for n in z.namelist() if n.lower().endswith((".mp3", ".wav", ".m4a", ".ogg")))
    print(f"{len(songs)} songs in archive; e.g. {songs[:3]}")
    # Spread picks across the archive so different generators/versions are likely covered.
    step = max(1, len(songs) // count)
    for name in songs[::step][:count]:
        target = out / Path(name).name
        target.write_bytes(z.read(name))
        print(f"fetched {name} ({target.stat().st_size // 1024} KiB)")
