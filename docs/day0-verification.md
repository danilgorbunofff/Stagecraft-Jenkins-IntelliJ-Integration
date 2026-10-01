# Day-0 verification record

Executed per the charter ([§10](../../README.md)). Every check below was run by hand
against a throwaway Docker Jenkins (2.541.3 LTS), recorded from the live server, and
the raw responses are committed under [`docs/fixtures/`](fixtures/) as unit-test
fixtures for `dev.stagecraft.jenkins` (§9.1).

**Gate decision: PASS.** All 15 checks passed on HTTP and on self-signed HTTPS.
No kill criteria from §14 were triggered.

| Artifact | Value |
|---|---|
| Jenkins | 2.541.3 LTS (Jetty 12.1.5, Java 17) |
| Image | `stagecraft-jenkins:day0` (git, workflow-aggregator, pipeline-model-definition, pipeline-stage-view, workflow-multibranch, cloudbees-folder, junit) |
| HTTP endpoint | `http://localhost:18080` |
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
| `stagecraft/deep/nested-freestyle` | nested freestyle | freestyle, ran, no failure |

CSRF was on by default; the crumb flow was confirmed in checks 2 and 15.

## The 15 checks

HTTP leg ran against `http://localhost:18080`, HTTPS leg against `https://localhost:18443`
with `-k` (self-signed). "Fixture" = committed raw response.

| # | Check | Expected | HTTP | HTTPS | Fixture |
|---|---|---|---|---|---|
| 1 | `GET /me/api/json` | 200, returns user | 200, `fullName: admin` | 200 | [`fixtures/01.me.json`](fixtures/01.me.json) |
| 2 | `GET /crumbIssuer/api/json` | 200, crumb + field | 200, `Jenkins-Crumb` + 34-char crumb | 200 | [`fixtures/02.crumb.json`](fixtures/02.crumb.json) |
| 3 | `X-Jenkins` header | present | `X-Jenkins: 2.541.3` + `X-Jenkins-Session` | same | [`fixtures/03.headers.txt`](fixtures/03.headers.txt) |
| 4 | Folder tree | folders visible in one request | 200; `freestyle-fail`, `multibranch-demo`, `stagecraft` | 200, same names | [`fixtures/04.rootjobs.json`](fixtures/04.rootjobs.json) |
| 5 | MB branch jobs | branch jobs listed | 200; `feature/ORD-214`, `main` | 200 | [`fixtures/05.mbjobs.json`](fixtures/05.mbjobs.json) |
| 6 | Branch build list | builds listed | 200; build 1, UNSTABLE (encoded `feature%2FORD-214`) | 200 | [`fixtures/06.branchbuilds.json`](fixtures/06.branchbuilds.json) |
| 7 | Build `remoteUrls` | git URL present | 200; `BuildData.remoteUrls` = fixture repo URL | 200 | [`fixtures/07.remotedata.json`](fixtures/07.remotedata.json) |
| 8 | Full console text | plain text, `[Pipeline]` markers | 200; 32 marker lines | 200 | [`fixtures/08.console-main.txt`](fixtures/08.console-main.txt) |
| 9 | Stage segmentation | stages parsed, names correct | 4 stages: `Declarative: Checkout SCM`, `Checkout`, `Build`, `Deploy to staging` | same | [`fixtures/09.parse-main.json`](fixtures/09.parse-main.json) |
| 10 | Incremental log | body + `X-Text-Size` | `X-Text-Size: 12263`, `X-More-Data` absent (finished); on a running build: `X-Text-Size: 864`, `X-More-Data: true` | `X-Text-Size: 12397` | [`fixtures/10.headers.txt`](fixtures/10.headers.txt) |
| 11 | Test results | JSON, or clean 404 | 200 on `feature/ORD-214` (`failCount 1`); 404 on `main` (no tests) | same | [`fixtures/11.testreport.json`](fixtures/11.testreport.json) |
| 12 | `wfapi/describe` | **200 with stage-view, 404 without — both recorded** | 200 (stage-view installed); 404 on the `-nosv` image | 200 | [`fixtures/12.wfapi.json`](fixtures/12.wfapi.json) + [`fixtures/nosv.wfapi-404.json`](fixtures/nosv.wfapi-404.json) |
| 13 | Jenkinsfile lint | valid → OK text; broken → error text | valid: "Jenkinsfile successfully validated."; broken: "Errors encountered validating Jenkinsfile: WorkflowScript: 8: expecting '}', found '' @ line 8, column 1." | same | [`fixtures/13.lint-valid.txt`](fixtures/13.lint-valid.txt), [`fixtures/13.lint-broken.txt`](fixtures/13.lint-broken.txt) |
| 14 | 404 behaviour | 404, distinguishable from 403 | 404 (`/job/does-not-exist`) | 404 | [`fixtures/14.404.txt`](fixtures/14.404.txt) |
| 15 | 403 behaviour | 403, body `No valid crumb` | 403, `No valid crumb was included in the request` | 403 | [`fixtures/15.wrongcrumb.txt`](fixtures/15.wrongcrumb.txt) |

Notes on deviations from the charter table:

- Check 4/5/6/7 used slightly reduced `tree=` filters (`jobs[name,color,url]`,
  `builds[number,result]`, `actions[remoteUrls,_class]`) — same discovery shape,
  smaller payloads.
- Check 9's fixture (`stagecraft-day0-fixture`) has **3 authored stages**; Jenkins
  prepends an automatic `Declarative: Checkout SCM` stage for a pipeline that checks
  out its own SCM, so the parser must expect 4. Names match the authored stages.
- Check 12: the charter asks for **both** the 200 and the 404 case. The 404 came from
  a second container (`stagecraft-jenkins:day0-nosv`) with the identical plugin set
  minus `pipeline-stage-view`: `/job/seed-pipe/1/wfapi/describe` → 404 while
  `consoleText` still carried 16 `[Pipeline]` markers and the parser still segmented
  it into `Build` and `Ship` ([`fixtures/nosv.parse.json`](fixtures/nosv.parse.json)).
  The fallback path is the normal path, not an edge case.

## Parser findings (check 9)

`parse_stages.py` (stack-based, console-text only) was run against three real logs:

| Log | Result |
|---|---|
| multibranch `main` (pipeline, 4 stages) | `stageCount 4`, all names correct, `stackLeftAtEnd 0`, `endMarkerSeen true` |
| freestyle-fail console (no `[Pipeline]` markers) | `stageCount 0`, `isPipelineLog false` — degrades cleanly |
| `-nosv` seed pipeline (2 stages) | `stageCount 2` (`Build`, `Ship`) |

**Bug found and fixed during the gate:** the parser originally appended a `None`
sentinel for non-pipeline logs and crashed producing JSON
(`TypeError: 'NoneType' object is not subscriptable`). Fixed to return
`stageCount 0` with diagnostics `{stackLeftAtEnd, endMarkerSeen, isPipelineLog}` —
this is exactly the shape `dev.stagecraft.jenkins` needs to fall back to plain-log
mode.

## Endpoint behaviours worth carrying into the client design

1. **API-token auth is exempt from CSRF crumb validation.** POSTs with an API token
   and a wrong crumb succeed (HTTP 201/200). To reproduce check 15's 403 the request
   must use password basic auth. Stagecraft should therefore treat the token path as
   crumb-free and still send crumbs when the user stores user+password.
2. **Declarative lint checks structure only.** Unknown step names pass lint
   (`nonsenseStepThatDoesNotExist()` → "successfully validated"); a broken file needs
   structural errors (unbalanced braces) to produce an error response. The plugin must
   not present lint output as a full static analysis.
3. **`remoteUrls` lives on build `actions`** (`BuildData`), not on job actions —
   `/job/{mb}/job/{branch}/{n}/api/json?tree=actions[remoteUrls,_class]` (§14 risk row:
   confirmed on this LTS).
4. **`X-More-Data` is only present while the build is running.** For finished builds it
   is absent (not `false`); the client should drive tailing off `X-More-Data` presence,
   not its value.
5. **curl specifics:** bare `[]` in URLs must be passed with `--globoff`, and POSTed
   XML must be BOM-less UTF-8 (PowerShell 5.1's `Out-File -Encoding utf8` writes a BOM
   that Jenkins rejects with `Content is not allowed in prolog`).
6. **Security warnings during image build (throwaway container only, not recorded as
   product issues):** `workflow-multibranch` SECURITY-3729 (credential exposure),
   `pipeline-groovy-lib` SECURITY-3815/SECURITY-3796.

## Fixture files

Raw responses are committed under `docs/fixtures/` (see the table above). These are
the recording fixtures for §9.1 unit tests: auth, crumb, folder traversal,
multibranch branch jobs, build list, console parsing, progressive log, test reports,
wfapi presence/absence, lint, 404/403.
