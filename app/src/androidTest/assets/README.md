# Native video test fixtures

These files contain only generated solid-color frames and a generated red cover image.
They contain no user data or third-party footage, and use the project's AGPL-3.0 license.

- `preview.avi`: 320×180, 5 fps, 8 seconds, Motion JPEG.
- `cover-art.mkv`: the same video with an embedded PNG attachment.
- `cover-art.mp4`: H.264 video with an embedded PNG cover picture.

They test the native decoder and the paths that ordinarily cache embedded artwork.
The instrumentation fixture encrypts each file in RAM with the official cryptolib before playback.
These assets are packaged only in the test APK.

To regenerate from the repository root, provide Pillow and FFmpeg (the checked-in files used FFmpeg 7.1):

```sh
uv run --with pillow app/src/androidTest/fixtures/generate.py --ffmpeg /path/to/ffmpeg
```

The script can produce different encoder metadata or bytes with different tool versions;
the tests verify behavior rather than fixture hashes. These tools are not app dependencies.

## Image and PDF viewer fixtures

`image.png`, `image.jpg`, `image.webp` and `image.avif` are generated 96×64 green images.
`pages.pdf` is a generated PDF containing a red page and a blue page, with no scripts or external references.
They use the project's AGPL-3.0 license and contain no third-party media.

```sh
python app/src/androidTest/fixtures/generate_documents.py --ffmpeg /path/to/ffmpeg
```

This generator uses Python's standard library and FFmpeg. The checked-in AVIF is encoded by
FFmpeg 7.1's libaom AV1 encoder. Native viewer tests encrypt the files in memory before opening them.
