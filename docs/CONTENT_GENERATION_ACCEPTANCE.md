# CSV generation integration — awaiting physical acceptance

Generate now calls `ContentCandidatePlanner` from `BatchWorker`. Eligible enabled library rows, a `TextGenerator` provider, and emergency category presets feed an interleaved pool of up to twelve valid plans per selection. Completed captions are read from Room on every selection, including previous batches. Normalized exact matches and token-overlap similarity >= 0.90 are rejected. Moderate similarity reduces the score. This is a lexical heuristic, not embedding similarity.

There is **no installed generative model or visual semantic model** in this repository. The default model provider returns no candidates. An actual model adapter can supply AI_GENERATED candidates through the same selector (tested with a stub). CSV and fallback sources function offline. Earlier statements that a complete AI creative system existed were inaccurate.

Local matching groups visual keywords from the script, tags, and concept and compares them against source filenames. Specific imported scripts require a matching filename; unmatched items remain unused. Scripts without recognized specific visual terms can use general category footage. Filenames are hints, not verified scene labels. Descriptive names such as `ocean-beach.mp4` make acceptance tests reproducible. Real per-frame semantic matching remains a limitation.

Candidate ranking considers matching hints, persisted clip use count, current-batch use, and text novelty. Imported rows rotate by use count and last-used time. One row remains one plan; parsed beats are preserved exactly. Clip count is independent of beat count: a long matching clip may carry several beats; a preferred count can request multiple matching clips. Reading time governs duration, up to 20 seconds. Scripts exceeding six beats, 180 characters per beat, or available reading time are skipped without rewriting/truncating.

Plan metadata records `textSource` and `contentItemId`. Publication checkpoints update content use count and time in the existing `completeReel` Room transaction, exactly once. Failures and cancellation before publication do not consume content. Saved plans resume with provenance; single-clip retries preserve the script and library identity. Completed captions and source-range pairing records retain history even if an import is later deleted. No new schema, permission, or runtime dependency is added.

Regression coverage includes all four text/clip structures, case-insensitive categories, disabled items, exact history exclusion, no-footage matching, unreadable scripts, repeated CSV import counts, model-provider participation, publication idempotence and database reopen. The Android export test imports CSV, plans it using the production selector, publishes a real MP4, checks both timed text frames and verifies usage. It does not replace physical Generate acceptance.

## Physical acceptance (required, not yet performed)

1. Install this APK with the same signing certificate as the installed app to preserve its database. If Android reports a signature mismatch, do not uninstall to test migration; a matching-signed build is needed.
2. Add a folder with a readable 15-second-or-longer video named `ocean-beach.mp4`.
3. Import a UTF-8 CSV with header `category,subtheme,text,tags` and row `Travel,freedom,"The ocean can wait. || We have all afternoon.",beach`.
4. Choose Travel, tap Generate, and open Your Reels. Verify a reel is marked `Text source: USER_CSV` and plays both exact beats in order. Confirm the file is in Movies/Upload Reels and is 1440×2560.
5. Return to Content Library: that row must show one use. Close/reopen the app, Generate again, and verify that script is not repeated. With insufficient fresh content a partial batch is expected; import more unique rows.
6. Disable a fresh imported item, Generate, and verify it is not selected; re-enable it and verify it can be selected. Test cancellation and confirm only published reels increment usage.
7. Report the USER_CSV label, exact rendered wording, library use count, and whether folders/history survived upgrade. The CSV feature remains **awaiting physical-device acceptance** until this succeeds.
