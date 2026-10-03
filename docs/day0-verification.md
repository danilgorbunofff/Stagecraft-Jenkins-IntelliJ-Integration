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
needs were not covered by the gate at all; those became the ten re-checks **R1–R10**, all of which
are now closed against live servers under [Re-check results](#re-check-results-r1r10).
Neither list blocks Days 1–2.

*Reviewed 2026-10-01 against the committed fixtures. The review corrected several statements
that the fixtures contradicted, and it replaced the parser (see [Parser](#parser-check-9)).*

*Re-checks executed 2026-10-02 against the same containers plus a bare 2.479.3 LTS. That pass
also fixed three defects in the parser and one in the container bootstrap (see
[Re-check results](#re-check-results-r1r10)).*

| Artifact | Value |
|---|---|
| Jenkins | 2.541.3 LTS (Jetty 12.1.5, Java 17) |
| Image | `stagecraft-jenkins:day0` (git, workflow-aggregator, pipeline-model-definition, pipeline-stage-view, workflow-multibranch, cloudbees-folder, junit) |
| HTTP endpoint | `http://localhost:18080` (also the configured Jenkins root URL; the HTTP-leg `url` fields use it — the HTTPS captures in `rechecks/r4-*` use `https://localhost:18443`, see [Corrections](#corrections-2026-10-03)) |
| HTTPS endpoint | `https://localhost:18443` (nginx 1.27 proxy, self-signed cert) |
| Fallback image | `stagecraft-jenkins:day0-nosv` at `http://localhost:28080` (same plugin set **minus** `pipeline-stage-view`) |
| Fixture repo | `https://github.com/danilgorbunofff/stagecraft-day0-fixture` |
| User / password | `admin` / `stagecraft-day0` (throwaway container credentials) |
| API token | `11e2…` (redacted per charter; full token lives in session artifacts only) |
| Cert SHA-256 | `BC:53:BA:98:C0:F2:52:2B:4A:B2:54:33:E3:F8:84:6E:71:B1:6B:22:93:09:A5:72:15:F1:C7:47:4D:02:C5:B4` (CN=localhost, SAN localhost/dev.stagecraft.jenkins/127.0.0.1, valid 30 d) |

Second and third servers, added for the re-checks:

| Server | Address | Purpose |
|---|---|---|
| `stagecraft-jenkins-2479` | `http://localhost:38080` | `jenkins/jenkins:2.479.3-lts`, **zero plugins** (R10) |
| `stagecraft-jenkins` + PR trait | `http://localhost:18080` | `github-branch-source` 1983.vfa_27ed961853 added after the gate (R8) |

## Jobs created

| Job | Path | Shape |
|---|---|---|
| `freestyle-fail` | root | freestyle, 1 build, FAILURE by design |
| `multibranch-demo` | root | multibranch pipeline → fixture repo; branches `main` (build FAILURE at Deploy), `feature/ORD-214` (UNSTABLE, 1 failing junit test) |
| `stagecraft` | root | folder |
| `stagecraft/deep` | nested folder | folder (2 levels deep) |
| `stagecraft/deep/nested-freestyle` | nested freestyle | freestyle, 1 build, **FAILURE** (same `exit 1` shell step as `freestyle-fail`; see [`08.console-nested.txt`](fixtures/08.console-nested.txt)) |

Added for the re-checks (all inside `stagecraft/` unless noted):

| Job | Shape | Used by |
|---|---|---|
| `long-run` | pipeline, 90 s shell step | R1, R9 (sampled while running) |
| `ticking` | pipeline, repeated 2 s sleeps | R1 (`X-Text-Size` growth) |
| `unicode-lines` | pipeline printing non-ASCII | R1 (offset unit) |
| `parallel-demo` | `parallel` with 3 lanes | R2 |
| `parallel-nested` | `parallel` with a stage inside each lane | R2 |
| `nested-stages-demo` | stage inside a stage | R2 |
| `skipped-post-demo` | `when`-skipped stage + `post { failure }` | R3 |
| `github-mb-fixture` (root) | multibranch → `GitHubSCMSource`, `BranchDiscoveryTrait(1)` + `OriginPullRequestDiscoveryTrait`, open PR #1 | R8 |
| `r10-console` (on 2.479.3) | freestyle, 12 s shell step | R10 |

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
| 4 | "Folders visible, one request" | Only the root level was recorded. `stagecraft/deep/nested-freestyle` was never reached through the API in a fixture. | Folder *recursion* is unverified **by this fixture**. Closed by R5: one request does reach 3 levels. |
| 6 | Branch build list for the multibranch branch | The committed fixture is `main`, build 1, FAILURE, and it carries `duration,number,result,timestamp,url`. `building` is absent, so the charter's `tree=` was not the one recorded. Previous versions of this record described it as `feature/ORD-214`, UNSTABLE. | The fixture is valid, but for a different branch than described. The `building` field (needed to tell "running" from "failed") has no fixture; R9 supplies the running-build shape (`building: true`, `result: null`, `duration: 0`, `estimatedDuration: -1`, empty `builds` right after the trigger). |
| 9 | 3 stages | 4 stages. Jenkins prepends a synthetic `Declarative: Checkout SCM` stage for a pipeline that checks out its own SCM. | The parser must expect synthetic `Declarative: …` stages (also `Post Actions`, `Tool Install`, `Agent Setup`) and must not treat them as user stages. |
| 10 | Body + `X-Text-Size` | Headers only. `X-Text-Size: 12263` is **smaller** than `Content-Length: 12336`, while `consoleText` of the same-shaped build is 2,856 bytes. HTTPS reported 12397, and the build each leg measured is not recorded. | R1 explains the gap exactly: `X-Text-Size` equals the body byte count **minus the number of `CRLF` pairs** (four captures, listed under R1). It is a newline-normalized length, not a file offset and not the body length. The client must echo the server's `X-Text-Size` back as the next `start` and never derive an offset from the bytes it received. |
| 15 | 403 on a stale crumb | With an **API token**, a POST with a wrong crumb **succeeds** (201/200). The 403 reproduces only with password basic auth. | API-token requests are crumb-exempt (Jenkins ≥ 2.96). Charter §9.2/§15.4/B.2/B.8 updated accordingly. |

## Parser (check 9)

The parser now lives at [`tools/parse_stages.py`](../tools/parse_stages.py). Its executable spec is
[`tools/test_parse_stages.py`](../tools/test_parse_stages.py) (`python tools/test_parse_stages.py`,
16 cases, 5 of them over the real parallel consoles captured while closing R2). The three
`*.parse*.json` fixtures were regenerated with it. Stage names and line
ranges are identical to the originally recorded output. *(Corrected 2026-10-03: the parser has
since gained `branch`, `laneBinding`, `parentUncertain` and `diagnostics.unattributedStages`, so
its current output is a superset of the committed JSON; every shared field and range still
matches, and the tests compare ranges only.)*

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
   from `consoleText` at all. The rewrite (R2) went further and removed the *fabricated chain*:
   lane stages are now emitted as siblings of the `parallel` wrapper while lanes are still
   opening, a lane stage whose name matches a `Branch: <label>` declaration is bound to that
   lane, and anything else is marked `parentUncertain: true` and listed in
   `diagnostics.unattributedStages` instead of being guessed into a chain. Lane stages carry
   `interleaved: true` and the whole parallel region as their range.
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
   not its value. `X-Text-Size` is the offset the server wants echoed back — see 15.
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
13. **A 404 does not mean "missing" — it means "missing or hidden".** With `matrix-auth`, an
    authenticated user who has `Overall/Read` but not `Item/Read` gets **404** (with an HTML body)
    for a job that exists, while an **anonymous** caller gets **403** for the same URL; granting
    `Item/Read` turns the same call into a 200. Status codes alone cannot separate "job deleted"
    from "you may not see this job", and the answer changes with authentication. Never render a
    404 as "job not found" without saying "or not visible to this account". (R7, §9.5.)
14. **No stage hierarchy and no per-node log exist anywhere.** `/execution/node/{id}/wfapi/log`
    returns `{"length":0,"hasMore":false,"consoleUrl":null}`; `/execution/node/{id}/wfapi/describe`
    returns a single stage with no children; `/execution/node/{id}/api/json` is not a valid
    endpoint; run-level `wfapi/describe` is flat. Where `pipeline-stage-view` is installed its
    `durationMillis` is **exclusive self-time** (`start + durationMillis` equals the first child's
    `start`, verified on 5/5 stage pairs), so totals must be summed from the leaves. Planned-design
    note: per-branch console output cannot be reconstructed, so the UI must interleave branches in
    the single console view rather than hosting one log per lane. (R2/R3.)
15. **`X-Text-Size` is a cumulative, newline-normalized character offset.** It equals the byte
    count of the log **minus** the number of `CRLF` pairs (four captures on 2.541.3: 17 624→17 527,
    5 029→5 008, 5 320→5 295 from committed headers, 8 495→8 450 from a capture that was not
    committed; confirmed again on 2.479.3, where a delta fetched from `start=521` came back as
    392 bytes = 359 characters + 33 `CRLF` pairs = `880 − 521` — the committed body was saved
    with its line endings normalised, so it shows 359 bytes and LF only). Echo it
    back verbatim as the next `start` — never derive an offset from the bytes received, because a
    delta body's byte count is not a character count. A `start` **beyond the end of the log is not
    an error**: the server resets to 0 and re-sends the entire log, so a tailer must ignore a body
    that is *larger* than the outstanding delta instead of appending it. On a running build
    `start=<X-Text-Size>` returns `200` with an empty body and `X-More-Data: true`, which is the
    cheapest idle poll. (R1, §9.7.)
16. **The `wfapi` namespace 404s outright when `pipeline-stage-view` is absent** — a bare
    2.479.3 reports `404` for `/wfapi/describe` while `consoleText` works. 404 under `/wfapi/`
    must be read as "stage view unavailable", never as "build missing". (R10.)
17. **A stale crumb and a missing crumb are the same 403** (`No valid crumb was included in the
    request`) when password auth is used. The client can only fix that by re-fetching a crumb with
    the same session cookie and retrying once; it cannot distinguish the two causes up front. (R10,
    §9.2.)
18. **The Jenkins-imposed GitHub API limiter throttles anonymous branch discovery *and* PR builds.**
    With no stored GitHub credentials, `GitHubSCMSource` discovery sleeps 4–8 minutes per
    pull-request check (`Current quota for Github API usage has 52 remaining (1 over budget) …
    Sleeping for 5 min 22 sec`), so the scan log stays in `processing` for 10–20 minutes with no
    child job created. The `PR-1` build that follows starts with the same sleep before printing
    anything, because `checkout scm` re-queries the GitHub API for the PR merge revision
    (`busyExecutors: 0`, empty queue, `Sleeping for 6 min 3 sec` as the first log line). A
    GitHub-backed multibranch can therefore look permanently stuck, and its first build can look
    like a hung checkout, while nothing is wrong. (R8.)
19. **`GlobalMatrixAuthorizationStrategy.getGrantedPermissions()` returns an immutable view.**
    `permissions.remove("user")` silently does nothing and the change appears not to apply; any
    scripted permission edit must build a **fresh** strategy object and set it. (R7.)
20. **Matrix-authorization and branch-source plugins can be installed at runtime without a restart
    on current LTS** — `PluginManager.deploy(true)` performs a dynamic load, but the plugin's
    `Jenkins` core requirement is enforced: `matrix-auth 3.3` needs `ionicons-api`, which needs
    Jenkins ≥ 2.504.1, so it loads on 2.541.3 and fails on 2.479.3. Any scripted plugin install
    must check the core version first. (R7/R10.)
21. **Multibranch child jobs are self-describing through `actions`.** For the same
    `GitHubSCMSource`, `main` carries `ObjectMetadataAction` + `PrimaryInstanceMetadataAction`,
    `feature%2FORD-214` carries only `ObjectMetadataAction`, and `PR-1` carries
    `ObjectMetadataAction` + `ContributorMetadataAction`. That is the reliable way to tell a
    pull-request job from a branch job and from the primary branch — there is no `PR-` name parsing
    needed, and it still works when the branch itself has no job. (R8, §9.4.)

## Re-check results (R1–R10)

Executed 2026-10-02 by `curl` against the running containers; raw captures are committed under
[`docs/fixtures/rechecks/`](fixtures/rechecks/). All ten are closed.

| # | Question | Outcome |
|---|---|---|
| R1 | `progressiveText` offsets: `start=0` with body, `start=<X-Text-Size>`, `start=<mid-log>`, `start` beyond the end | **Closed.** `X-Text-Size` = body bytes **− number of `CRLF` pairs** (four captures on 2.541.3: 17 624→17 527, 5 029→5 008, 5 320→5 295 from committed headers; 8 495→8 450 was not committed) and it is **cumulative** for the whole log, not per response. Echoing it back as `start` works: on a running build → `200`, `Content-Length: 0`, `X-More-Data: true`; after the build ends → `200` with 0 bytes and **no** `X-More-Data`. A `start` beyond the end is not an error — the server **resets to 0 and re-sends the whole log**. Never derive `start` from the bytes received. Confirmed again on 2.479.3 (R10). |
| R2 | Parallel and nested-stage pipelines; `wfapi/describe`; `execution/node/{id}/wfapi/log` | **Closed, with a negative result.** There is **no per-node log and no stage hierarchy endpoint** anywhere: `/execution/node/{id}/wfapi/log` → `{"length":0,"hasMore":false,"consoleUrl":null}`; `/execution/node/{id}/wfapi/describe` → one stage, no children; `/execution/node/{id}/api/json` → not a valid endpoint. Run-level `wfapi/describe` is flat. Per-lane output cannot be recovered: all `{ (Branch: X)` frames open before any lane body and close after all of them, so lexical nesting never reveals lane ownership. Four real consoles were committed (fixtures 10–13) and the parser was rewritten against them (see [Defects found](#defects-found-while-closing-the-re-checks)). |
| R3 | A skipped later stage and a `post { failure { … } }` block | **Closed** ([`13.console-skipped-post-demo.txt`](fixtures/13.console-skipped-post-demo.txt)): `Stage "Skipped" skipped due to when conditional`, synthetic `Declarative: Post Actions`, inferred failed stage `Fails`, `result FAILURE`. |
| R4 | HTTPS: are returned `url` fields rebased? | **Closed — corrected 2026-10-03.** The committed captures contradict the original wording: through the nginx HTTPS endpoint the `url` fields read `https://localhost:18443/…`, i.e. Jenkins built them from the request the proxy forwarded, not from `http://localhost:18080`. So what a server returns depends on its root-URL configuration and on what the proxy forwards, and neither is under the client's control. The design conclusion stands for that reason, not because of this capture: build job and build URLs locally from the configured base, and rebase only URLs that cannot be built (`JenkinsUrls.rebase`, which now also handles a context path such as `/jenkins/`). |
| R5 | One-request recursion to `stagecraft/deep/nested-freestyle` | **Closed.** A single `tree=jobs[name,url,_class,jobs[name,url,_class,jobs[name,url,_class]]]` reached all 3 levels. Nested folder children also come back **double-encoded** in `url` (`…/job/stagecraft/job/deep/job/nested-freestyle/`) while `name` is not. |
| R6 | Bad token → `/me/api/json` | **Closed.** `401 Unauthorized` with `WWW-Authenticate: Basic realm="Jenkins"` and a `remember-me` cookie cleared to 1970. Cleanly distinguishable from 403/404. |
| R7 | A user without `Item/Read` on a job | **Closed, and the answer is "neither".** With `matrix-auth` 3.3 on 2.541.3 (`GlobalMatrixAuthorizationStrategy`: `admin` full control, `hudson.model.Hudson/Read` for `authenticated`) an authenticated `outsider` gets **404 with an HTML body** for `/job/freestyle-fail/api/json`, `…?tree=builds[number]` and `/job/freestyle-fail/1/consoleText`; **anonymous gets 403**; granting `Item/Read` flips the same calls to `200`. So 404 conflates "does not exist" with "hidden", and the same URL returns 403 or 404 depending only on whether the caller is authenticated. The client must never render 404 as "job not found" (charter check 14 / §9.5). |
| R8 | A branch-source multibranch where a branch has an open PR | **Closed.** `GitHubSCMSource` with `BranchDiscoveryTrait(1)` **excludes** `feature/ORD-215` ("Ignoring SCMHead{'feature/ORD-215'} because current strategy excludes branches that ARE also filed as a pull request") and discovers `main` + `feature%2FORD-214`; the PR check then runs under the imposed limiter (behaviour 18) but **does** finish — `PR-1` appeared with its own `PR-1/1` build, exactly the documented `PR-<n>` naming. The child job's `actions` also say what it is: `PR-1` carries `ContributorMetadataAction` + `ObjectMetadataAction`, `main` carries `PrimaryInstanceMetadataAction`, `feature%2FORD-214` only `ObjectMetadataAction` (behaviour 21). Discovery of "my branch" must look for the `PR-<n>` sibling when the branch is PR-only, because no job carries the branch name then. |
| R9 | Branch build list on a **running** build | **Closed.** Immediately after the trigger: `building: true`, `result: null`, `duration: 0`, `estimatedDuration: -1`; the job's `builds` list already holds that build (number 1, `building: true` — corrected 2026-10-03, the original said `builds: []`). 90 s later the same job reports `building: false`, `result: SUCCESS`, `duration: 92204` (this second observation has no committed fixture). |
| R10 | One older LTS (checks 1, 2, 8, 10, 12, 15) | **Closed** on `jenkins/jenkins:2.479.3-lts` (`X-Jenkins: 2.479.3`, Jetty 12.0.16) with **zero plugins installed** (`{"_class":"hudson.LocalPluginManager","plugins":[]}`): 1 → 200; 2 → 64-hex crumb; 8 → `consoleText` 200 `text/plain;charset=utf-8`; 10 → running `X-Text-Size: 521` + `X-More-Data: true`, finished `X-Text-Size: 880` with the header **absent**, and a fetch from `start=521` returned a 392-byte body containing 33 `CRLF` pairs — i.e. 359 characters, exactly `880 − 521`, an independent confirmation of the R1 rule on a second LTS; 12 → `/job/r10-console/wfapi/describe` **404** with a 73 KB `text/html;charset=utf-8` body even though the job exists; 15 → wrong crumb and no crumb both `403 No valid crumb was included in the request` with password auth, while a crumb-free `GET` is 200. The 2.479.3 findings match 2.541.3 exactly. |

### Defects found while closing the re-checks

1. **The parser fabricated a parallel chain.** For `parallel { stage('A')… }` it emitted
   `Parallel → A → B → C` — a linear chain that never existed. All `Branch:` frames open before
   any lane stage frame and stay open, so lane ownership is *not* recoverable from `consoleText`.
   `tools/parse_stages.py` now attributes a lane only when the lane stage's name matches a
   `Branch: <label>` declaration, or while lanes are still opening (sibling of the parallel
   wrapper), and otherwise marks the stage `parentUncertain: true` and lists it in
   `diagnostics.unattributedStages` instead of guessing. Five regression tests over the real
   consoles were added; `python tools/test_parse_stages.py` runs 16 cases.
2. **The container bootstrap deleted and recreated `multibranch-demo` on every start**
   (`02-create-mb.groovy`), so every restart wiped the branch index and re-triggered builds.
   The script is now idempotent (reuses an existing project and only rebuilds the source when the
   traits differ).
3. **`01-force-admin.groovy` was the same class of bug** — it unconditionally called
   `realm.createAccount("admin", …)` on every boot and relied on the swallowed
   `UserAlreadyExists` exception. It now checks `getUser("admin")` first.
4. **`02-create-mb.groovy` is guarded for servers without `github-branch-source`.** On the bare
   2.479.3 container it produced a Groovy compile error at every startup; it now no-ops when the
   plugin is absent.

### Still open

Nothing. All ten re-checks are closed. The only residual nuisance is the cost of R8: an anonymous
branch-source scan against GitHub spends 10–20 minutes in imposed-limiter sleeps per indexing run,
so any future re-run of that scenario should be expected to sit in `processing` for a long time
before `PR-1` appears.


## Fixture files

Raw responses are committed under `docs/fixtures/` (see the table above). These are
the recording fixtures for §9.1 unit tests: auth, crumb, root job listing,
multibranch branch jobs, build list, console parsing, progressive-log headers, test reports,
wfapi presence/absence, lint, 404/403.

Fixtures **10–13** were added while closing the re-checks: real consoles from the new probe jobs,
which the rewritten parser is written against.

| Fixture | Job | What it exercises |
|---|---|---|
| `10.console-parallel-demo.txt` | `stagecraft/parallel-demo` | 3 lanes (A, B, C), all `{ (Branch: X)` frames opening before any lane body |
| `11.console-parallel-nested.txt` | `stagecraft/parallel-nested` | stages *inside* lanes, whose lane ownership is not recoverable from the console |
| `12.console-nested-stages-demo.txt` | `stagecraft/nested-stages-demo` | ordinary nested stages |
| `13.console-skipped-post-demo.txt` | `stagecraft/skipped-post-demo` | a `when`-skipped stage plus a synthetic `Declarative: Post Actions` |

[`docs/fixtures/rechecks/`](fixtures/rechecks/) holds the raw evidence behind
[Re-check results](#re-check-results-r1r10) — response headers, `wfapi` bodies, child-job lists and
the branch-source scan log that the tables above quote. Credentials are redacted; see
[`rechecks/README.md`](fixtures/rechecks/README.md) for what each file proves.

Housekeeping on review: HTML error bodies were renamed from `.json` to `.html`
(`11.testreport-404.html`, `nosv.wfapi-404.html`); the page crumb embedded in `14.404.txt` was
replaced with `REDACTED-CRUMB`; the parser moved from `docs/fixtures/` to `tools/`.

## Corrections (2026-10-03)

An audit compared every checkable statement above with the committed fixtures. Most hold. These
did not, and are corrected in place where they are quoted above:

| Where | Said | The fixtures say |
|---|---|---|
| R4, header table | HTTPS responses carry `http://localhost:18080/…` urls | `rechecks/r4-https-*.json` carry `https://localhost:18443/…` urls. The design rule (build URLs locally) stands on other grounds; see R4. |
| R9 | `builds: []` on the running job | `r9-running-build.json` lists the running build (number 1, `building: true`) |
| R1, behaviour 15, deviation 10 | "5/5 captures" | four pairs are listed, three of them in committed headers |
| R10 delta | "392 bytes = 359 characters + 33 `CRLF` pairs" | the committed `r10-check10.finished.delta.body.txt` is 359 bytes, LF only — its line endings were normalised before it was committed, so it cannot show the CRLF count |
| Deviation 10 | R1 "explains the gap exactly" against `08.console-main.txt` | the CRLF rule accounts for 73 of the bytes; the rest of the 12 336 − 2 856 gap is the hidden `ha:` console notes. Stripping the notes and normalising CRLF turns `16.progressive-main-finished.raw.txt` into `08.console-main.txt` byte for byte (asserted by `ConsoleTextTest`) |
| Fixture table | `10.console-parallel-demo.txt` has 10 lanes | 3 lanes |
| Parser section | parse fixtures equal the current parser's output | the current parser emits extra fields; ranges match |
| `rechecks/README.md` | several sizes and offsets | corrected in that file |
| Redaction | session cookies are redacted | three `JSESSIONID` values were not (`03.headers.txt`, `https.03.headers.txt`, `rechecks/r10-check12.wfapi-404.headers.txt`); now `REDACTED`. They belonged to throwaway containers. |

Still open, and not fixable after the fact: the HTTPS results for checks 1, 2, 4–6 and 10 were not
committed, so §15.1's "all 15 checks on HTTP and HTTPS" rests on the HTTP fixtures plus
`https.03`, `https.07` and `https.13` for those checks.
