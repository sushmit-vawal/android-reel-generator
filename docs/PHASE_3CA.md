# Phase 3C-A: visual matching and large-library coverage

This build implements 3C-A. Physical acceptance is pending. Typography, readability treatments, and new safe-area rules remain Phase 3C-B and have not been changed.

## Why generation exhausted a small pool

The old worker inspected only `dao.candidates().take(40)`. Sorting by usage did not help when no output was produced: failed or unmatched clips retained their position. The content planner used filename keywords, stopped at the first 12 viable plans, and had only five built-in captions per category. Published text was correctly excluded, but the available matching pool could quickly become empty even with hundreds of source videos and CSV rows. The UI displayed only 50 content rows without pagination.

Previously, frame inspection ran after selection, measured a single central pixel, and treated brightness change as movement. There were no visual embeddings, image labels, temporal subject ranges, per-beat vibe checks, or narrative sequence scoring.

## New selection and indexing

- All enabled, accessible folders and subfolders enter the candidate inventory. Overlapping trees still deduplicate by document identity.
- Unanalyzed videos are shuffled and interleaved across folders. Each Generate indexes another page of 12; every valid cached clip remains available. If there is no suitable plan, the worker keeps exploring additional pages until it finds one or exhausts the inventory. The page size bounds work between planning attempts, not the total library size. Cancellation retains completed analysis for the next run.
- Progress reports indexed/total counts. Initial exploration can take longer, especially for slow document providers or a library with no suitable content. Native frame decoding and ML tasks cannot always stop immediately; cancellation is checked between operations.
- Every eligible CSV row is considered. Fresh imported content has priority, followed by the optional text generator, existing captions, and footage-led templates. The shipping app has no generative text model. CSV wording is never rewritten; rows that cannot fit the reading budget or match footage are deferred.
- Random choice is restricted to complete plans within 0.035 of the best score. Equal-scoring shot alternatives are shuffled by batch/slot seed. All choices must pass relevance thresholds first.
- Exact/near-duplicate published text remains blocked. Reimporting another CSV appends new records, skips duplicates, and preserves earlier imports and usage. Content Library now shows total/unused counts, additional pages, and warnings for oversized scripts.

## Analysis, model, and evidence

The APK bundles **ML Kit image labeling 17.0.9**, an on-device image classifier. It does **not** contain a CLIP-style image/text embedding model. The manifest removes INTERNET permission, so classification and stored footage stay on device. Runtime testing checks bundled inference without that permission.

Sources are sampled at 6 points from 10% to 85% for videos up to 30 seconds, 12 for up to a minute, and 18 for longer videos. Frames are decoded at no more than 320 × 320. Nearby samples 200 ms apart provide 32 × 32 luma grids for motion estimates corrected for uniform exposure change. Whole-grid brightness, contrast/detail, near-black exposure, and central edge concentration inform usability and crop suitability. One classifier session is reused and bitmaps are released after inference.

Each temporal section has confidence-bearing semantic tags, motion, brightness, complexity, energy, quality, and crop assessment. Adjacent sections merge only with consistent canonical subjects, energy, and brightness; merged semantic confidence and quality are conservative. Sparse samples are **not** precise scene-cut detection: sections are bounded to within three seconds of each sampled frame, and long unsampled gaps are not silently treated as understood.

ML labels supply actual visual evidence. A small explicit vocabulary maps labels and text onto common activities/settings. Filename hints have lower confidence and are used only when classification yields no supported subject. Mood/energy, narrative role, abstract travel/routine intent, quality, and crop suitability are **heuristics**, not learned emotional understanding. Classification cannot establish whether an event is funny. Humor requires a supported visual subject from its text/tags; no invented funny-event fallback is supplied.

`ClipEmbeddingProvider` and a typed embedding representation define the seam for a future aligned model. The current provider returns unavailable; no fabricated vectors or embedding scores are used. MobileCLIP was investigated, but its official model/export tooling is not a turnkey Android pipeline with verified tokenizer, preprocessing, compatible weights, runtime performance, and device acceptance for this project.

Primary references:

- [ML Kit bundled Android image labeling](https://developers.google.com/ml-kit/vision/image-labeling/android)
- [Official MobileCLIP repository](https://github.com/apple-aiml-research/ml-mobileclip)
- [ONNX Runtime mobile integration](https://onnxruntime.ai/docs/tutorials/mobile/)

## Per-beat matching and sequence planning

The planner derives `BeatVisualIntent` from each exact text beat, concept/tags, and category. Explicit subjects take priority over shared tags. Supported work → transition → travel payoff scripts receive distinct work, airport/road, and destination intents. Reflective language favors lower activity; effort/motivation favors training or work rather than generic scenery.

Default thresholds are configurable in `MatchPolicy`: semantic ≥ 0.50, vibe ≥ 0.48, total ≥ 0.58, quality ≥ 0.25. Total score weights semantic relevance 0.57, concept 0.05, vibe 0.17, motion 0.08, quality 0.08, and crop suitability 0.05. Lifetime/current-batch reuse and previous concept/range pairing impose a **combined maximum penalty of 0.07**. Fresh unrelated footage cannot pass the semantic gate.

A bounded beam search considers alternatives across beats and subtracts brightness, motion, and crop discontinuity penalties. Deliberate energy progression reduces the motion discontinuity penalty. Every selected range stays inside its analyzed section and supplies the beat's reading time. A matching single clip can support multiple beats; a single caption can span several short matching sections. Clip-count hints cannot override relevance. Export failure does not flatten a multi-stage story onto an arbitrary first source.

Debug notes in persisted ReelPlan JSON record concept, exact beat, inferred intent/role, source URI, trim range, semantic/vibe/motion/concept scores, reuse penalty, overall score, and analysis provider. A bounded app-private `files/visual-match-debug.txt` also contains top rejected examples and the last selected plan. With a development APK and USB debugging it can be read using `adb shell run-as com.reelgenerator cat files/visual-match-debug.txt`. It is not shown in normal UI or uploaded.

## Storage, rendering, and limitations

Room **4 → 5** adds only `CachedClipAnalysis`, including file/model fingerprint and cached source dimensions/duration. All existing tables survive. Changed size/modified/name/model version invalidates analysis; sources lacking reliable metadata and degraded model results are rechecked after 24 hours. Unchanged normal sources reuse cached analysis.

Export remains 1440 × 2560 (2K portrait), with the existing Media3 compositor, timed captions, sequential render, publication checkpoint, and Movies/Upload Reels destination. `SubjectCropPolicy` currently measures central-interest suitability and favors usable center crops; it does not track faces or move the crop. Existing typography is deliberately preserved until physical 3C-A acceptance.

Finite templates can still be exhausted. Unsupported visual subjects, very long scripts, unreadable footage, or a lack of appropriate clips can still produce a partial/zero batch; the app now reports category/library/analysis context instead of recommending filename changes alone. Add concise visual tags to abstract CSV rows where useful. Tags guide intent; they do not overwrite strong conflicting image evidence. Broad labels and sparse sampling need real-phone evaluation on the user's footage.

## Verification and physical acceptance

Automated coverage includes 550-video selection beyond the old cutoff; all 105 CSV rows selected across repeated requests; balanced randomized folder coverage; visual thresholds; bounded reuse; calm versus active footage; motivation intent; work/airport/ocean temporal sequencing; continuity; imported wording/history; video-first templates; pixel measurements; analysis invalidation; cache loading; schema 1 → 5 and populated 4 → 5 migration. Existing render, 2K dimensions, timed text, multi-clip, cancellation, and CSV publication tests remain. A bundled-classifier runtime test runs without INTERNET permission. See the linked build/test reports for final results.

Physical acceptance requires the user's phone; an emulator cannot establish creative quality. Install the APK as an update, preserving app data. Do not uninstall to work around a signing mismatch: that would remove the local library/history. Android requires the previous signing key for an in-place upgrade, independently of the database migration.

1. Keep existing imports; import `phase3ca-acceptance.csv` as another file. Confirm previous rows and use counts remain.
2. Enable multiple folders containing office/laptop, sunrise, beach workout, airport/airplane, road, scuba/ocean, city/night, gym, home/coffee/food, friends, and useful humor clips. Include a longer video with visibly different internal sections and some generic camera filenames.
3. Run Travel, Motivation, and Lifestyle: **three batches × five attempts**. First-run analysis can take longer; watch indexed/total counts. Continue batches to verify that new footage is explored beyond the first page and fresh CSV rows are used.
4. For each result, rate subject relevance, vibe, and sequence coherence 1–5. Check exact CSV wording, appropriate internal ranges, calm/active distinction, and relevant reused footage beating unrelated fresh footage. Record deferred rows too; do not count forced mismatches as success.
5. Check 1440 × 2560 output in Upload Reels, all five publication checkpoints, cancellation during analysis/render, and history after restarting the app. Test one additional Humor batch using visually grounded jokes if suitable footage is available.
6. Share the results before Phase 3C-B. Do not proceed if selection still feels mostly random.
