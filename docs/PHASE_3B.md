# Phase 3B — provider seams and 2K export

Phase 3B starts from the verified Phase 3A composition pipeline. Output is now 1440×2560 (2K vertical, 9:16); source clips are still center-cropped, muted, and rendered at the requested 30 fps. Caption geometry scales with the canvas while preserving the verified Phase 2 treatment.

The new `analysis` package defines `FrameAnalysisProvider`, cache-keyed frame samples, confidence, and deterministic creative candidates. `LocalFrameAnalysisProvider` samples center-pixel luminance and adjacent-sample change; `CachedFrameAnalysisProvider` stores bounded JSON results under the app cache. The unavailable provider remains explicit and returns unknown measurements rather than inventing scene labels. `CreativeCandidateEngine` reuses local presets and deduplicates candidates; `CandidateEvaluator` and `HookEngine` provide deterministic ranking. Each newly planned reel records provider/sample provenance in its plan metadata.

No new runtime dependency or permission is required. Existing Room plans remain compatible because source metadata is independent of output dimensions. Local unit tests and APK assembly pass after the 2K change. The emulator export suite must be rerun on this branch before phone verification; physical acceptance should confirm 1440×2560 metadata, readable captions, multi-clip timing, cancellation, and five-reel batching.
