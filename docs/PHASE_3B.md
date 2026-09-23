# Phase 3B — provider seams and 2K export

Phase 3B starts from the verified Phase 3A composition pipeline. Output is now 1440×2560 (2K vertical, 9:16); source clips are still center-cropped, muted, and rendered at the requested 30 fps. Caption geometry scales with the canvas while preserving the verified Phase 2 treatment.

The new `analysis` package defines `FrameAnalysisProvider`, cache-keyed frame samples, confidence, and deterministic creative candidates. The unavailable provider is explicit and returns unknown measurements rather than inventing scene labels. `CreativeCandidateEngine` reuses the existing local presets and deduplicates candidates; provider-backed semantic generation, frame decoding, ranking, and visual typography adaptation remain subsequent 3B increments.

No new runtime dependency or permission is required. Existing Room plans remain compatible because source metadata is independent of output dimensions. Local unit tests and APK assembly pass after the 2K change. The emulator export suite must be rerun on this branch before phone verification; physical acceptance should confirm 1440×2560 metadata, readable captions, multi-clip timing, cancellation, and five-reel batching.
