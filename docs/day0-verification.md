# Day-0 verification record

Executed per the charter ([§10](../README.md#10--the-day-0-gate-one-day-before-any-product-code)).
Every check below was run by hand against a throwaway Docker Jenkins (2.541.3 LTS), recorded
from the live server, and the raw responses are committed under [`docs/fixtures/`](fixtures/) as
unit-test fixtures for `dev.stagecraft.jenkins` (§9.1).

**Gate decision: PASS with deviations.** The load-bearing results hold on HTTP and on
self-signed HTTPS. Stage markers are in `consoleText`, the console parse still works when
`wfapi` returns 404, lint works, and so does crumb handling. No kill criteria from §14 were
triggered. Six checks did not behave exactly as §10 specified, and they are listed under
[Deviations](#deviations) instead of being counted as plain passes. Some things the product
needs were not covered by the gate at all; they are listed under [Open re-checks](#open-re-checks).
Neither list blocks Days 1–2. Items R1–R4 must be closed before Days 7–10 (log, stages,
discovery) are built on them.

*Reviewed 2026-10-01 against the committed fixtures. The review corrected several statements
that the fixtures contradicted, and it replaced the parser (see [Parser](#parser-check-9)).*

| Artifact | Value |
|---|---|
| Jenkins | 2.541.3 LTS (Jetty 12.1.5, Java 17) |
| Image | `stagecraft-jenkins:day0` (git, workflow-aggregator, pipeline-model-definition, pipeline-stage-view, workflow-multibranch, cloudbees-folder, junit) |
| HTTP endpoint | `http://localhost:18080` (also the configured Jenkins root URL; every `url` field in the fixtures uses it) |
| HTTPS endpoint | `https://localhost:18443` (nginx 1.27 proxy, self-signed cert) |
| Fallback image | `stagecraft-jenkins:day0-nosv` at `http://localhost:28080` (same plugin set **minus** `pipeline-stage-view`) |
| Fixture repo | `https://github.com/danilgorbunofff/stagecraft-day0-fixture` |
| User / password | `admin` / `stagecraft-day0` (throwaway container credentials) |
| API token | `11e2…` (redacted per charter; full token lives in session artifacts only) |
| Cert SHA-256 | `BC:53:BA:98:C0:F2:52:2B:4A:B2:54:33:E3:F8:84:6E:71:B1:6B:22:93:09:A5:72:15:F1:C7:47:4D:02:C5:B4` (CN=localhost, SAN localhost/dev.stagecraft.jenkins/127.0.0.1, valid 30 d) |

## Jobs created

| Job | Path | Shape |
|---|---|---|
| `freestyle-fail` | root | freestyle, 1 build, FAILURE by design |
| `multibranch-demo` | root | multibranch pipeline → fixture repo; branches `main` (build FAILURE at Deploy), `feature/ORD-214` (UNSTABLE, 1 failing junit test) |
| `stagecraft` | root | folder |
| `stagecraft/deep` | nested folder | folder (2 levels deep) |
| `stagecraft/deep/nested-freestyle` | nested freestyle | freestyle, 1 build, **FAILURE** (same `exit 1` shell step as `freestyle-fail`; see [`08.console-nested.txt`](fixtures/08.console-nested.txt)) |

CSRF was on by default; the crumb flow was confirmed in checks 2 and 15.

## The 15 checks

HTTP leg ran against `http://localhost:18080`, HTTPS leg against `https://localhost:18443`
with `-k` (self-signed). "Fixture" = committed raw response. **Δ** = see [Deviations](#deviations).

| # | Check | Expected | HTTP | HTTPS | Fixture |
|---|---|---|---|---|---|
| 1 | `GET /me/api/json` | 200, returns user | 200, `id: admin` | 200 | [`fixtures/01.me.json`](fixtures/01.me.json) |
| 2 | `GET /crumbIssuer/api/json` | 200, crumb + field | 200, `Jenkins-Crumb` + 64-hex-char crumb | 200 | [`fixtures/02.crumb.json`](fixtures/02.crumb.json) |
| 3 | `X-Jenkins` header | present | `X-Jenkins: 2.541.3` + `X-Jenkins-Session` | present, **but the recorded response is `403 Forbidden`** **Δ** | [`fixtures/03.headers.txt`](fixtures/03.headers.txt), [`fixtures/https.03.headers.txt`](fixtures/https.03.headers.txt) |
| 4 | Folder tree | folders visible in one request | 200; `freestyle-fail`, `multibranch-demo`, `stagecraft` (root level only) **Δ** | 200, same names (not committed) | [`fixtures/04.rootjobs.json`](fixtures/04.rootjobs.json) |
| 5 | MB branch jobs | branch jobs listed | 200; `feature%2FORD-214`, `main` | 200 (not committed) | [`fixtures/05.mbjobs.json`](fixtures/05.mbjobs.json) |
| 6 | Branch build list | builds listed | 200; committed fixture is **`main`**, build 1, FAILURE **Δ** | 200 (not committed) | [`fixtures/06.branchbuilds.json`](fixtures/06.branchbuilds.json) |
| 7 | Build `remoteUrls` | git URL present | 200; `BuildData.remoteUrls` = fixture repo URL | 200, identical | [`fixtures/07.remotedata.json`](fixtures/07.remotedata.json), [`fixtures/https.07.remotedata.json`](fixtures/https.07.remotedata.json) |
| 8 | Full console text | plain text, `[Pipeline]` markers | 200; 32 marker lines | 200 | [`fixtures/08.console-main.txt`](fixtures/08.console-main.txt) |
| 9 | Stage segmentation | 3 stages, names correct | 4 stages: `Declarative: Checkout SCM` + the 3 authored `Checkout`, `Build`, `Deploy to staging` **Δ** | same | [`fixtures/09.parse-main.json`](fixtures/09.parse-main.json) |
| 10 | Incremental log | body + `X-Text-Size` | headers only recorded: `X-Text-Size: 12263`, `X-More-Data` absent (finished); running build: `X-Text-Size: 864`, `X-More-Data: true` **Δ** | `X-Text-Size: 12397` | [`fixtures/10.headers.txt`](fixtures/10.headers.txt), [`fixtures/10.running.headers.txt`](fixtures/10.running.headers.txt) |
| 11 | Test results | JSON, or clean 404 | 200 on `feature/ORD-214` (`failCount 1`); 404 on `main` (no tests, HTML body) | same | [`fixtures/11.testreport.json`](fixtures/11.testreport.json), [`fixtures/11.testreport-404.html`](fixtures/11.testreport-404.html) |
| 12 | `wfapi/describe` | **200 with stage-view, 404 without — both recorded** | 200 (stage-view installed); 404 on the `-nosv` image (HTTP only) | 200 | [`fixtures/12.wfapi.json`](fixtures/12.wfapi.json) + [`fixtures/nosv.wfapi-404.html`](fixtures/nosv.wfapi-404.html) |
| 13 | Jenkinsfile lint | valid → OK text; broken → error text | valid: "Jenkinsfile successfully validated."; broken: "Errors encountered validating Jenkinsfile: WorkflowScript: 8: expecting '}', found '' @ line 8, column 1." | same | [`fixtures/13.lint-valid.txt`](fixtures/13.lint-valid.txt), [`fixtures/13.lint-broken.txt`](fixtures/13.lint-broken.txt), [`fixtures/https.13.lint-broken.txt`](fixtures/https.13.lint-broken.txt) |
| 14 | 404 behaviour | 404, distinguishable from 403 | 404 (`/job/does-not-exist`) | 404 | [`fixtures/14.404.txt`](fixtures/14.404.txt) |
| 15 | 403 behaviour | 403 on stale crumb, body `No valid crumb` | 403, `No valid crumb was included in the request` — **only with password auth** **Δ** | 403 | [`fixtures/15.wrongcrumb.txt`](fixtures/15.wrongcrumb.txt) |

## Deviations

| # | What §10 expected | What actually happened | Consequence |
|---|---|---|---|
| 3 | `X-Jenkins` on an authenticated response over HTTPS | The committed HTTPS response is `403 Forbidden` (nginx in front, `X-Jenkins` still present). The record does not say why the request was refused. | The version header is confirmed. The HTTPS auth leg for this request is **not** confirmed by this fixture; checks 1, 2 and 13 confirm HTTPS auth separately. |
| 4 | "Folders visible, one request" | Only the root level was recorded. `stagecraft/deep/nested-freestyle` was never reached through the API in a fixture. | Folder *recursion* is unverified. See R5. |
| 6 | Branch build list for the multibranch branch | The committed fixture is `main`, build 1, FAILURE, and it carries `duration,number,result,timestamp,url`. `building` is absent, so the charter's `tree=` was not the one recorded. Previous versions of this record described it as `feature/ORD-214`, UNSTABLE. | The fixture is valid, but for a different branch than described. The `building` field (needed to tell "running" from "failed") has no fixture. |
| 9 | 3 stages | 4 stages. Jenkins prepends a synthetic `Declarative: Checkout SCM` stage for a pipeline that checks out its own SCM. | The parser must expect synthetic `Declarative: …` stages (also `Post Actions`, `Tool Install`, `Agent Setup`) and must not treat them as user stages. |
| 10 | Body + `X-Text-Size` | Headers only. `X-Text-Size: 12263` is **smaller** than `Content-Length: 12336`, while `consoleText` of the same-shaped build is 2,856 bytes. HTTPS reported 12397, and the build each leg measured is not recorded. | `X-Text-Size` is a raw log-file offset (the raw log includes hidden console-note annotations), **not** the length of the text returned. The client must treat it as opaque and never compute it from the body. Body semantics unverified; see R1. |
| 15 | 403 on a stale crumb | With an **API token**, a POST with a wrong crumb **succeeds** (201/200). The 403 reproduces only with password basic auth. | API-token requests are crumb-exempt (Jenkins ≥ 2.96). Charter §9.2/§15.4/B.2/B.8 updated accordingly. |

## Parser (check 9)

The parser now lives at [`tools/parse_stages.py`](../tools/parse_stages.py). Its executable spec is
[`tools/test_parse_stages.py`](../tools/test_parse_stages.py) (`python tools/test_parse_stages.py`,
11 cases). The three `*.parse*.json` fixtures were regenerated with it. Stage names and line
ranges are identical to the originally recorded output.

| Log | Result |
|---|---|
| multibranch `main` (pipeline, 4 stages) | `stageCount 4`, names correct, `stackLeftAtEnd 0`, `endMarkerSeen true`, inferred failed stage `Deploy to staging` (`last-executed-stage`) |
| freestyle-fail console (no `[Pipeline]` markers) | `stageCount 0`, `fallbackMode plain-log`, first error line 8 |
| `-nosv` seed pipeline (2 stages) | `stageCount 2` (`Build`, `Ship`), `result SUCCESS` |

**Defects found on review in the gate-day parser, now fixed:**

1. **`// stage` popped enclosing frames.** The `}` before it had already closed the stage, so the
   handler then popped `node`/`withEnv` blocks until it found another stage. Flat pipelines came
   out right by accident (`stackLeftAtEnd 0` was coincidental). A **nested** stage truncated its
   parent at the child's `// stage`, losing the parent's remaining lines. `// stage` is now
   informational only.
2. **Parallel branches were reported as stages.** `{ (Branch: A)` was emitted as a stage named
   `Branch: A`, and the inner stages got ranges containing each other's output. Since JEP-210
   the plain console has **no per-branch prefix**, so interleaved output cannot be attributed
   from `consoleText` at all. Stages inside `parallel` are now reported with the whole parallel
   region as their range and `interleaved: true`.
3. **`isPipelineLog` was false for a truncated pipeline log with stages** (no End marker, empty
   stack). That is exactly what the head-only fetch in §9.7 produces. It is now keyed on any
   `[Pipeline]` marker. A pipeline with no stages is reported as `pipeline-without-stages`.
4. The gate-day write-up described a fix for a `None` sentinel crash. The committed code
   contained only a no-op block (`if not end_seen and not stages: stages = []`), now removed.

**Added:** synthetic-stage flag, `Stage "X" skipped due to …` detection, ANSI/CRLF tolerance,
first error line, and an **inferred** failed stage with its basis. Status is never present in the
console, so the inference must be labelled as such in the UI.

## Endpoint behaviours worth carrying into the client design

1. **API-token auth is exempt from CSRF crumb validation.** POSTs with an API token
   and a wrong crumb succeed (HTTP 201/200). To reproduce check 15's 403 the request
   must use password basic auth. Stagecraft should therefore treat the token path as
   crumb-free and still send crumbs (with the session cookie they are bound to) when the user stores user+password.
2. **Declarative lint checks structure only.** Unknown step names pass lint
   (`nonsenseStepThatDoesNotExist()` → "successfully validated"); a broken file needs
   structural errors (unbalanced braces) to produce an error response. The plugin must
   not present lint output as a full static analysis.
3. **`remoteUrls` lives on build `actions`** (`BuildData`), not on job actions —
   `/job/{mb}/job/{branch}/{n}/api/json?tree=actions[remoteUrls,_class]` (§14 risk row:
   confirmed on this LTS).
4. **`X-More-Data` is only present while the build is running.** For finished builds it
   is absent (not `false`); the client should drive tailing off `X-More-Data` presence,
   not its value. `X-Text-Size` is an opaque offset (Deviation 10).
5. **curl specifics:** bare `[]` in URLs must be passed with `--globoff`, and POSTed
   XML must be BOM-less UTF-8 (PowerShell 5.1's `Out-File -Encoding utf8` writes a BOM
   that Jenkins rejects with `Content is not allowed in prolog`).
6. **Security warnings during image build (throwaway container only, not recorded as
   product issues):** `workflow-multibranch` SECURITY-3729 (credential exposure),
   `pipeline-groovy-lib` SECURITY-3815/SECURITY-3796.
7. **Every `url` / `absoluteUrl` in a response is built from Jenkins' configured root URL**
   (`http://localhost:18080/…` in every fixture), not from the address the client used.
   Behind a reverse proxy, following those links leaves the user's configured endpoint.
   The client must rebase returned URLs onto its configured base, or build paths itself.
8. **The failure message is outside every stage.** In `08.console-main.txt` the
   `ERROR: Deploy failed: …` line comes *after* `[Pipeline] End of Pipeline` (line 72). "Scroll
   the failed stage to its first error" cannot work from the console range alone; the error
   must be searched across the whole log.
9. **`wfapi/describe` carries the failure reason per stage**: `stages[].error.message` =
   `Deploy failed: Discount table is empty for region EU` (`12.wfapi.json`). Use it as the
   failed stage's headline when present.
10. **Test results are mapped to stages by Jenkins itself:** junit suites carry
    `enclosingBlockNames` (`["Deploy to staging"]`) and `nodeId` (`11.testreport.json`).
    No client-side matching needed.
11. **Branch job names are URL-encoded once, URLs twice:** name `feature%2FORD-214`, URL
    `…/job/feature%252FORD-214/` (`05.mbjobs.json`).
12. **404 bodies are HTML**, not JSON, even under `/api/json` and `/wfapi/` paths. The client must
    branch on status code before parsing.

## Open re-checks

Not covered by the gate. Each is a short `curl` session against the same containers.

| # | Re-check | Why it matters | Blocks |
|---|---|---|---|
| R1 | `progressiveText?start=0` **with body**, then `start=<X-Text-Size>` and `start=<mid-log offset>`; `start` beyond the end | Offset semantics (Deviation 10); whether a tail fetch from a known size is possible (charter §9.7) | Days 7–8 |
| R2 | A **parallel** declarative pipeline and a **nested-stages** pipeline: `consoleText` + `wfapi/describe` + `execution/node/{id}/wfapi/log` for a parallel child | Confirms the parser's interleaving model against a real server, and whether per-node logs give exact per-branch output where stage-view exists | Days 9–10 |
| R3 | A build where a **later stage is skipped** and a `post { failure { … } }` block runs | Confirms skipped-stage text and `Declarative: Post Actions` on this LTS | Days 9–10 |
| R4 | Same calls over HTTPS: compare returned `url` fields with the HTTPS base | Behaviour 7: the rebasing rule | Days 1–4 |
| R5 | Recursive folder listing to `stagecraft/deep/nested-freestyle` in **one** request (`tree=jobs[name,url,_class,jobs[name,url,_class,jobs[name,url,_class]]]`) | Deviation 4; the one-request discovery in charter §9.4 | Days 3–4 |
| R6 | Bad token → `/me/api/json` | Distinguishes 401 from 403/404 in the settings UI | Days 5–6 |
| R7 | A user without `Item/Read` on a job → `/job/{x}/api/json` | Jenkins hides jobs as **404**, so "404 vs 403" (check 14) does not mean "missing vs forbidden" | Days 5–6 |
| R8 | A GitHub/Bitbucket branch-source multibranch where a branch has an open PR | With default discovery the branch has only a `PR-N` job; discovery of "my branch" must find it (charter §9.4) | Days 3–4 |
| R9 | Branch build list with the full charter `tree=` (incl. `building`) on a running build | Deviation 6 | Days 5–6 |
| R10 | One older LTS (e.g. 2.479.x), checks 1, 2, 8, 10, 12, 15 | The gate tested one version; the charter's matrix starts much lower | Days 13–14 |

## Fixture files

Raw responses are committed under `docs/fixtures/` (see the table above). These are
the recording fixtures for §9.1 unit tests: auth, crumb, root job listing,
multibranch branch jobs, build list, console parsing, progressive-log headers, test reports,
wfapi presence/absence, lint, 404/403.

Housekeeping on review: HTML error bodies were renamed from `.json` to `.html`
(`11.testreport-404.html`, `nosv.wfapi-404.html`); the page crumb embedded in `14.404.txt` was
replaced with `REDACTED-CRUMB`; the parser moved from `docs/fixtures/` to `tools/`.
