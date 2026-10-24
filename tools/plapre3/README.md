# Folio Plapre 3 FP32 conversion

Use the adjacent `tools/pico/venv` dependencies and validated acoustic exports.
`fetch.py` retrieves pinned public inputs without credentials and checks hashes.
The model has CC-BY-4.0 attribution; Kanade/HiFT keep their original licenses.
No downloaded inference code is executed by the exporter.

1. `fetch.py`
2. `export_lm.py` (writes FP32 ONNX external data and raw embeddings).
3. Install Transformers 4.57.6 into `reference-deps/` with pip `--target`, using
   NumPy 1.26.4. This isolates reference dependencies from the Pico environment.
4. `validate_lm.py`: compare the original model's unmodified configuration against
   ONNX for full prompt prefill, cached decoding and all KV tensors.
5. `samples.py`: complete waveform generation, five genuine voice samples and two
   harder diagnostic passages. ASR and human listening are separate checks.
6. `make_pack.py`: assemble the data-only archive, samples, APK-pinned catalog and
   Kotlin file identities. Use `tools/pico/build-native.ps1` for the shared bridge.

The configuration contains `rope_theta=10000` and an additional
`rope_parameters=100000`. Transformers 4.57.6 uses the former. Do not apply Pico's
rotary correction to this different model. The reference check deliberately does
not override either field. Stop on both token 0 and token 21223; token-limit or
time-limit exhaustion must fail without advancing the listening cursor.

The optional pack is self-contained and hosted on Folio's GitHub releases. Models
remain outside the APK and source archive. The large model requires a 64-bit
process. Host/emulator timings are not a Flip7 performance result.

Published pack versions are immutable. A change to conversion, weights, padding,
tokenizer or voice projections must receive a new pack version and release tag,
with updated signed catalog identities. Never replace the published v1 archive
while retaining its URL or version. This protects resumed downloads, installed
ownership manifests and audio source checkpoints.
