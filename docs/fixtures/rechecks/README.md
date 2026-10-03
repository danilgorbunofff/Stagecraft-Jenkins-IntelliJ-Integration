# Re-check evidence (R1–R10)

Raw server responses behind [Day-0 verification → Re-check results](../../day0-verification.md#re-check-results-r1r10).
Everything here was captured 2026-10-02 by `curl` against the throwaway containers
(`stagecraft-jenkins` 2.541.3 on `:18080`, `stagecraft-jenkins-2479` 2.479.3 on `:38080`).

Files are response headers (`.headers.txt`) or bodies, named `<re-check>.<what>`. The API token,
`data-crumb` values, `crumb` JSON fields and session cookies are replaced with `REDACTED-*`; the
numbers the claims rely on (`Content-Length`, `X-Text-Size`, `X-More-Data`, status codes) are
untouched.

| File | Proves |
|---|---|
| `r1-running.first.headers.txt` | `progressiveText?start=0` on a running build: `200`, `X-Text-Size: 2815`, `X-More-Data: true` |
| `r1-running.delta.headers.txt` | `start=<X-Text-Size>` while running: `200`, `Content-Length: 0`, `X-More-Data: true` |
| `r1-console.route.headers.txt` | A `start=0` fetch of the same route: `X-Text-Size: 5295` for a 5 320-byte body (the CRLF rule again). The mid-log delta it was meant to show (`start=5295` → 2540 bytes) was not captured |
| `r1-finished.at-end.headers.txt` | `start=<X-Text-Size>` on a finished build: 0 bytes, **no** `X-More-Data` |
| `r1-finished.mid-log.headers.txt` | Mid-log offset on a finished build returns the remaining 62 bytes |
| `r1-finished.beyond-end.headers.txt` | `start` **past** the end is not an error: the server resets to 0 and re-sends the whole 17 624-byte log |
| `r1-crlf-normalised.headers.txt` | The CRLF rule: `X-Text-Size: 5008` for a 5 029-byte body (`5 029 − 21 CRLF pairs`) |
| `r1-consoleText.unicode.body.txt` | A `consoleText` of the `unicode-lines` job: 484 bytes, 454 characters, LF line endings |
| `r2-pernode-wfapi-log.json` | `/execution/node/{id}/wfapi/log` → `{"length":0,"hasMore":false,"consoleUrl":null}` |
| `r2-parallel-demo.wfapi.json` | Run-level `describe` for a parallel pipeline: flat stage list, `durationMillis` is exclusive self-time |
| `r2-parallel-nested.wfapi.json` | Same for stages nested inside lanes |
| `r2-parallel-nested.console.txt` | The console for that build — no lexical signal for lane ownership |
| `r2-nested-stages-demo.wfapi.json` | Ordinary nesting is reported flat too |
| `r3-skipped-post-demo.wfapi.json` | A `when`-skipped stage and the synthetic `Declarative: Post Actions` stage |
| `r4-https-build-url.json` | Captured over HTTPS: `url` is `https://localhost:18443/…` — Jenkins followed the forwarded request. Corrected 2026-10-03; see R4 in the record |
| `r4-https-root-url.json` | Same for the root listing |
| `r5-tree-3-levels.json` | One `tree=` request reaching `stagecraft/deep/nested-freestyle` (3 levels) |
| `r6-bad-token.headers.txt` | Bad token → `401` + `WWW-Authenticate: Basic realm="Jenkins"` |
| `r6-bad-token.body.txt` | The `401` body |
| `r7-anonymous.403.headers.txt` | Anonymous caller without `Overall/Read` → `403` |
| `r7-authenticated-no-item-read.404.headers.txt` | Authenticated caller with `Overall/Read` but no `Item/Read` → **404** for a job that exists |
| `r8-scan-log.txt` | Branch-source scan: `feature/ORD-215` excluded by `BranchDiscoveryTrait(1)`, "Checking pull request #1", then the imposed rate limiter sleeping 4–8 min per check (this capture ends mid-sleep; `PR-1` appeared once it expired) |
| `r8-child-jobs.json` | The multibranch's child jobs: `main`, `feature%2FORD-214` and **`PR-1`** |
| `r8-pr1-job.json` | The `PR-1` job itself — a plain `WorkflowJob`, so the PR identity lives in its actions |
| `r8-pr1-build1.json` | `PR-1/1`: `building: true`, `result: null` — the PR build exists |
| `r8-pr1-console.txt` | That build's console before any work: `Branch indexing` → `Connecting to https://api.github.com with no credentials` → `Sleeping for 6 min 3 sec`. A PR build re-queries the GitHub API for its merge revision, so its first minutes are limiter sleep |
| `r8-job-actions.txt` | The three child jobs' action classes side by side: `PR-1` has `ContributorMetadataAction`, `main` has `PrimaryInstanceMetadataAction`, `feature%2FORD-214` neither — the reliable way to classify a child job |
| `r9-running-build.json` | The job while its first build runs: `builds` holds build 1 with `building: true` |
| `r9-running-build.headers.txt` | Headers for the same response |
| `r10-pluginmanager.json` | `hudson.LocalPluginManager` with an empty plugin list — a bare Jenkins |
| `r10-check1.me.json` | `/me/api/json` on 2.479.3 |
| `r10-check8.consoleText.txt` | `consoleText` still works with zero plugins |
| `r10-check10.running.headers.txt` | Tailing on 2.479.3 matches 2.541.3 (`X-More-Data: true`) |
| `r10-check10.finished.headers.txt` | Finished on 2.479.3: `X-More-Data` **absent**, `X-Text-Size: 880` |
| `r10-check10.finished.delta.body.txt` | The delta fetched from `start=521`: 359 characters = `880 − 521`. Saved with LF line endings, so the 33 `CRLF` pairs of the 392-byte response are not in the file |
| `r10-check12.wfapi-404.headers.txt` | `wfapi/describe` **404s** without `pipeline-stage-view` (body is a 73 KB HTML page, not committed) — must read as "stage view unavailable" |
| `r10-check15.wrong-crumb.txt` | Wrong crumb and missing crumb produce the same `403 No valid crumb was included in the request` |

The full deliverables from the 15 gate checks, including the console and `wfapi` bodies the parser
is tested against, live one level up in [`docs/fixtures/`](../).
