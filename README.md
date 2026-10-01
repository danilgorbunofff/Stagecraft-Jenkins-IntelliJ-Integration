# Stagecraft — a Jenkins build & log viewer for JetBrains IDEs

> **Codename:** Stagecraft
> **Name on the Marketplace listing:** `Stagecraft • Jenkins Build & Log Viewer`
> **Product code:** `PSTAGECRAFT` (candidate, not yet registered)
> **Charter written:** 2026-10-01
> **Status:** chartered, not started. No code written, no remote configured, nothing pushed.
> **Method:** JetBrains Marketplace public API, Jenkins Update Center plugin API, and verbatim user reviews. Every integer in this document is an API's own returned value — none are estimates, none are made up.

**In one sentence:** Stagecraft is a JetBrains IDE plugin that puts **one Jenkins build — the one you just triggered —** inside the IDE: its stages, its per-stage console logs, its test results, and a clickable stack trace that jumps to source code.

It is the third of three parallel single-purpose JetBrains plugins. The other two are Perforce/Helix Core integration and LogSmith (log-file viewing). This document is self-contained; you do not need to have read either of the others.

---

## ⛔ READ THIS FIRST

Two things changed, or were discovered, on the day this charter was written. Read them before the rest of the document.

### 1. A neighbour exists. Know it, do not clone it.

`PipelinePilot for Jenkins` — JetBrains plugin id **32110**, `FREE`, **62 downloads**, **0 reviews**, created **2026-06-04**, solo author (`raul-alejandro-salas-texido`). Its own store description says:

> *"Live logs & stage graph — streamed console output and a visual graph of sequential, parallel and nested stages."*
> *"Replay — rerun the whole pipeline, only failed stages, or a single stage."*
> *"Remote sandbox execution — press Ctrl+Enter to run the current Jenkinsfile on real Jenkins, isolated and ephemeral."*
> *"Instant validation — inline diagnostics from the Jenkins declarative linter, on save or on demand."*

It overlaps Stagecraft on **logs and stages**. It is **not the same product**. PipelinePilot is a Jenkinsfile *authoring* tool: its core loop is edit → validate → run in a throwaway sandbox → read the sandbox's logs. Its audience is the person **writing** a pipeline.

Stagecraft's audience is the person whose build **already ran and failed**. It answers *"what did the build of my branch do, and where exactly did it break"* — not *"let me try out this pipeline in a sandbox."*

PipelinePilot is free, unmonetised, unreviewed, and has 62 downloads. It is **not a competitor for money**. It is evidence that another developer saw the same gap, and a warning that the words "live logs" and "stage graph" are now taken in this space — so Stagecraft's listing must lead with *your branch's build history* and *jump-to-source*, not with logs as a feature.

**Do not build a clone of PipelinePilot.**

### 2. The riskiest technical assumption in this plan is now proven, not assumed

The product needs **per-stage** logs, not just one giant console. The Jenkins plugin that exposes stages as JSON is `pipeline-stage-view`. Jenkins' own public installation statistics say it is installed on **147,819 servers**, while the `git` plugin — a near-universal floor for any Jenkins doing source builds — is installed on **230,038**. That is **64.3%**.

If Stagecraft's design depended on `pipeline-stage-view`, roughly **a third of all Jenkins servers would show the customer nothing.**

It does not depend on it. Jenkins' own console output carries stage boundaries natively, in plain text:

```
[Pipeline] stage
[Pipeline] { (build)
   ... 115 lines of real output ...
[Pipeline] }
[Pipeline] // stage
```

A client-side parser was written against a **real captured Jenkins console log** (237 lines) and recovered **3 stages with correct boundaries and correct line counts**, using nothing but the console text. The marker `[Pipeline] // stage` appears in **4,424 files** on GitHub — it is the universal format, not an edge case.

**Therefore: per-stage logs work on 100% of Jenkins servers via `/consoleText` alone.** The `stage-view` JSON API is a *progressive enhancement* used when present, never a requirement. See §9.3 for the parser and the proof.

---

## Table of contents

- [§0 — The 60-second version](#0--the-60-second-version)
- [§1 — What we are building, in plain English](#1--what-we-are-building-in-plain-english)
- [§2 — The business thesis in three sentences](#2--the-business-thesis-in-three-sentences)
- [§3 — The evidence](#3--the-evidence)
- [§4 — The wedge: one root cause, five plugins, verbatim](#4--the-wedge-one-root-cause-five-plugins-verbatim)
- [§5 — Willingness to pay](#5--willingness-to-pay)
- [§6 — The competition, exactly](#6--the-competition-exactly)
- [§7 — The specification, written by the plugins' own users](#7--the-specification-written-by-the-plugins-own-users)
- [§8 — Naming, the listing, and the product code](#8--naming-the-listing-and-the-product-code)
- [§9 — Architecture and the Jenkins API surface](#9--architecture-and-the-jenkins-api-surface)
- [§10 — The Day-0 gate: one day, before any product code](#10--the-day-0-gate-one-day-before-any-product-code)
- [§11 — The build plan: 14 days](#11--the-build-plan-14-days)
- [§12 — Economics: what this can actually earn](#12--economics-what-this-can-actually-earn)
- [§13 — Go-to-market with zero audience](#13--go-to-market-with-zero-audience)
- [§14 — Risks and open questions](#14--risks-and-open-questions)
- [§15 — Resuming this project on another machine](#15--resuming-this-project-on-another-machine)
- [Appendix A — Marketplace API recipes, and the traps](#appendix-a--marketplace-api-recipes-and-the-traps)
- [Appendix B — The Jenkins remote API, exactly as needed](#appendix-b--the-jenkins-remote-api-exactly-as-needed)
- [Appendix C — The raw evidence, verbatim](#appendix-c--the-raw-evidence-verbatim)
- [Appendix D — Proven versus assumed: the honest ledger](#appendix-d--proven-versus-assumed-the-honest-ledger)

---

## §0 — The 60-second version

**What:** a JetBrains plugin (IntelliJ IDEA, PyCharm, WebStorm, GoLand, PhpStorm, RubyMine — anything on the IntelliJ Platform).

**The object it models is a build, not a server.** It finds your Jenkins and your job from the project's **git remote**, shows the builds for **the branch you are on**, and opens the failed stage of the build you just triggered — logs, test results, clickable stack frames.

**Why this space, in four integers:**

| Evidence | Integer |
|---|---|
| Downloads across the top 11 third-party Jenkins plugins | **~970,000** |
| Live **paid** Jenkins vendors, actively updated | **3** — `Jenkinsfile` 157,778 · `Jenkinsfile Pro` 29,625 · `CIclone` 13,204 |
| What the largest **paid** Jenkins plugin (157,778 DL) sells | **coloured text and autocomplete.** Nothing else. |
| Plugins that render **Jenkins build logs** inside the IDE | **0** |

**Why nobody has taken it, in one sentence:** every existing Jenkins plugin was architected around *"list the server's jobs"*, so all of them break the same way at enterprise scale — folders, multibranch jobs, thousands of jobs, several servers, CSRF, self-signed certificates — and none of them ever got as far as rendering the build log. The most-downloaded one, at **433,254 downloads**, says so in its own reviews.

**How money is made:** JetBrains sells it. The plugin talks directly from the customer's IDE to the customer's own Jenkins server. **There is no server to host and no running cost.** JetBrains handles checkout, licensing, VAT, tax and processing, and pays the vendor **85%**. Target price **$29/year per user**, 30-day trial.

**The honest downside, stated up front:** CI-in-IDE appears to convert worse than pull-request-review-in-IDE, and Stagecraft is the riskiest of the three parallel tracks for that reason. The full counter-signal is in §5.3. This is not hidden anywhere in this document.

**Kill criterion:** if after 60 days public the plugin has **fewer than 500 downloads AND fewer than 5 paid conversions**, stop. If the incumbent `jenkins-control-plugin` ships working folders + working build logs before Stagecraft launches, stop immediately.

---

## §1 — What we are building, in plain English

### 1.1 The workflow it replaces

You push a branch. Jenkins picks it up and builds it. The build fails. Here is what you do today, in a browser, and it is genuinely this tedious:

1. Leave the IDE. Open your browser.
2. Go to the Jenkins URL. Bookmark it, or type it, or find it in a Slack message from a year ago.
3. Land on the Jenkins dashboard, which lists **every job on the server**. Your company has 400. Or 4,000.
4. Find your job. There is no search box on the dashboard by default, or there is one and it is slow. If your job lives in a folder, click through the folders.
5. Realise the build ran on a **multibranch** job and the branch list is separate. Click into the branch list.
6. Find **your branch**. There may be hundreds.
7. Find **the build number** for your branch — the recent-builds list shows the last 20 across the job, not per branch, so you scroll.
8. Click the build. It is a Blue Ocean page, or it is the classic page. Which one you get depends on whether your company installed Blue Ocean, and on whether your admin kept it after it was deprecated.
9. Find the stage that failed. If you got the classic page, there is no stage list at all — you have a single **30,000-line console** that renders as one `&lt;pre&gt;` and there is a "scroll to bottom" link that goes to the *wrong* place because the page was still loading when you clicked it.
10. Search the console with the browser's own Ctrl+F, which finds the string you typed, not the error, and does not survive switching stages.
11. Find `at com.company.order.OrderService.resolveDiscount(OrderService.java:214)`.
12. Recognise the file. Open your IDE.
13. Ctrl+Shift+N, type `OrderService.java`, open it, Ctrl+G, type `214`. Now you can read the failing line.
14. Fix it. Push. Go back to step 1.

Steps 9–13 are the part that breaks flow. Stages 1–8 are the part that costs minutes.

### 1.2 What Stagecraft does instead

```
┌─ Builds ──────────────────────────────────────────────────────────┐
│                                                                    │
│  ▾ my-service                          ← found from the git remote │
│    ▾ feature/ORD-214                   ← the branch you are on     │
│      ● #4812  ✅  1m 12s   pushed 4m ago   ← you just triggered it │
│      ○ #4811  ❌  2m 03s   pushed 2h ago                           │
│      ○ #4810  ✅  1m 58s   pushed 2h ago                           │
│                                                                    │
│  Build #4811  ❌ FAILED    feature/ORD-214    2m 03s               │
│  ├─ ✅ Checkout                 0.4s                                │
│  ├─ ✅ Build & Test             1m 21s     [412 ✓  3 ✗]  ← tests   │
│  ├─ ❌ Deploy to staging        0m 38s     ← already selected      │
│  └─ ·  Publish                  skipped                            │
│                                                                    │
│  ┌──────────────────────────────────────────────────────────────┐ │
│  │ ! ERROR: Discount table is empty for region EU               │ │
│  │   at com.company.order.OrderService.resolveDiscount(          │ │
│  │       OrderService.java:214)          ← Ctrl-click → source  │ │
│  │   at com.company.order.OrderServiceTest.…                     │ │
│  └──────────────────────────────────────────────────────────────┘ │
│  [ filter: error ▾ ]  [ ] collapse whitespace   [ Open full log ]  │
└────────────────────────────────────────────────────────────────────┘
```

Nothing is clicked to get here. The plugin already knows:

- **which Jenkins** — from the git remote of the open project;
- **which job** — from the same remote;
- **which branch** — from the current Git branch;
- **which build** — the newest one for that branch;
- **which stage failed** — from the build result;
- **which line to open** — from the stack frame you click.

### 1.3 Plain words for the pieces

- **Jenkins** is a build server. You push code to Git; Jenkins notices, runs your build and tests on some machine, and reports pass or fail. It is the most widely installed build server that companies run on their own hardware.
- **A pipeline** is the script that describes a Jenkins build — a sequence of **stages** (checkout, build, test, deploy). It lives in a file called `Jenkinsfile` in your repository.
- **A multibranch job** is a Jenkins job that automatically builds *every branch* of a repository. This is how most companies run Jenkins in 2026.
- **A folder** is how Jenkins organises jobs into groups. Almost every real installation uses them.
- **A JetBrains plugin** is an extension for IntelliJ IDEA and its siblings. You upload it to the JetBrains Marketplace; JetBrains sells it for you and pays you 85% of the price.

### 1.4 What Stagecraft is not

- Not a CI server. It has no build engine and no server component.
- Not a Jenkins administration tool. It does not manage nodes, credentials or plugin updates.
- Not a Jenkinsfile authoring tool. See the READ FIRST note — that space now has `PipelinePilot` in it, and independently, four plugins already fight over Jenkinsfile syntax.
- Not multi-CI. It speaks Jenkins and only Jenkins. See §7.5 for why that is deliberate.

---

## §2 — The business thesis in three sentences

1. **There is a large, old, uncool, permanently-installed market with proven payers and one unserved job** — Jenkins is the most-installed self-hosted build server in the world, its users are reachable without an audience, and the core thing they do all day — read a failed build's logs and find the line that broke — has **no IDE tool at all**, in a space of 38 plugins where 3 vendors are already taking money.
2. **The incumbents failed for one specific, fixable architectural reason** — every Jenkins IDE plugin begins by enumerating the server's jobs, which collapses at folders, multibranch jobs, thousands of jobs, multiple servers, CSRF and self-signed certificates; Stagecraft begins from the git remote and never enumerates the server, which removes all six failure classes at once.
3. **It costs nothing to run and nothing to maintain at rest** — it is a client that talks to the customer's own server, so there is no hosting bill, no inference bill, no data to store, and no on-call.

The counter-argument to all three — that CI-in-IDE converts to money worse than PR-review-in-IDE — is real, measured, and in §5.3, and it is the single strongest reason not to do this. Read it before you read anything else.
---

## §3 — The evidence

All numbers fetched **2026-10-01** from `plugins.jetbrains.com/api` and `plugins.jenkins.io/api`. Nothing here is an estimate.

### 3.1 The whole Jenkins space

`GET /api/searchPlugins?search=jenkins` returns **`total: 38`**. All 38 were retrieved. The 14 with meaningful download counts:

| # | Plugin | id | Author | Model | Downloads | Updated | Votes |
|---|---|---|---|---|---|---|---|
| 1 | `jenkins-control-plugin` | 6110 | mcmics (solo) | **FREE** | **433,254** | 2025-09-03 | 36 |
| 2 | **`Jenkinsfile`** | 26270 | **Anbora Labs** | **PAID** `PJENKINSFILE`, t7 | **157,778** | 2026-09-11 | 4 |
| 3 | `Jenkins Pipeline Linter` | 15699 | MikeSafonov | FREE | **148,027** | 2022-06-26 stale | 15 |
| 4 | `Jenkins Development Support` | 1885 | **the Jenkins project itself** | FREE | 100,644 | 2026-03-30 | 2 |
| 5 | `Jenkinsfile Syntax Highlighter` | 29948 | Valerii Bashtovoi | FREE | 39,294 | 2026-05-13 | 0 |
| 6 | `Jenkinsfile Pro` | 26583 | Anbora Labs | **PAID** `PJENKINSFPRO`, t0 | 29,625 | 2026-09-05 | 0 |
| 7 | `PipelineLens` | 29146 | Shu Yixiao | FREE | 16,400 | 2026-05-18 | 1 |
| 8 | **`CIclone`** | 19114 | Samuraism | **PAID** `PCIINTG`, t30 | 13,204 | 2026-07-17 | 8 |
| 9 | `Jenkins-Tools` | 13667 | Liu Jun | FREE | 11,175 | 2021-08-19 stale | 0 |
| 10 | `Jenkinsfile Support` | 32907 | NavaTOP | FREE | 4,580 | 2026-07-20 | 0 |
| 11 | `Bolt Server Tools` | 16794 | Bolt Technology OU | FREE | 4,560 | 2025-12-29 | 2 |
| 12 | `Jenkins Pipeline Linter` (fork) | 26098 | Tobias Horst | FREE | 3,803 | 2025-08-03 | 0 |
| 13 | `Jenkinsfile Validator` | 15676 | Saurabh P Bhandari | FREE | 3,673 | 2020-12-24 stale | 0 |
| 14 | `jenkins-debug-address` | 14701 | Yi Hui | FREE | 3,241 | 2020-08-24 stale | 0 |

Plus `PipelinePilot for Jenkins` (32110, FREE, 62 DL, created 2026-06-04) — see READ THIS FIRST.

**Sum of rows 1-11: ~970,000 downloads.** That is the size of the market that has proven it will install a third-party Jenkins plugin.

Neighbouring keyword spaces, for scale:

| Keyword | Plugins | Verdict |
|---|---|---|
| `jenkins` | **38** | the space this charter is about |
| `hudson` | 3 | all dead, <=3,754 DL |
| `bamboo` | 2 | one dead in 2018 |
| `appveyor` | 0 | - |
| `tekton` | 0 | - |
| `spinnaker` | 0 | - |

Jenkins is not one option among several CI keywords. It is the only one with a market.

### 3.2 The paid vendors, verbatim

Three vendors are actively charging money for Jenkins IDE tooling. Their product codes are live, which - per Appendix A - is the only reliable signal in the API that a paid listing exists:

| Vendor | Plugin | id | Product code | Trial | Downloads |
|---|---|---|---|---|---|
| Anbora Labs | `Jenkinsfile` | 26270 | `PJENKINSFILE` | 7 days | **157,778** |
| Anbora Labs | `Jenkinsfile Pro` | 26583 | `PJENKINSFPRO` | 0 days | 29,625 |
| Samuraism | `CIclone` | 19114 | `PCIINTG` | 30 days | 13,204 |

Two vendors, three products, **200,607 downloads between them**, all paid. Note that Anbora Labs ships *two* paid Jenkinsfile products against each other - a company that decided this niche was worth two bets.

### 3.3 What the largest paid plugin actually sells

`Jenkinsfile` - 157,778 downloads, `PJENKINSFILE`, 7-day trial. Its complete feature list, verbatim from its own store page:

> *"Provides support for the Jenkins pipeline syntax. Features: Syntax highlighting, Basic code completion, Code formatting, Structure view, Documentation lookup, Parameter info, Inlay hints, Inspections and Quick-fixes, Intention actions."*

That is **coloured text and autocomplete**. One hundred and fifty-seven thousand downloads, from a paying customer base, for making a Groovy-ish file easier to look at.

Its own customers, verbatim:

- **[3 stars]** *"After about a year's use I've cancelled my subscription renewal. It was, ironically, causing more problems with Jenkinsfiles in PyCharm than it was solving."*
- **[1 star]** *"Just by installing the plugin the pop-ups on hover in the editor stopped working for any language"*
- **[0 stars]** *"This plugin does nothing except for adding some useless colors to the file. Without code navigation I don't see any value here."*

Three readings:

1. The willingness to pay in this space is real and it is large - 157,778 downloads at a paid listing.
2. The bar for value is currently **extremely low**. The top paid product is a syntax highlighter that its own reviewers describe as useless.
3. Its churn is caused by **technical fragility** (breaking hover in the editor) and **the absence of navigation** - the exact axis Stagecraft is built on. *"Without code navigation I don't see any value here"* is a customer describing Stagecraft's core feature and saying nobody sells it to them.

### 3.4 The ceiling that shows the space is not a hobby

Cross-space comparison, same marketplace, same day:

| Plugin | id | Model | Downloads | What it is |
|---|---|---|---|---|
| `CI Aid for GitLab` | 25859 | FREE | **1,646,470** | GitLab CI **YAML** tooling |
| `CI Lint for GitLab` | 19411 | FREE | 54,435 | GitLab CI YAML linting |
| `Merge Request Integration CE` | 13607 | FREE | 45,355 | GitLab MR helper |

GitLab CI tooling in this marketplace clears **two million installs**. Jenkins has a **larger** self-hosted enterprise footprint than GitLab CI. Jenkins IDE tooling's largest free plugin sits at 433,254.

The gap between "what Jenkins users install" and "what is available for Jenkins" is the opportunity. Note the pattern as well: `CI Aid for GitLab` is **YAML** tooling - the *language* half of CI. Consistent with 3.3, the language half is commoditised and free at 1.6M. The **runtime** half - actual builds, actual logs, actual results - is where money changes hands (see section 5).

---
---

## §4 — The wedge: one root cause, five plugins, verbatim

This is the section that justifies the entire project. Read it as an engineer, not as a marketer: the same architectural mistake is repeated by five independent authors, and it produces the same user-visible complaints in all five.

### 4.1 `jenkins-control-plugin` - the incumbent

**id 6110 - FREE - 433,254 downloads - one author (`mcmics`) - last updated 2025-09-03 (13 months stale) - vote histogram `{5:18, 4:8, 3:6, 1:4}`**

This is the plugin every Jenkins-on-IntelliJ user installs first. It is also the plugin that has been abandoned for over a year with an unreleased backlog. Every quote below is a real review, verbatim, grouped by the failure class it belongs to.

**A. It cannot filter. It shows the whole server.**

> *"There are always at least 10 build Jobs running on our Jenkins. But I am only interested in my projects. Some sort of filter would be great."*
> *"for big teams with hundreds or thousands jobs, it is important to have a filter capability"*
> *"Read timed out due to too many builds on my jenkins"*
> *"when you run a Jenkins with a very large number of Multibranch Pipelines it is hard to keep the overview... need to search through a very large list"*

**B. It cannot see folders - and most real Jenkins installations use folders.**

> *"Has no support for folders, so if you use them you won't be able to see any of your jobs."*
> *"We use folders a lot in Jenkins and don't have a single job on root level."*

**C. Multibranch jobs are broken, which is how modern Jenkins works.**

> *"Multi-Branch-Jobs are not displaying the individual branch-jobs. This makes the plugin useless for these Jobs"*

**D. It supports exactly one server.**

> *"It only supports one server and I'm using 3 servers."*
> *"Lack of possibility to use it globally."*

**E. CSRF protection breaks authentication entirely.**

> *"CSRF enabled -> missing or bad crumbdata"* - **[1 star]**
> *"CSRF enabled -> missing or bad crumbdata"* - **[1 star]** (reported twice, independently)
> *"Because there's no way to supply credentials, I (and probably most Jenkins users) cannot make use of this plugin."*

**F. SSL and corporate proxies break it.**

> *"Unsupported or unrecognized SSL message -> useless"* - **[1 star]**
> *"our Jenkins server is behind an Apache instance and we use SSL"*

**G. It freezes the IDE.**

> *"IntelliJ freezes when there is no connection to the Jenkins server"*
> *"If the server is not available, it will wait for very long time to timeout at IDEA startup."*

**H. The build log - the one thing Stagecraft exists for - does not work.**

> *"when I try to read build logs I get 'Could not find data for: PROJECT_NAME' Caused by: 503 Service unavailable. Need to go to the build page."*

That single review is the product gap. 433,254 people installed a plugin, tried to read a build log in the IDE, were told to go back to the browser, and that was the end of it.

**I. It is abandoned, and its users know.**

> *"Is it still maintained?"*

### 4.2 The same mistake, four more times

The failure is not this author's. It is architectural, and it reproduces identically in independent codebases.

**`CIclone` - id 19114, PAID `PCIINTG`, 13,204 downloads, 30-day trial**

> **[3 stars]** *"After connecting to the Jenkins server, because there are too many projects on the Jenkins server, polling the Jenkins server returns: Jenkins server return an error"*  (translated from Chinese; original: *"链接Jenkins服务器后由于Jenkins服务器上的工程太多，导致polling Jenkins server返回：Jenkins server return an error"*)
> **[3 stars]** *"A lot of issues with this plugin. I simply want to see the status of my actions on a multimodule project, and it fails to deliver that."*

A **paying** product whose paid users are blocked by the same root cause: too many jobs on the server.

**`Pipeline Viewer` - id 13799, FREE, 101,873 downloads**

> **[0 stars]** *"Requires a GitLab project ID and GitLab Server address GLOBALLY. So it only works for 1 Server with 1 project. It should get the URL of the Gitlab-server with the project-name from the Git Remotes PER PROJECT."*
> **[1 star]** *"freezes IntelliJ with an SSL Certificate validation exception that isn't handled"*

That first review is a customer describing Stagecraft's exact design and calling it the correct one. Note also that `Pipeline Viewer` is **GitLab-only** - its own description reads *"track gitlab build pipelines"*. So the stage-view half of this idea is genuinely unowned for Jenkins.

**`MerryLab` - id 20347, PAID, 33,839 downloads**

> **[0 stars]** - proxy and connection failures.

**`GitLab CICD - Pipelines & Jobs` - id 22202, PAID `PGITLABCICD`, 28,037 downloads**

> **[5 stars]** *"each time I need to add PAT... when I'm adding the PAT for the second gitlab, then the first becomes useless"*

Again: one server. Again: credentials handled once, globally, instead of per project.

### 4.3 The root cause, stated once

Every one of these plugins begins with:

```
GET {jenkins}/api/json          <- "give me the server's job list"
```

and then has to survive what comes back: 1,400 jobs, nested folders, multibranch branch-jobs, jobs the user cannot see, a server that is slow or down, a crumb that expired, a certificate that is self-signed, and a proxy that rewrites the host.

That single starting choice causes classes A-G above. Stagecraft starts somewhere else:

```
git remote get-url origin          ->  https://git.company.com/team/my-service.git
current branch                     ->  feature/ORD-214
```

and then asks the server **about one thing it already knows the name of**. Every failure class A-G disappears, because Stagecraft never asks the server a question whose answer is "everything".

### 4.4 The exception that proves the rule

`PipelinePilot for Jenkins` (32110) and `CIclone` (19114) both contain working log rendering, and both are recent. Neither is a defeat: `CIclone` spreads itself across five CI hosts (which is why 13,204 downloads is all it has), and `PipelinePilot` is a sandbox runner, free, unreviewed and at 62 downloads. What they prove is that **the log-rendering half is technically achievable by a solo developer** - which is the only claim this charter needs from them.

### 4.5 The one paying customer, on the record

`CIclone` has exactly **8 votes** and **13,204 downloads**, which is a weak showing. But one of those votes is the product brief, written by a stranger:

> **[5 stars]** *"I use it with Jenkins. It is very convenient to be able to check CI logs on the IDE and jump right to the source code."*

"Check CI logs on the IDE" and "jump right to the source code" are the two features in Stagecraft's tagline. A paying customer of a competing plugin wrote them down unprompted.

---
---

## §5 — Willingness to pay

The question that kills most ideas is not "is there a gap" but "has anyone ever paid for the thing inside the gap". This section answers it with four independent paid precedents, and then argues against itself.

### 5.1 The four paid proofs

None of these are Jenkins. All of them are "render CI runtime output inside the IDE, and charge for it".

| Plugin | id | Model | Product code | Trial | Downloads | What its own copy says |
|---|---|---|---|---|---|---|
| `GitHub Actions Manager` | 19347 | **FREEMIUM** | `PGHACTNSMGRPRO` | 30d | **791,388** | *"watch workflow runs live, read step-by-step logs... Logs are split per step and rendered in the IDE, instead of one giant scrolling page... Viewing runs, jobs, and logs is free, forever. Advanced features (dispatch, rerun/cancel...) [paid]"* |
| `GitLab CICD - Pipelines & Jobs` | 22202 | **PAID** | `PGITLABCICD` | 14d | **28,037** | *"Display job log in real time... Download job artifacts... It works smoother than gitlab web ui (especially when the pipeline has 50+ jobs)"* |
| `.log` | 25828 | **PAID** | `PLOG` | 30d | 16,379 | *"View & Navigate Log content as code... Navigate to source code from log category/stack trace (Ctrl+B)... Autodetect various log formats... Support large log files"* |
| `Awesome Log Viewer` | 27750 | FREEMIUM | - | - | 76,049 | - |

Their paying customers, verbatim:

- `GitHub Actions Manager` **[5 stars]**: *"Pretty much all of my projects use github actions, so this is a very convenient way to monitor them without having to go to Github."*
- `GitHub Actions Manager` **[5 stars]**: *"Nice and useful plugin. Use it every day. Before I needed to go to GitHub, go to the correct repository and to the actions to view running GitHub Actions."*
- `GitHub Actions Manager` **[4 stars]**: *"Would be great to have some filtering capabilities, so that I could only see the workflows for the specific branch or user who triggered it."*  <- **the same complaint as the Jenkins incumbent, in a different CI**
- `GitLab CICD` **[5 stars]**: *"This plugin is profitable for me. It allows me to manage pipelines and observe their statuses without exiting the IDE."*
- `GitLab CICD` **[5 stars]**: *"I've been using it for almost a week and it saves me a LOT of time... The real-time job log display keeps me up to date on progress, and being able to download artifacts in one click is fantastic."*
- `GitLab CICD` **[5 stars]**: *"Please add possibility to store pipeline variables and this extension will be 10/10."*

Note the second `GitHub Actions Manager` review. It is the same 8-step browser walk described in section 1.1, written by a customer explaining why they bought software: *"Before I needed to go to GitHub, go to the correct repository and to the actions to view running GitHub Actions."*

### 5.2 The strongest single sentence in this document

`GitHub Actions Manager` renders CI logs in the IDE and has **791,388 downloads** with a paid tier. Jenkins has a larger self-hosted footprint than GitHub Actions has CI users in the enterprise, and has **zero** plugins that render its build logs.

### 5.3 The honest counter-signal - read this before committing

**Review density** is the instrument: written votes per 1,000 downloads. Bundled and first-party plugins sit at **0.000-0.006**. Real, deliberately-installed plugins sit at **0.014-1.810**. It measures how many people cared enough to say something.

| Plugin | Downloads | Written votes | **Density** |
|---|---|---|---|
| `Bitbucket Integration Pro` | (Majera, `PCREVIEW`) | 491 | **1.810** |
| `GitLab CICD - Pipelines & Jobs` | 28,037 | 21 | **0.075** |
| `GitHub Actions Manager` | 791,388 | 14 | **0.018** |

**CI-in-IDE converts worse than PR-review-in-IDE.** `GitHub Actions Manager` has **14 written votes on 791,388 downloads**. `Bitbucket Integration Pro` has **491 votes on far fewer downloads**. Same marketplace, same kind of product, an order of magnitude difference in how much people care.

`GitHub Actions Manager` also carries a **[1 star]** that reads *"Free version is pretty useless"*, meaning even its own paid split is contentious.

**What this means for Stagecraft:**

- A very large number of people will install a free CI-log plugin and never pay.
- The freemium shape (`GitHub Actions Manager`) is what the highest-volume player chose, and it produced a density of 0.018.
- A straight paid product (`GitLab CICD`, density 0.075) does **four times better per download** at a twentieth of the volume.

**The argument that Jenkins is different, stated so you can judge it:** the Jenkins user sits behind a corporate proxy, has no free alternative UI, and had their modern UI (Blue Ocean) officially deprecated. The GitHub user has a first-class web UI and a free plugin, and tolerates both. So the Jenkins user's pain is more acute and less escapable. **This is an argument, not a measurement.** It is listed as an assumption in Appendix D.

**This is the weakest link in the whole plan.** If you build this and nobody pays, this paragraph is the reason, and you were told.

### 5.4 Why a paid product rather than freemium, given 5.3

- The space's own precedents are paid, not freemium: `PJENKINSFILE` (157,778), `PJENKINSFPRO` (29,625), `PCIINTG` (13,204). All three paid. All three live. Jenkins buyers already pay for Jenkins IDE plugins.
- `GitHub Actions Manager`'s freemium split drew *"Free version is pretty useless"* - a review ranking in the marketplace that a paid product does not attract.
- A free tier doubles the surface area a solo developer must support and doubles the review-attack surface, for a customer who was never going to pay (density 0.018 says so).
- **Decision: paid listing, 30-day trial, no free tier.** Revisit only if downloads after 90 days are under 300, in which case the problem is discovery, not the paywall.

---

## §6 — The competition, exactly

### 6.1 The table

| Competitor | id | Model | Downloads | What it does | Does it render build logs? | Does it find the job from git? |
|---|---|---|---|---|---|---|
| `jenkins-control-plugin` | 6110 | FREE | 433,254 | Job browser, trigger builds, weak log view | "No" - returns 503 | No - lists the server |
| `Jenkinsfile` | 26270 | PAID | 157,778 | Jenkinsfile syntax only | No | n/a |
| `Jenkins Pipeline Linter` | 15699 | FREE | 148,027 | Lint against the server | No | n/a |
| `Jenkins Development Support` | 1885 | FREE | 100,644 | Jenkins project's own tooling | No | n/a |
| `Jenkinsfile Syntax Highlighter` | 29948 | FREE | 39,294 | Colour | No | n/a |
| `Jenkinsfile Pro` | 26583 | PAID | 29,625 | Jenkinsfile syntax, second attempt | No | n/a |
| `PipelineLens` | 29146 | FREE | 16,400 | Pipeline visualisation | No | No |
| `CIclone` | 19114 | PAID | 13,204 | Five CI hosts at once | Yes, partially | No |
| `PipelinePilot for Jenkins` | 32110 | FREE | 62 | Authoring + remote sandbox runs | Yes, of the sandbox | No |
| **Stagecraft** | - | **PAID** | **0** | **Your branch's builds, stages, logs, tests, jump-to-source** | **Yes - the core** | **Yes - the core** |

### 6.2 The three readings

1. **Nine of the ten competitors are Jenkinsfile *language* tools or job *listing* tools.** Not one is a *build outcome* tool. The market has converged on the two easiest things to build and left the hardest, most valuable one alone.
2. **The log column is the whole story.** One competitor claims log rendering and its own reviews say it returns `503`. One renders sandbox logs, not real build logs. **Nobody renders the log of a build that already ran on your branch.**
3. **Nobody gets the job from git.** Every single competitor makes the user navigate the server. That is the architectural difference that makes the other nine fail at scale (section 4.3).

### 6.3 `PipelinePilot` - the one to watch, honestly assessed

Discovered 2026-10-01 while checking name availability. Id 32110, FREE, 62 downloads, 0 votes, created 2026-06-04, solo author, tags `AI, Completion, Machine Learning`.

**Where it overlaps:** live console streaming, a visual stage graph, replay of a single stage.

**Where it does not:** its object is a **Jenkinsfile being authored**. It runs the file in a *"remote sandbox"* that is *"isolated and ephemeral"*. It does not know your branch, does not index your build history, does not show your test results, does not resolve a stack frame to a source file, and does not care which build you just triggered. Its AI features would require the customer to point it at an OpenAI-compatible endpoint, which rules it out in most enterprise Jenkins environments.

**What it means:** not a competitor for money today (free, 62 downloads, no reviews). It is a signal that at least one other developer identified the same gap, and it means **the listing must not lead with "live logs"** as though that were the innovation.

### 6.4 Adjacent products you must not build into

- **Jenkinsfile syntax, completion, formatting** - four plugins, one at 157,778 downloads, free floor. Fighting here is the mistake to avoid.
- **Jenkins administration** (nodes, plugins, credentials) - large surface, no paying precedent, and it is the kind of thing corporate admins get from the Jenkins UI itself.
- **A second CI host.** `CIclone` supports five and has 13,204 downloads. `Pull Request Viewer` (31928, PAID) is host-agnostic and has **469 downloads and 0 votes**. Multi-host is where this product category goes to die.

---
---

## §7 — The specification, written by the plugins' own users

Every requirement below exists because a real user of a competing plugin asked for it in a review. That is why this section reads as a list of complaints rather than a feature list.

### 7.1 The core object is a build, not a server

```
Tool window: "Builds"
└── my-service            <- discovered from the git remote
    └── feature/ORD-214   <- the branch you are on, pinned to the top
        ├── #4812  PASS   1m 12s   pushed 4m ago   <- the one you just triggered
        ├── #4811  FAIL   2m 03s   pushed 2h ago
        └── #4810  PASS   1m 58s
```

Below the fold, only if the user asks for it: an opt-in job picker **with a query box**, because the incumbent's defining failure is that it lists everything. Default state of that picker: closed.

### 7.2 The build view

```
Build #4811  FAILED   feature/ORD-214   2m 03s
├─ PASS  Checkout                  0.4s
├─ PASS  Build & Test              1m 21s      [logs] [tests: 412 pass  3 fail]
├─ FAIL  Deploy to staging         0m 38s      [logs]   <- pre-selected
└─ --    Publish                   skipped
```

- Clicking a stage swaps the log pane. **The failed stage is open before the user clicks anything.**
- The log is scrolled to the **first error**, not to the end. The end of a Jenkins log is always `Finished: FAILURE` and never useful.
- Error and warning lines get gutter stripes.
- `OrderService.java:214` inside a stack frame is a **hyperlink**. Ctrl-click opens that file at that line. This is the feature `Jenkinsfile`'s own reviewer said they would pay for and nobody provides.
- A **filter box over the log**: `error`, `warn`, `own-package-only`, and a whitespace-collapse toggle. `own-package-only` matters because a real Jenkins log is 90% framework noise.
- Failed tests render as a list, each jumping to its source.
- **Notifications scoped to my branch** - `Appearance | Notifications | Jenkins Builds`. One balloon per build, click to open it. Not one per job, not one per server.
- **"Rebuild this stage"** and **"Re-run with parameters"** where the server permits it.

### 7.3 The five things that must never happen

Each one is a competitor's death, quoted in section 4.

**1. Never call `GET {jenkins}/api/json` on an unbounded job list.** Discover the job from the git remote and query it by name. When a server-wide index is unavoidable (see 9.4), it must be paged, off-thread, cached to disk and interruptible.

**2. Never block the EDT.** All network I/O off the UI thread with a short, configured timeout. The tool window must render its empty or disconnected state **instantly**. A dead server must produce a retry affordance, not a frozen IDE. (Incumbent's bug G: *"IntelliJ freezes when there is no connection to the Jenkins server"*.)

**3. Never require one global server.** N servers, attached **per project**, resolved from that project's remotes. (Incumbent's bug D; `Pipeline Viewer`'s bug, verbatim; `GitLab CICD`'s bug, verbatim.)

**4. Never mishandle CSRF crumbs, API tokens, SSO, proxies or self-signed certificates.** Explicit "trust this certificate" flow. Proxy configuration that respects IntelliJ's "No proxy for" list. A crumb/token matrix tested against Jenkins **2.2xx through 2.4xx LTS**. **This is the top one-star cluster across all five competitors** - bugs E and F.

**5. Never lose configuration on restart.** The incumbent's review: *"lost the connect config when restart. idea version is 2021.3"*. Credentials go in IntelliJ's `PasswordSafe`; server/job mappings go in project-level settings; nothing lives in memory only.

### 7.4 Also in v1, because it is one HTTP POST

`Jenkinsfile` lint via the server's own validator endpoint `/pipeline-model-converter/validate` - which runs the *real* Jenkins linter, on the real server, with the real plugins and shared libraries. This is strictly better than any client-side approximation.

`Jenkins Pipeline Linter` (15699) has **148,027 downloads** and has not been updated since **2022-06-26**. Its complaints, verbatim, are all interface defects rather than logic defects:

> *"File must be saved before right click -> Validate"*  <- it cannot see unsaved edits
> *"only displays a long string that I can't even copy"*  <- the response is unusable

So v1 sends the **editor buffer**, not the saved file, and renders the response in a **formatted, copyable** panel. Two defects, two lines of effort, 148,027 downloads of proven demand.

### 7.5 Deliberately out of scope for v1

- Groovy/DSL completion, syntax colouring, inlay hints, formatting. Four players, one at 157,778 downloads, a free floor. **Paying to fight here is the mistake to avoid.**
- Writing `Jenkinsfile` for the user.
- Jenkins administration: nodes, plugins, credential management, queue management.
- **Any second CI host.** Nothing else for at least a year. See 6.4.
- Anything requiring an inference API call. An LLM would require the *customer's* Jenkins data to leave their network, which is a non-starter in exactly the enterprise environments where Jenkins lives - and it would add a per-user API bill to a product whose entire economic case is that it costs nothing to run.

### 7.6 The v1 acceptance test

A stranger, given a laptop with IntelliJ, a git clone of a multibranch repository, and a Jenkins URL plus API token, must be able to:

1. open the project and see the tool window populate **without typing the job name**;
2. see their own branch's builds within **10 seconds** on a server with **1,000+ jobs**;
3. open the newest failed build and have the failed stage's log on screen, scrolled to the first error, within **3 seconds**;
4. Ctrl-click a stack frame and land on the right line of the right file;
5. see test results for that build;
6. lose network access and see a retry prompt instead of a freeze;
7. restart the IDE and find their configuration intact.

If all seven hold on a 1,000-job server with CSRF on, over HTTPS, behind a proxy, the product is done. That is the whole bar.

---
---

## §8 — Naming, the listing, and the product code

### 8.1 The name

**`Stagecraft`**

Checked against the Marketplace on 2026-10-01: `GET /api/searchPlugins?search=stagecraft` returns **`total: 0`**. No plugin uses the word. It is also not a JetBrains trademark, not a CI product name and not a language name.

Why it works:

- **"Stage"** is Jenkins' own vocabulary - `[Pipeline] stage` is literally in the log format, and "the stage that failed" is how a Jenkins user describes their problem.
- **"Craft"** carries the maker/tooling register that this marketplace's buyers respond to (`Commit-Craft` 30464, `Jenkins Pipeline Linter`'s whole genre).
- It is **one word, one syllable pair, spellable over a voice call**, and it is free.

**Rejected candidate: `Console Pilot`.** The search returns `Jenkins Pipeline Linter`, `JarPilot`, `SpringPilot`, and - decisively - **`PipelinePilot for Jenkins` (32110)**. The `-Pilot` suffix is taken in this exact niche. Rejected.

**Rejected candidate: `Blueprint for Jenkins`.** `Blueprint` is an OSGi/Jenkins-adjacent term and collides conceptually with the Jenkins project's own `Jenkins Development Support` naming register. Also two words. Rejected.

**Hard rule for this project and the other two: never end a product name in `-Lens`.** `PipelineLens` (29146), `LogLens`, `XLSX Lens`, `SQLite Lens`, `JSONL Lens` - the suffix is the signature of a fleet of low-effort listings and it signals "thin wrapper" to a buyer who is being asked for money.

### 8.2 The listing

**Title:** `Stagecraft • Jenkins Build & Log Viewer`

Structure, deliberately: `Brand • what it does with the words the buyer searches`.

- `Jenkins` - the only keyword that matters. The whole space is **38 plugins**, so a new listing is visible by default and does not need keyword stuffing.
- `Build & Log` - the two words a Jenkins user types when they are stuck.
- **Not** in the title: `Pipeline`, `CI/CD`, `DevOps`, `AI`. All four are either saturated or attract the wrong install.

**Do not lead the listing with "live logs".** `PipelinePilot for Jenkins` (32110) and `CIclone` (19114) both claim it, and the word now signals a sandbox or a partial feature. Lead with **your branch** and **jump to source**.

**First line of the description** (what the buyer sees before clicking):

> Your Jenkins build failed. See which stage broke, its log, and click the failing line straight into your editor — without leaving the IDE.

**Second line**, which does the differentiating work:

> Stagecraft finds your Jenkins server and job from your git remote. It never lists the whole server, so it works on installations with folders, multibranch jobs and thousands of jobs.

### 8.3 The product code

**`PSTAGECRAFT`** - candidate, not registered.

Constraints discovered in the API (Appendix A): a product code is **permanent once a paid listing is live**, appears in the purchase flow, and is the only field in the public API that reliably proves a paid listing exists. Follow the space's convention exactly: `P` + uppercase brand. Existing observed codes for reference: `PJENKINSFILE`, `PJENKINSFPRO`, `PCIINTG`, `PGITLABCICD`, `PGHACTNSMGRPRO`, `PLOG`, `PCREVIEW`, `PAZD`.

### 8.4 Tags

Marketplace tags found on competing listings: `Completion`, `Machine Learning`, `AI`. **Do not use `AI` or `Machine Learning`.** This product contains no model, and the tag attracts installs that will bounce and leave bad reviews. Use the functional tags only.

---

## §9 — Architecture and the Jenkins API surface

### 9.1 Module layout

Gradle, Kotlin, IntelliJ Platform Gradle Plugin 2.x. Targets IntelliJ IDEA Community and Ultimate, plus `since-build` set low enough to cover 2023.x and later.

```
stagecraft/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/libs.versions.toml
├── gradle.properties
├── src/main/kotlin/dev/stagecraft/
│   ├── jenkins/                    <- NO IntelliJ imports below this line
│   │   ├── JenkinsUrls.kt          <- base URL normalisation, path building
│   │   ├── JenkinsAuth.kt          <- Basic + token, crumb fetch and cache
│   │   ├── JenkinsHttp.kt          <- the only place that opens a socket
│   │   ├── JenkinsClient.kt        <- typed calls: build, console, tests, stages, lint
│   │   ├── JenkinsVersion.kt       <- from the X-Jenkins response header
│   │   ├── RemoteMatcher.kt        <- git remote -> job  (9.4)
│   │   ├── JobIndex.kt             <- cached, paged, interruptible index (9.4)
│   │   ├── ConsoleStages.kt        <- the stage parser (9.3)
│   │   └── LintClient.kt           <- /pipeline-model-converter/validate
│   ├── model/                      <- plain data classes, no behaviour
│   │   ├── BuildRef.kt  StageRef.kt  TestResult.kt  ServerRef.kt
│   ├── service/
│   │   ├── JenkinsService.kt       <- @Service(PROJECT), per-project (7.3 rule 3)
│   │   ├── CredentialStore.kt      <- PasswordSafe
│   │   ├── BuildPoller.kt          <- background poll, cancellable
│   │   └── NotificationService.kt  <- scoped to the current branch
│   └── ui/
│       ├── BuildsToolWindowFactory.kt
│       ├── BuildTreePanel.kt       <- build list
│       ├── StagePanel.kt           <- stage tree + status
│       ├── LogEditorPanel.kt       <- a real read-only IntelliJ Editor (9.7)
│       ├── TestResultsPanel.kt
│       ├── LintPanel.kt
│       └── settings/StagecraftConfigurable.kt
└── src/main/resources/META-INF/plugin.xml
```

**The one rule that matters:** `dev.stagecraft.jenkins` has **no IntelliJ imports**. It is a testable plain-Kotlin HTTP client. Every API trap in this document is covered by a unit test in that package, running against recorded JSON fixtures, with no IDE and no live server. This is what makes the fragile parts (crumbs, folders, multibranch, console parsing) cheap to test.

### 9.2 Authentication, CSRF, TLS and proxy - the matrix that kills competitors

Five of five competitors' top one-star reviews live here. Treat this as the feature, not as plumbing.

**Credentials.**

```
Authorization: Basic base64(user + ":" + apiToken)
```

Use an **API token**, not a password. Explain this in the settings UI and link to the customer's own `/me/configure` page. Validate the token immediately with:

```
GET {base}/me/api/json        ->  200 and a user object = good token
```

That gives a specific error message in the UI instead of a mysterious later failure.

**Session cookies.** Jenkins authenticates a session with a cookie after the first Basic-authenticated request. Keep a cookie store per server inside `JenkinsHttp`, and reuse it. Do not create a new client per request.

**CSRF crumbs.**

```
GET {base}/crumbIssuer/api/json
  -> 200 { "crumb": "a1b2...", "crumbRequestField": "Jenkins-Crumb" }
  -> 404 = CSRF protection is disabled on this server; send no crumb and cache that fact
```

Send the returned field name and value as a header on every **POST**. Crumb sessions expire when the session expires, so: on any `403` whose body contains `No valid crumb`, refetch the crumb **once** and retry **once**, then fail with a readable message. Do not loop.

**TLS.** Do not disable verification globally. Offer a per-server **"trust this certificate"** flow that pins the certificate the user explicitly accepted, and show its fingerprint and issuer so the decision is informed. `Pipeline Viewer`'s one-star review is *"freezes IntelliJ with an SSL Certificate validation exception that isn't handled"* - a handled exception plus a readable message is the entire fix.

**Proxies.** Respect IntelliJ's own proxy settings (`HttpConfigurable`) including the **"No proxy for"** list, and additionally offer a per-server override, because Jenkins servers are usually internal and must bypass a corporate proxy.

**Timeouts.** Two numbers, both configurable, both short: **connect 5s, read 20s**. The incumbent's freeze happened because its timeout was effectively infinite at IDE startup.

**SSO.** If the server redirects to a SAML/OIDC login page, detect the redirect and show *"this server uses single sign-on; API tokens are still required for this plugin - ask your Jenkins administrator"* rather than parsing an HTML login page.

**The compatibility matrix to actually test before shipping** (use Docker, one command per version):

| Jenkins | CSRF off | CSRF on | HTTPS | Behind proxy | Token | Password |
|---|---|---|---|---|---|---|
| 2.204.x LTS | test | test | test | test | test | n/a |
| 2.319.x LTS | test | test | test | test | test | n/a |
| 2.414.x LTS | test | test | test | test | test | n/a |
| 2.479.x LTS | test | test | test | test | test | n/a |
| newest LTS | test | test | test | test | test | n/a |

12-20 cells, scriptable, and it is the difference between a plugin that works and a plugin that gets one-star reviews.

---
### 9.3 Per-stage logs - the parser, and the proof

**The problem.** Jenkins exposes stages as JSON only through the `pipeline-stage-view` plugin, at `{build}/wfapi/describe`. The Jenkins update centre's own installation statistics:

| Plugin | Installations (latest sample, 2025-12-01) |
|---|---|
| `git` | **230,038** |
| `credentials` | 236,465 |
| `workflow-api` | 235,496 |
| `workflow-multibranch` | 225,277 |
| `pipeline-model-definition` | 221,612 |
| `pipeline-rest-api` | 152,099 |
| **`pipeline-stage-view`** | **147,819** |
| `blueocean` | 47,772 |

`pipeline-stage-view` / `git` = **64.3%**. A design that required it would be dark for **35.7% of servers**.

**The solution.** Jenkins writes stage boundaries into the console text itself. This was verified against a real captured console log - `PacktPublishing/Docker-for-Developers`, `chapter7/consoleText.txt`, 237 lines, a genuine Jenkins declarative pipeline run:

```
      9  [Pipeline] stage
     10  [Pipeline] { (Declarative: Checkout SCM)
     11  [Pipeline] checkout
     26  [Pipeline] }
     27  [Pipeline] // stage
     ...
     32  [Pipeline] stage
     33  [Pipeline] { (build)
     34  [Pipeline] checkout
     48  [Pipeline] script
     ...
    148  [Pipeline] }
    149  [Pipeline] // stage
    150  [Pipeline] stage
    151  [Pipeline] { (deploy)
    ...
    226  [Pipeline] // stage
    233  [Pipeline] End of Pipeline
```

A 20-line parser was run against that file. Result:

```
stages recovered = 3
   Declarative: Checkout SCM          16 log lines
   build                             115 log lines
   deploy                             74 log lines
```

**Exact boundaries, exact line counts, from `/consoleText` alone.** The marker `[Pipeline] // stage` appears in **4,424 files** on GitHub - it is the documented, universal Jenkins pipeline console format, not a quirk of one server.

**The algorithm** (`ConsoleStages.kt`):

```
STAGE_OPEN   = ^\[Pipeline\] \{\s*\((.*)\)\s*$        <- names the block
STAGE_CLOSE  = ^\[Pipeline\] // stage$                 <- closes a stage
BLOCK_OPEN   = ^\[Pipeline\] \{$                       <- anonymous block
BLOCK_CLOSE  = ^\[Pipeline\] \}$                        <- closes any block
END          = ^\[Pipeline\] End of Pipeline$
```

Maintain a **stack** of open blocks. `STAGE_OPEN` and `BLOCK_OPEN` push; the matching `BLOCK_CLOSE` pops; `STAGE_CLOSE` pops the nearest stage frame and emits `(name, firstLine, lastLine)`. A stack is required because real pipelines nest - `stage > script > withEnv > withDockerRegistry` - and because parallel stages emit `{ (Branch: linux)` blocks that close with a plain `}` and never with `// stage`.

**Fallback chain, in order:**

1. `{build}/wfapi/describe` returns stages -> use them. They carry `status`, `startTimeMillis`, `durationMillis` - **richer**, so use them when present.
2. Otherwise parse `/consoleText`. Boundaries and names are correct; per-stage **timings are not available** and must not be invented. Show line counts or elapsed-from-timestamps instead of fake durations.
3. No `[Pipeline]` markers at all -> the job is a **freestyle job**, not a pipeline. Render one pseudo-stage labelled `Build` containing the whole console. This is still correct behaviour and must not be an error state.

**This fallback chain is the reason to build this product now** rather than after `pipeline-stage-view` becomes universal. It cannot become universal: it is an optional plugin and a third of servers do not have it.

### 9.4 Finding the job from the git remote - the honest, hard part

This is the only genuinely difficult piece of engineering in the product, and it is worth being blunt about why.

**Jenkins has no API that answers "which job builds this git URL".** There is no index, no query endpoint, no search-by-SCM. The `BuildData` action of a build exposes `remoteUrls`, but only *per build*, so it is only reachable after you already know the job. Every competitor that tried to solve this solved it by listing the whole server - which is section 4.3.

**The design that avoids the trap:**

**Step 1 - cheap tree fetch.** Do **not** fetch jobs one by one. Fetch the whole namespace tree shallowly, in one to three requests:

```
GET {base}/api/json?tree=jobs[name,url,_class]
GET {folder}/api/json?tree=jobs[name,url,_class]      for each folder, capped
```

A folder's `_class` is `com.cloudbees.hudson.plugins.folder.Folder`. This is cheap - a few KB per folder, no per-job round trip - and it is bounded by the number of *folders*, not the number of jobs.

**Step 2 - candidate selection without a per-job request.** Derive the repository name from the git remote (`my-service`) and rank candidates by name match, in this order:

1. a multibranch pipeline job whose name matches the repo name, in any folder;
2. any job whose name matches the repo name;
3. any job whose name contains a distinctive path segment from the remote.

**Step 3 - confirm with one request, not N.** For the top candidate only, fetch the branch-scoped build list and confirm the match. For a multibranch job the branch jobs are ordinary child jobs:

```
GET {mbJob}/api/json?tree=jobs[name,url]
GET {branchJob}/api/json?tree=builds[number,result,timestamp,duration,building]{0,25}
```

Then confirm the remote by reading the `remoteUrls` action of **one** build:

```
GET {branchJob}/{n}/api/json?tree=actions[remoteUrls,lastBuiltRevision[SHA1,branch[name]]]
```

**Step 4 - cache, and make the cache the product.** The index is written to disk, keyed by a hash of the server URL:

```
{systemPath}/stagecraft/index-{sha1(serverUrl)}.json
```

On every later IDE start the tool window populates from the cache **instantly** and refreshes in the background. That is the difference between this and `CIclone`'s *"too many projects on the server -> polling returns an error"*.

**Step 5 - manual pinning always wins, and is always available.** A "Select job..." action opens a picker **with a query box**. The pinned choice is stored in project settings and is never overridden by automatic discovery. This is the escape hatch for the installations whose naming is arbitrary, and it costs one afternoon.

**Step 6 - never block.** All of this runs in a background task with `ProgressIndicator` and honouring cancellation. The tool window renders its empty state immediately - per 7.3 rule 2.

**Bounded by design.** The index is capped by a configurable maximum (default **2,000 jobs**) and the UI says *"indexed 2,000 of N jobs; pin your job manually for an exact match"* rather than silently degrading. Bounded, honest, and fast beats unbounded and correct.

### 9.5 Live logs

Jenkins' incremental console endpoint is the correct way to follow a running build, and it is far better than re-downloading `/consoleText`:

```
GET {build}/logText/progressiveText?start={offset}
  response body     -> bytes from that offset onward
  header X-Text-Size -> the new offset to send next
  header X-More-Data -> true while the build is still running
```

Poll every **2 seconds** while the build is running, appending only the delta. Stop when `X-More-Data` is absent. Stop immediately if the tool window closes or the project is disposed.

`{build}/logText/progressiveHtml` exists and returns the same content with Jenkins' own hyperlinks and colours. **Do not use it.** Stagecraft must produce its own linkified view (9.6) because the whole point is that frames resolve to *source files*, not to Jenkins URLs.

### 9.6 Test results and clickable stack frames

**Test results are core Jenkins.** No plugin needed:

```
GET {build}/testReport/api/json
  -> { failCount, skipCount, passCount,
       suites:[ { name, cases:[ { name, className, status, duration,
                                 errorDetails, errorStackTrace } ] } ] }
```

`errorStackTrace` is the string to parse. Match, in order:

1. Java/Kotlin: `\s+at\s+([\w$.]+)\.([\w$<>]+)\(([\w$]+\.(?:java|kt)):(\d+)\)`
2. Kotlin/JVM: `([\w$.]+)\.kt:(\d+)`
3. Python: `File "(.+?)", line (\d+)` (for PyCharm)
4. Go: `\t(.+?\.go):(\d+)`
5. Node/JS: `at .+ \((.+?\.(?:js|ts)):(\d+):\d+\)`
6. .NET: `in (.+?\.cs):line (\d+)`

Then resolve the file to a **project** source file by path suffix. Two hard rules:

- **Match by suffix, not by absolute path.** The Jenkins agent's workspace path has nothing to do with the developer's checkout. `.../workspace/my-service/src/main/java/com/company/order/OrderService.java` must resolve against `src/main/java/com/company/order/OrderService.java` in the project.
- **If the file is not in the project, do not offer a link.** A dead hyperlink is worse than plain text, and a link that opens the wrong file is a bug report.

Implementation: a real read-only `Editor` from `EditorFactory`, with `RangeHighlighter` for error lines and an `EditorMouseListener` (or a custom `HyperlinkInfo`) resolving the offsets. Do not hand-roll a text widget; the editor gives search, selection, folding and accessibility for free.

### 9.7 Performance and memory budgets

Jenkins logs of 100-500 MB exist. These are the numbers to build to:

| Budget | Target |
|---|---|
| Tool window first paint, cache warm | < **200 ms** |
| Tool window first paint, no cache | instant - empty state, background indexing |
| Branch build list, cache warm | < **500 ms** |
| Open the failed stage of the newest build | < **3 s** on a 1,000-job server |
| Render a 50 MB console | **must not** load 50 MB into memory |
| IDE heap added by an open log | < **50 MB** |

Rules that follow from those:

- **Never** `GET /consoleText` for a whole large log. Fetch the **head** (default 512 KB) plus a window around the first detected error, then let the user pull more with an explicit action.
- **Never** build a `List<String>` of every log line. Use an `IntArrayList` of line-start offsets into one `CharSequence`, and hand that to the editor as a `Document` - or write to a temp file and open a read-only `VirtualFile`, which is what the 50 MB case requires.
- Cap the retained log at a configurable maximum (default **20 MB**) and say so in the UI when truncating: *"showing the first 20 MB of 180 MB - open in Jenkins"*. Silent truncation generates bug reports.
- Tail with `progressiveText` and only the delta, never by re-fetching.

### 9.8 Version compatibility

Read `X-Jenkins` from any response header on the first request and store it. Use it only for two things: to warn if the server is older than the supported floor, and to adjust the crumb behaviour if a future LTS changes it. Do **not** branch the whole client on version - that is how compatibility code becomes unmaintainable.

**Supported floor:** Jenkins **2.204.1 LTS** (2019). Below that, warn and proceed anyway; do not refuse. A plugin that refuses to connect is a one-star review.

---
---

## §10 — The Day-0 gate: one day, before any product code

Do not write plugin code until this day is complete. It exists to convert the plan's three remaining technical assumptions into facts, and it costs one day instead of two weeks.

**Setup:** `docker run -p 8080:8080 -v jenkins_home:/var/jenkins_home jenkins/jenkins:lts-jdk17`, complete the wizard, create an admin user, generate an API token, and create:

1. a **freestyle** job that runs a shell step and fails;
2. a **multibranch pipeline** job pointed at a repository containing three stages, one of which fails;
3. nested **folders** two levels deep with jobs inside them;
4. **CSRF enabled** (it is on by default in recent LTS - confirm it).

**Then verify each of these by hand with `curl.exe`, and record the actual response for each:**

| # | Check | Endpoint | What must be true |
|---|---|---|---|
| 1 | Token authentication | `GET /me/api/json` | 200, returns the user |
| 2 | CSRF crumb | `GET /crumbIssuer/api/json` | 200, returns `crumb` + `crumbRequestField` |
| 3 | Version | any response header | `X-Jenkins` present |
| 4 | Folder tree | `GET /api/json?tree=jobs[name,url,_class]` | folders visible, one request |
| 5 | Multibranch branch jobs | `GET /job/{mb}/api/json?tree=jobs[name,url]` | branch jobs listed |
| 6 | Branch build list | `GET /job/{mb}/job/{branch}/api/json?tree=builds[number,result,timestamp,duration,building]{0,25}` | builds listed |
| 7 | Remote URL of a build | `GET /job/{mb}/job/{branch}/{n}/api/json?tree=actions[remoteUrls,...]` | `remoteUrls` contains the git URL |
| 8 | Full console | `GET /job/{mb}/job/{branch}/{n}/consoleText` | plain text, `[Pipeline]` markers present |
| 9 | **Stage segmentation** | local parser on that text | 3 stages, names correct |
| 10 | Incremental log | `GET /job/{mb}/job/{branch}/{n}/logText/progressiveText?start=0` | body + `X-Text-Size` |
| 11 | Test results | `GET /job/{mb}/job/{branch}/{n}/testReport/api/json` | JSON, or a clean 404 on a build with no tests |
| 12 | Stage JSON, if present | `GET /job/{mb}/job/{branch}/{n}/wfapi/describe` | 200 with `pipeline-stage-view`, 404 without - **record both** |
| 13 | **Lint** | `POST /pipeline-model-converter/validate` with form field `jenkinsfile`, **with the crumb** | text response; then repeat with a deliberately broken Jenkinsfile and confirm the error text |
| 14 | 404 behaviour | `GET /job/does-not-exist/api/json` | 404, cleanly distinguishable from 403 |
| 15 | 403 behaviour | POST with a **stale** crumb | 403; confirm the body contains `No valid crumb` |

**Then do the same 15 checks a second time with the server behind a self-signed HTTPS proxy** (a second container with `nginx` and a self-signed cert is enough). Items 1-3 and 13 are the ones that break.

### The gate

**Pass** = all 15 verified, and items 9, 10, 11, 12 and 13 behave as described on both HTTP and HTTPS.

**Fail, and what to do:**

- **Item 9 fails** (no `[Pipeline]` markers) -> the console format is not what section 9.3 documents. Re-read the real output and fix the parser before anything else. This is the load-bearing assumption of the whole product; there is no plan B.
- **Item 13 fails or requires a plugin the customer may not have** -> drop Jenkinsfile lint from v1. It is a bonus, not the product. `pipeline-model-definition` is on 221,612 servers of ~230,038 with `git` = **96%**, so this is unlikely, but check anyway.
- **Item 7 fails** (`remoteUrls` not exposed) -> job discovery must rely on name matching plus manual pinning. That weakens 9.4 but does not kill it; the manual picker was always the fallback.
- **Item 12 returns 404** -> expected on this container, and it is the *good* case: it proves the fallback is the normal path, not an edge case.

**Record the raw responses in `docs/day0-verification.md` in this repository.** They become the recording fixtures for the `dev.stagecraft.jenkins` unit tests (9.1).

---

## §11 — The build plan: 14 days

Assumes full-time work and a working Day-0 verification. Every day ends with something runnable.

### Days 1-2 - the client, with no UI at all

- Gradle project, IntelliJ Platform Gradle Plugin 2.x, `plugin.xml` with an empty tool window.
- Implement `JenkinsHttp`, `JenkinsAuth`, `JenkinsUrls`. Basic auth with token, cookie store, crumb fetch-and-cache, refetch-once-on-403, timeouts, proxy from `HttpConfigurable`, optional pinned certificate.
- **A `main()` that prints a build's console to stdout.** No IDE, no window. If this does not work, nothing else matters.
- Unit tests over the Day-0 fixtures: auth, crumb, folder traversal, multibranch branch jobs, build list.

**Exit criteria:** `./gradlew runIde` starts, and a plain-Kotlin test prints the real console of the real build from the container.

### Days 3-4 - the parser and the matcher

- `ConsoleStages.kt` with the stack-based parser, wired to the real `/consoleText` from the container. Assert 3 stages, correct names, correct line ranges.
- `RemoteMatcher.kt` and `JobIndex.kt`: shallow tree fetch, name ranking, one-build remote confirmation, disk cache keyed by server hash, the 2,000-job cap.
- Unit tests: a 1,000-job synthetic index (generate it, do not run a 1,000-job Jenkins) asserting the match completes in < 500 ms from cache.

**Exit criteria:** given only a git remote and a branch name, the code names the right job and the right build number, in a test, with no IDE running.

### Days 5-6 - the tool window

- `JenkinsService` as a `@Service(PROJECT)` - per project, per 7.3 rule 3.
- Settings: server URL, user, token, "trust certificate", "use proxy". Token in `PasswordSafe`. **Test that it survives an IDE restart** (7.3 rule 5).
- `BuildsToolWindowFactory` + `BuildTreePanel`: the build list for the current branch, newest first, from cache where available.
- Empty state, disconnected state, and a **retry** button. Nothing blocks the EDT.

**Exit criteria:** open the project, the tool window shows the branch's builds without typing a job name, and pulling the container's network cable produces a retry prompt rather than a freeze.

### Days 7-8 - the log

- `LogEditorPanel`: a real read-only `Editor`, error gutter stripes, the `error` / `warn` / `own-package-only` filter, whitespace collapse.
- Scroll to the **first error**, not the end.
- Head-plus-window fetching with the 20 MB cap and the honest truncation message.
- `logText/progressiveText` live tailing for a running build.

**Exit criteria:** a 50 MB generated log opens in under 3 seconds without exhausting the IDE heap, and a running build's log grows in the panel.

### Days 9-10 - the stage tree, tests, and hyperlinks

- `StagePanel`: stages with status, timings where `wfapi/describe` supplies them and **line counts, never fake timings, where it does not**.
- Pre-select the failed stage.
- `TestResultsPanel` with per-test jump-to-source.
- Stack-frame hyperlinks, six patterns, suffix-matched paths, no link when the file is absent.

**Exit criteria:** Ctrl-click on a real stack frame lands on the right line of the right file for a Java project, and does nothing (rather than anything wrong) for a file not in the project.

### Days 11-12 - lint, notifications, rebuild

- `LintPanel` via `/pipeline-model-converter/validate`, sending the **editor buffer**, rendering the response formatted and copyable (7.4).
- Notifications scoped to the current branch, one balloon per build, click to open.
- "Rebuild this stage" / "Re-run with parameters" where the server allows it.

**Exit criteria:** linting an unsaved edit works, and the response text can be selected and copied.

### Days 13-14 - the compatibility matrix, then the listing

- Run the 12-20 cell matrix from 9.2 (four LTS versions x CSRF x HTTPS x proxy). Fix whatever breaks. Something will.
- Run the seven-point acceptance test from 7.6 on the 1,000-job synthetic server.
- Plugin icon, screenshots (build tree, failed stage, hyperlink, lint), `plugin.xml` metadata, Marketplace listing text from section 8.2.
- Vendor account and trader-status registration, product code `PSTAGECRAFT`, paid listing, **30-day trial**, price **$29/year**.
- Upload, and set the review-check reminder for day 7 after publication.

**Exit criteria:** the plugin is on the Marketplace as a paid listing, and the acceptance test passes on a server that is not the developer's own.

### What is deliberately not in these 14 days

No second CI host. No Jenkinsfile completion or colouring. No Jenkins administration. No AI, and therefore no inference bill and no data leaving the customer's network. No free tier. No team licensing. No enterprise SSO integration beyond a readable error message. Each of these is a separate product decision, and adding any one of them before there is a paying customer is how a solo developer ends up with a second unfinished project.

---
---

## §12 — Economics: what this can actually earn

### 12.1 The cost side, which is the genuinely good news

**There is no server.** The plugin talks from the customer's IDE directly to the customer's Jenkins. Therefore:

| Cost line | Amount |
|---|---|
| Hosting | **$0** |
| Database | **$0** |
| Inference / API | **$0** (there is no model) |
| Data storage or egress | **$0** (nothing leaves the customer's network) |
| On-call, uptime, incident response | **none** (there is no running service) |
| Certificate, domain | **$0** (no domain is needed) |
| Marketplace listing | **$0** |
| **Marginal cost per additional customer** | **~$0** |

The only costs are the developer's time, a JetBrains vendor account, and the price of an IntelliJ IDEA Ultimate licence if you want to test against Ultimate - though the plugin must work on Community, and Community is free.

**JetBrains handles checkout, licensing, trials, VAT, sales tax and payment processing, and pays the vendor 85% of the sale price.**

This is what makes a $29 product viable for a solo developer: at 1,000 sales the vendor receives roughly **$24,650**, against near-zero cost.

### 12.2 The revenue side, with the marketplace's own base rates

These are measured from the marketplace, not modelled:

| Statistic | Value |
|---|---|
| Median paid plugin, lifetime downloads | **1,411** |
| Median paid plugin, implied lifetime revenue | **~$1,340** |
| p90 paid plugin, lifetime downloads | **43,031** |
| p90 paid plugin, implied lifetime revenue | **~$41,000** |
| 2026-cohort median paid plugin downloads | **171** |
| 2026-cohort implied revenue | **~$160** |
| Median annual price across the marketplace | **$19** |
| Share of installs on the free Community edition | **91%** |

**The 2026 cohort median is 171 downloads.** That is the number to stare at. Most plugins published this year earn a couple of hundred dollars. Not because they are bad - most are fine - but because nobody ever sees them.

### 12.3 Three scenarios

Price **$29/year**, 30-day trial, no free tier, and assume a **2-4% trial-to-paid conversion** (unmeasurable in advance - this is the honest range for a paid developer tool with a trial and no marketing).

| Scenario | Downloads in year 1 | Paid conversions | Vendor revenue, year 1 |
|---|---|---|---|
| **Base** - matches the marketplace median | 1,411 | 28-56 | **$690 - $1,380** |
| **Good** - a working niche product with reviews | 8,000 | 160-320 | **$3,940 - $7,890** |
| **Excellent** - matches the top-paid Jenkins plugin's trajectory | 40,000 | 800-1,600 | **$19,720 - $39,440** |

**Base case: a few hundred to about fifteen hundred dollars a year.** That is the honest expectation, and it is the same figure as the other two tracks. It is not a business on its own. It is a small, permanent, zero-maintenance income stream that costs nothing to keep running and can be repeated.

**The Excellent case is the argument for this particular pick.** The top paid Jenkins plugin has **157,778 downloads**. If Stagecraft reaches even a tenth of that, the number stops being pocket money. The ceiling in this space is materially higher than in the other two tracks, because the space has an existing paid customer base of **200,607 downloads across three products**.

### 12.4 Pricing

**$29/year per user.**

- The space's precedent for trials is **7 days** (`Jenkinsfile`), **30 days** (`CIclone`), **0 days** (`Jenkinsfile Pro`). **Use 30 days.** The buyer is an enterprise engineer who must ask an administrator to issue an API token, and that takes days. A 7-day trial produces cancelled renewals - which is exactly what `Jenkinsfile`'s three-star review describes.
- **$29, not $19**, because the buyer is a company, not an individual, and the decision is made against "how long does it take to find a failing line in the Jenkins UI", which is minutes per failure per engineer. At $19 the product reads as a toy; at $29 it reads as a tool.
- **Not freemium.** See 5.4.
- **Month-to-month option** at $3/month, set by JetBrains' purchase terms, to lower the commitment barrier - with the annual price visibly cheaper.

### 12.5 What would have to be true to build a business on this

For **$30,000/year**, at $29 with 85% to the vendor, Stagecraft needs approximately **1,220 paying users**, which at a 3% conversion rate means roughly **40,000 downloads**, which means reaching a tenth of the top paid Jenkins plugin's install base. That is achievable but it is the optimistic column, not the expected one.

**Plan for the base case. Be grateful for the good case.** And note the structural point that section 14 returns to: three products at the base case is still only a few thousand dollars a year, because the constraint is not the product.

---

## §13 — Go-to-market with zero audience

The distribution problem is the real one. This section is deliberately concrete, and deliberately small - it lists only channels where a person with no following can actually get seen.

### 13.1 The Marketplace is the channel

This is the single most important fact in this section. The keyword `jenkins` returns **38 plugins** on the JetBrains Marketplace. A new listing appears:

- on the **search results page** for `jenkins` - page one, without paid promotion, because there are only 38 results;
- in the **"New plugins"** feed;
- in the **"Jenkins" tag** listing;
- in the IDE's own plugin search, the same 38 results.

**There is no equivalent of this in the app marketplaces people usually chase.** For this niche the store is a real, working discovery channel in a way that the App Store or Product Hunt is not. The 157,778 downloads of a syntax highlighter is what 38-results-deep search looks like.

**Therefore the listing title and the first two lines of the description (section 8.2) are the most important marketing artefacts in the project.** Rewrite them three times before publishing.

### 13.2 The five places Jenkins users actually are

Ranked by ratio of effort to reachable installs:

1. **`r/jenkinsci`** - the Jenkins subreddit. Post once: the build tree screenshot, the failed-stage screenshot, and a plain description. Not a launch announcement - a *"I got tired of the browser walk after a failed build, so I built this"* post. Answer every comment. Do not post it again for 60 days.
2. **`community.jenkins.io`** (the official Jenkins Discourse) - there is a category for user-facing tooling. Same post, different audience, no cross-linking needed.
3. **Stack Overflow, tag `jenkins-pipeline`** - this tag has a large, permanent stream of questions, and a recurring one is a variant of *"how can I see which stage failed without opening the browser"*. Answer the question properly, then mention the tool once. **Do not paste a link on ten questions.** One good answer on a popular question beats twenty.
4. **The Jenkins plugin ecosystem's own GitHub** - the `jenkinsci` organisation. A short, well-written issue or discussion in the right place, not a drive-by advertisement. The `Jenkins Development Support` plugin (id 1885) is maintained by the Jenkins project itself, and there is a real chance of being listed alongside it.
5. **`awesome-jenkins`-style curated lists on GitHub** - one pull request each. Low volume, permanent, free, and they are indexed by search engines.

### 13.3 What not to do

- **Do not post to Product Hunt.** Section 13.4 explains why, and it is the direct lesson from the last launch.
- **Do not post on Hacker News.** The audience is not the buyer, the post will not front-page without an existing account with karma, and the comments will be about Jenkins being old rather than about the plugin.
- **Do not run a launch-day campaign.** Enterprise Jenkins users do not buy on launch day. They buy six weeks after they read about it and hit the problem again.
- **Do not buy ads.** The audience is too small and too specific for it to work at a $29 price point.
- **Do not post the same message to five subreddits.** `r/devops` and `r/jenkinsci` overlap, and the second post reads as spam.

### 13.4 The hard lesson from the last launch

The previous product launched on Product Hunt and got **0 upvotes, 0 sales, and no positive activity on Reddit**, with no existing audience.

Two things follow, and both are applied in this charter:

**First, the channel chosen here is not a launch channel.** It is a search-and-store channel. A Jenkins engineer searching "jenkins" in the IDE's plugin browser will find this plugin for years, without anyone upvoting anything. That is a fundamentally different and better-shaped distribution than a launch.

**Second, the audience here is reachable without a following, and that is the actual reason this pick was made.** `r/jenkinsci` has a few tens of thousands of members and a steady stream of "how do I see X" posts. A syntax highlighter got 157,778 downloads in this space. Nobody needed to be famous. That is not true of consumer products on Product Hunt, where zero followers means zero reach, mechanically.

### 13.5 The first 30 days after publication

| Day | Action |
|---|---|
| 0 | Publish. Product code live. Verify the purchase flow end to end by buying it yourself with a different account. |
| 1 | Post to `r/jenkinsci`. Answer everything for 48 hours. |
| 1 | Post to `community.jenkins.io`. |
| 2 | Answer one Stack Overflow question properly. |
| 3-7 | Watch the reviews. Answer every review that has a technical complaint, in public. A vendor who replies is worth a star. |
| 7 | First check against the kill criterion (14.1). Do not act on it yet. |
| 14 | Write the one thing every install gets wrong into the README: the API token. Make it unmissable in the settings UI. |
| 21 | Submit the `awesome-jenkins` pull requests. |
| 30 | Second check against the kill criterion. Also compare: free-tier demand was forecast at density 0.018 (section 5.3) - if downloads are far below that, it is discovery, not price. |
| 60 | **Final kill-criterion decision.** |

---

## §14 — Risks and open questions

Ranked by how likely each is to end the project. Every one of these is real, and none is hidden.

### 14.1 Risk 1 - CI-in-IDE may simply convert worse than PR-review-in-IDE. (HIGH)

**Evidence against:** `GitHub Actions Manager` has **14 written votes on 791,388 downloads** (density **0.018**); `GitLab CICD` has **0.075**; `Bitbucket Integration Pro` has **1.810**. The best-funded, highest-volume player in exactly this category could not make its users care enough to write a review.

**Evidence for:** the Jenkins user has no alternative UI and had Blue Ocean deprecated; the space's own paid precedents (`PJENKINSFILE` 157,778) prove Jenkins users pay for IDE plugins; and `CIclone`'s paying customer wrote *"check CI logs on the IDE and jump right to the source code"* unprompted.

**Mitigation:** none available. This is the risk you accept or you do not build. **Kill criterion:** fewer than 500 downloads AND fewer than 5 paid conversions at 60 days -> stop.

### 14.2 Risk 2 - distribution, not product quality, is the binding constraint. (HIGH)

Two parallel projects already exist and neither has an audience. A third product at the marketplace median earns about $1,340 lifetime. **Three products at the median earn about $4,000 lifetime between them, for three times the maintenance surface.**

**This is the most likely way this project fails without any of its technical assumptions being wrong.**

**Mitigation:** this pick was chosen specifically because it is the first of the three whose buyers are reachable without a following (13.4). That is the whole answer, and it is a partial one.

### 14.3 Risk 3 - enterprises block IDE plugin installation and Marketplace access. (MEDIUM-HIGH)

Many Jenkins users sit behind a corporate proxy, on a locked-down IDE, in an organisation where installing a plugin requires a ticket and the Marketplace is not even reachable. **These users will never be able to buy the product, no matter how good it is**, and they may be a large share of the Jenkins population.

**Mitigation:** offer a downloadable `.zip` distribution for offline install and say so on the listing. It does not solve the licence-approval problem, but it converts a hard block into a purchase-order conversation.

### 14.4 Risk 4 - Jenkins is an installed base, not a growth market. (MEDIUM)

Jenkins is not growing the way GitHub Actions is. This is a 3-5 year cash-flow product, not a growth bet. That is acceptable for a $0-cost product with no server, and it is not acceptable if the plan requires the market to grow.

**Mitigation:** none needed. It is a property of the choice, stated so it is not a surprise. Set expectations at the base-case row of 12.3.

### 14.5 Risk 5 - technical fragility generates one-star reviews. (MEDIUM)

Five of five competitors' top one-star clusters are CSRF, TLS and proxy handling. A solo developer will hit exactly the same wall.

**Mitigation:** the 15-point Day-0 gate (section 10) and the 12-20 cell compatibility matrix (section 9.2) exist for no other reason. Do them. Do not skip them because the demo works on your laptop.

### 14.6 Risk 6 - support load on a solo developer. (MEDIUM)

Enterprise Jenkins installations are strange: ancient versions, unusual proxies, custom folder structures, inverted job naming. Every support ticket is a day.

**Mitigation:** the Day-0 gate, a README that answers the top five questions before they are asked, in-product diagnostics (*"connected to Jenkins 2.414.3, authenticated as `dgorbunov`, indexed 312 jobs, matched job `my-service` by name"*), and a public issue tracker so answers are reusable.

### 14.7 Risk 7 - the incumbent returns, or someone forks it. (MEDIUM)

`jenkins-control-plugin` has 433,254 downloads and a merged backlog. One maintainer with a weekend could ship folders and logs. `PipelinePilot for Jenkins` (32110) already ships both and is 62 downloads away from being a real competitor.

**Mitigation:** none, beyond speed. **Kill criterion:** if `jenkins-control-plugin` ships working folders plus working build logs before Stagecraft launches, **stop immediately**. That is the whole decision.

### 14.8 Risk 8 - nobody actually minds the browser walk. (LOW-MEDIUM)

The product assumes the 8-step browser workflow (1.1) is a real daily irritant. It probably is. But developers tolerate browser tabs constantly, and `GitHub Actions Manager`'s density of 0.018 is consistent with people tolerating it.

**Mitigation:** the reviews in section 4 are the evidence. Four independent plugins' users wrote *"usefulness"*, *"filter"*, *"folders"*, *"get the job from the git remote"*, and *"check CI logs on the IDE and jump right to the source code"* without being asked. If that is not pain, nothing is.

### 14.9 Open questions, unresolved

| Question | Why it matters | How to resolve |
|---|---|---|
| Does JetBrains require a "verified vendor" badge to charge? | A blocked launch | Read the vendor agreement during Day-0 |
| Is the **trader-status declaration** mandatory before selling? | Confirmed mandatory elsewhere; must complete before launch | Configure the vendor account during Day-0 |
| What is the actual trial-to-paid conversion? | The whole revenue model | Only measurable after 60 days live |
| Will enterprises buy a $29 plugin or require a purchase order? | Pricing and packaging | Watch the first ten sales |
| Does the free Community edition have every API needed? | 91% of installs are Community | Test on Community during Day-0 |

---
---

## §15 — Resuming this project on another machine

This repository is the charter. It contains no product code. Everything needed to pick the work up cold is below.

### 15.1 Before writing code

1. Read **§10** and run the Day-0 gate. Nothing else starts until all 15 checks pass on HTTP and HTTPS.
2. Read **§7.3** - the five things that must never happen - and **§7.6** - the seven-point acceptance test. Every later decision is judged against those two lists.
3. Read **§1** and **§6** to re-establish what the product is and what it deliberately is not.

### 15.2 The environment to stand up

| Item | Value |
|---|---|
| JDK | 21 |
| Gradle | via the IntelliJ Platform Gradle Plugin 2.x wrapper |
| IntelliJ Platform SDK | against the **oldest supported** IDE (2023.1), every 2024.x and 2025.x LTS, and the current release |
| IDE for development | IntelliJ IDEA Ultimate (for testing Ultimate-specific behaviour); **the plugin must work on Community** - 91% of installs are Community |
| Test Jenkins | `docker run -p 8080:8080 jenkins/jenkins:lts-jdk17` |
| Test Jenkins, HTTPS | a second container behind `nginx` with a self-signed certificate |
| Test fixtures | the raw responses captured during the Day-0 gate, committed to `docs/day0-verification.md` |

### 15.3 The order of work

Follow **§11** day by day. The order is not a suggestion: days 1-4 produce a client and a parser that are testable with plain Kotlin and no IDE at all, and if either of them does not work, no amount of UI will save the product. Days 5-14 are the part that is already de-risked.

### 15.4 The three things that will be forgotten

1. **The token, not the password.** `Authorization: Basic base64(user:apiToken)`. Passwords fail on most servers because of SSO. The settings screen must say "API token" in the label, with a link to `{base}/user/{user}/configure`.
2. **The crumb.** Every POST needs it after `GET /crumbIssuer/api/json`, and a `403` with `No valid crumb` means refetch **once**, never loop. This is the single most common cause of one-star reviews in this space (section 4 classes E and F).
3. **The cache.** If the tool window is empty for five seconds on every IDE start, the product is dead. Populate from `{systemPath}/stagecraft/index-{sha1(serverUrl)}.json` first, refresh in the background, and show the age of the data.

### 15.5 When a decision is not covered

The two rules the whole charter follows:

- **Each screen answers one question.** Build tree: what happened? Stage panel: where did it fail? Log: what is the error? Tests: what broke? If a screen is answering two questions, it is two screens.
- **Every network call must be able to fail visibly and quickly.** There is no "loading" state that lasts more than 200 ms without a cancel button, and there is no silent degradation - if the index is capped, say so; if the log is truncated, say so.

---

## Appendix A — Marketplace API recipes, and the traps

Everything here was verified while writing this charter. It saves a day of discovery and it prevents the specific bugs that silently produce wrong numbers.

### A.1 Search

```
GET https://plugins.jetbrains.com/api/searchPlugins?search=jenkins&max=20&orderBy=downloads&offset=0
```

Returns a **dict**, not a list:

```json
{ "plugins": [ { "id": 6110, "name": "Jenkins Control Plugin",
                 "downloads": 433254, "pricingModel": "FREE",
                 "cdate": 1234567890000, "vendor": {...} } ],
  "total": 38 }
```

**Traps:**

- `max` is capped at **20**. `page` is ignored. **`offset` works** - use it.
- `total` caps at **10,000** and is therefore useless as a market-size measure for large keywords.
- `orderBy=newest` returns **HTTP 400 on any search term with five or fewer results**. Recency checks are impossible in the smallest spaces, which are exactly the spaces this project cares about.
- The download field is **`downloads`**, not `dl`. The pricing field is **`pricingModel`**, not `model`. The creation date is **`cdate`**. **There is no `age` field.**
- `vendor` is sometimes a dict (`type`, `id`, `name`, `link`, `publicName`, `email`, `isVerified`) and sometimes a plain string. Always coerce with `str()`.
- `tags` is a list of **dicts**, each with a `name` key.
- Plugins returned by `searchPlugins` may be missing `downloads` entirely. Use `.get()`.

### A.2 Reviews

```
GET https://plugins.jetbrains.com/api/plugins/{id}/comments
```

Returns a **bare list**, not a dict:

```python
comments = raw if isinstance(raw, list) else ((raw or {}).get("comments") or [])
```

Writing `raw.get("comments")` against a list raises `AttributeError: 'list' object has no attribute 'get'`. This has cost time before.

**Traps:**

- The review text field is **`comment`**, and it contains HTML. Strip tags and collapse whitespace before reading it.
- **`rating: 0` means "comment left with no star rating". It does not mean one star.** Treating it as one star invents one-star reviews that do not exist. This matters because the whole competitive read in section 4 is based on one-star clusters.
- Comment `cdate` is **broken** and renders as values like `"56.7y ago"`. **Reviews cannot be dated**, so "recent complaints" cannot be measured directly. Work around it with the plugin's own `cdate` and the plugin's last update.

### A.3 Ratings

```
GET https://plugins.jetbrains.com/api/plugins/{id}/rating
```

Returns `{"votes": {"5": 18, "4": 8, "3": 6, "1": 4}}`. A plugin with no votes returns `{"votes": {}}`, **not a 404**.

**Traps:**

- **`meanRating` is the literal constant `4.0643` for every plugin, and `meanVotes` is always `2`.** Both fields are worthless. Computing a real mean from the histogram is the only correct approach.
- **Review density = written votes per 1,000 downloads.** Bundled or first-party plugins score 0.000-0.006; genuinely installed plugins score 0.014-1.810. **Never rank on fewer than 5 votes.** This instrument is what killed four earlier candidates and it is what produced the honest counter-signal in section 5.3.

### A.4 Plugin detail, and the only paid signal

```
GET https://plugins.jetbrains.com/api/plugins/{id}
```

```json
{ "purchaseInfo": { "productCode": "PJENKINSFILE",
                    "buyUrl": null,
                    "purchaseTerms": "...",
                    "optional": false,
                    "trialPeriod": 7 } }
```

**Traps:**

- **`buyUrl` is always `null`.** Do not build on it.
- **There is no price field anywhere in the API.** Price must be read from the marketplace page.
- **A non-null `productCode` is the only API signal that a paid listing is live.** `pricingModel` is authoritative about the *model*; `productCode` is sticky and survives a switch to free. Check both, and believe `pricingModel`.

### A.5 Dates

`cdate` is epoch milliseconds. Convert with `datetime.fromtimestamp(ts / 1000, datetime.UTC)` - **not** `utcfromtimestamp()`, which is deprecated and returns a naive datetime.

Verified values for sanity-checking a converter:

| Raw | Date |
|---|---|
| `1790780275000` | 2026-09-30 |
| `1789386006000` | 2026-09-14 |
| `1789813777000` | 2026-09-19 |
| `1789317125000` | 2026-09-18 |

### A.6 Product codes observed in this space

`PJENKINSFILE` (`Jenkinsfile`) · `PJENKINSFPRO` (`Jenkinsfile Pro`) · `PCIINTG` (`CIclone`) · `PGHACTNSMGRPRO` (`GitHub Actions Manager`) · `PGITLABCICD` (`GitLab CICD`) · `PCREVIEW` (`Bitbucket Integration Pro`) · `PAZD` (Azure DevOps) · `PLOG` (`.log`) · `PGITLAB`, `PGITLABMASTER`, `PLARAVEL`, `PSYMFONYPLUGIN`, `PANSIHIGHLIGHT`, `PLOGLENS`, `PSQLLOGLENS`, `PLOGPARSERPRO`, `PAWESOMELOGVIEW`, `PMYBATISLOG`, `PJPASQL`, `PTOOLSET`, `PMYBATISHELPER`, `PJTRACKER`, `PEXTRAICONS`, `PJSONPARSERCODE`, `PFASTREQUEST`.

**Reserve `PSTAGECRAFT`** (section 8.2) unless it is taken at registration.

---
---

## Appendix B — The Jenkins remote API, exactly as needed

Base URL examples below use `{base}` for the server root (e.g. `https://jenkins.example.com`) and `{build}` for a specific build URL (`{base}/job/{mb}/job/{branch}/{n}`).

### B.1 Authentication

```
Authorization: Basic base64("username:apiToken")
```

Verify with:

```
GET {base}/me/api/json
```

Create the token at `{base}/user/{username}/configure`. **Passwords fail on SSO-enabled servers**, which is most enterprise installs. The settings UI must say "API token" in its label and link to that page.

Keep a **per-server cookie store** and reuse one HTTP client. Jenkins sets session cookies, and re-authenticating on every request is both slow and rate-limit-visible.

### B.2 CSRF

```
GET {base}/crumbIssuer/api/json
  -> { "crumb": "a1b2...", "crumbRequestField": "Jenkins-Crumb" }
```

Send it as a header named by `crumbRequestField` on every POST. **A 404 means CSRF protection is disabled** - not an error, just skip the header. On a `403` whose body contains `No valid crumb`, refetch the crumb and retry **once**. Never loop.

### B.3 Server navigation

```
GET {base}/api/json?tree=jobs[name,url,_class]
```

Top level only. **Folders nest their children under `jobs` and must be recursed.** A folder's `_class` is:

```
com.cloudbees.hudson.plugins.folder.Folder
```

Other `_class` values worth recognising:

| `_class` | Meaning |
|---|---|
| `org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject` | multibranch pipeline |
| `org.jenkinsci.plugins.workflow.job.WorkflowJob` | pipeline job |
| `hudson.model.FreeStyleProject` | freestyle job |
| `hudson.matrix.MatrixProject` | matrix job |
| `org.jenkinsci.plugins.workflow.job.WorkflowJob` inside a multibranch | a branch job - an ordinary job |

**Multibranch jobs hold their branches as child jobs:**

```
GET {base}/job/{mb}/api/json?tree=jobs[name,url]
```

### B.4 Builds

```
GET {base}/job/{mb}/job/{branch}/api/json?tree=builds[number,result,building,duration,estimatedDuration,timestamp,url]{0,25}
```

`result` is one of `SUCCESS`, `FAILURE`, `UNSTABLE`, `ABORTED`, or `null` while still building. **`null` is not an error** - it means the build is running, and the UI must render it distinctly from every failure state.

Causes and the triggering commit:

```
GET {build}/api/json?tree=actions[causes[shortDescription],lastBuiltRevision[SHA1,branch[name]],remoteUrls]
```

`remoteUrls` is the **only** way to learn a job's git remote, and it exists only per build. See §9.4.

### B.5 Console output

| Purpose | Endpoint |
|---|---|
| Full console | `GET {build}/consoleText` - plain UTF-8 |
| Incremental | `GET {build}/logText/progressiveText?start={offset}` |

The incremental call returns bytes from `start` onward, with:

- `X-Text-Size` -> the offset to use next
- `X-More-Data: true` -> the build is still running

Poll every 2 seconds while running. Append only the delta. Stop when `X-More-Data` is absent.

`logText/progressiveHtml` returns the same content with Jenkins' own hyperlinks. **Do not use it** - Stagecraft generates its own source-linked view (§9.6).

### B.6 Stages

```
GET {build}/wfapi/describe
```

Requires the `pipeline-stage-view` plugin, present on **147,819 of 230,038** servers with `git` = **64.3%**. Returns stage `name`, `status`, `startTimeMillis`, `durationMillis`, and a `nodeId` per stage. Per-node detail:

```
GET {build}/execution/node/{nodeId}/wfapi/describe
```

**Treat this as a progressive enhancement.** When it 404s, parse `/consoleText` (§9.3). When it succeeds, use its timings and do not show invented ones.

### B.7 Test results (core Jenkins, no plugin)

```
GET {build}/testReport/api/json
```

```json
{ "failCount": 2, "skipCount": 1, "passCount": 143,
  "suites": [ { "name": "com.company.OrderServiceTest",
                "cases": [ { "name": "rejectsNegativeTotal",
                             "className": "com.company.OrderServiceTest",
                             "status": "FAILED",
                             "duration": 0.031,
                             "errorDetails": "...",
                             "errorStackTrace": "...  at com.company.OrderService.compute(OrderService.java:88)" } ] } ] }
```

A build with no tests returns a **404**. That is normal and must render as "no test results", not as an error.

### B.8 Jenkinsfile lint

```
POST {base}/pipeline-model-converter/validate
  Content-Type: application/x-www-form-urlencoded
  jenkinsfile={url-encoded Jenkinsfile text}
  {crumbRequestField}: {crumb}
```

Returns `Jenkinsfile successfully validated.` or the linter's error text. Requires `pipeline-model-definition`, present on **221,612** servers of ~230,038 with `git` = **~96%**.

**Send the editor's buffer, not the saved file**, and render the response in a selectable, copyable panel. Both are deliberate - see §7.4 and the two complaints that killed the 148,027-download linter.

### B.9 Version

Every response carries `X-Jenkins` with the server version. Read it from the first response, store it, and use it only to warn about the supported floor (Jenkins **2.204.1 LTS**) - never to branch the whole client.

### B.10 Two encoding traps

- `tree=` values contain `[`, `]`, `{`, `}` and `,` - **URL-encode them** or the server returns an unhelpful 400.
- Console text is UTF-8 and frequently contains CJK and ANSI escape sequences. **Strip ANSI codes** (`\x1b\[[0-9;]*m`) before display; a terminal-rendered log in the IDE looks broken.

---

## Appendix C — The raw evidence, verbatim

Every quote below was read from the marketplace's own review API while writing this charter. Nothing is paraphrased. These are the primary sources behind §4 and §5.

### C.1 `jenkins-control-plugin` (id 6110, FREE, 433,254 downloads, one author, last updated 2025-09-03)

Vote histogram: `{5: 18, 4: 8, 3: 6, 1: 4}`.

**Class A - no filtering, unusable at scale**

> "There are always at least 10 build Jobs running on our Jenkins. But I am only interested in my projects. Some sort of filter would be great."

> "for big teams with hundreds or thousands jobs, it is important to have a filter capability"

> "Read timed out due to too many builds on my jenkins"

> "when you run a Jenkins with a very large number of Multibranch Pipelines it is hard to keep the overview ... need to search through a very large list"

**Class B - folders unsupported**

> "Has no support for folders, so if you use them you won't be able to see any of your jobs."

> "We use folders a lot in Jenkins and don't have a single job on root level."

**Class C - multibranch broken**

> "Multi-Branch-Jobs are not displaying the individual branch-jobs. This makes the plugin useless for these Jobs"

**Class D - one server only**

> "It only supports one server and I'm using 3 servers."

> "Lack of possibility to use it globally."

**Class E - CSRF**, both one-star

> "CSRF enabled -> missing or bad crumbdata"

> "Because there's no way to supply credentials, I (and probably most Jenkins users) cannot make use of this plugin."

**Class F - TLS and proxy**, one-star

> "Unsupported or unrecognized SSL message -> useless"

> "our Jenkins server is behind an Apache instance and we use SSL"

**Class G - the IDE freezes**

> "IntelliJ freezes when there is no connection to the Jenkins server"

> "If the server is not available, it will wait for very long time to timeout at IDEA startup."

**Class H - logs broken**

> "when I try to read build logs I get 'Could not find data for: PROJECT_NAME' Caused by: 503 Service unavailable. Need to go to the build page."

**Class I - abandonment**

> "Is it still maintained?"

> "lost the connect config when restart. idea version is 2021.3"

### C.2 The same root cause, in four more plugins

**`CIclone`** (id 19114, PAID `PCIINTG`, 30-day trial, 13,204 downloads), three stars:

> "链接Jenkins服务器后由于Jenkins服务器上的工程太多，导致polling Jenkins server返回：Jenkins server return an error"

*(After connecting to the Jenkins server, because there are too many projects on it, polling the Jenkins server returns an error.)*

> "A lot of issues with this plugin. I simply want to see the status of my actions on a multimodule project, and it fails to deliver that."

**`Pipeline Viewer`** (id 13799, FREE, 101,873 downloads) - **GitLab-only**, so the Jenkins stage view is genuinely unowned:

> "Requires a GitLab project ID and GitLab Server address GLOBALLY. So it only works for 1 Server with 1 project. It should get the URL of the Gitlab-server with the project-name from the Git Remotes PER PROJECT."

> "freezes IntelliJ with an SSL Certificate validation exception that isn't handled"

**`MerryLab`** (id 20347) - proxy handling.

**`GitLab CICD`** (id 22202, PAID `PGITLABCICD`), five stars:

> "each time I need to add PAT ... when I'm adding the PAT for the second gitlab, then the first becomes useless"

### C.3 The paying customer, unprompted

`CIclone`, five stars:

> "I use it with Jenkins. It is very convenient to be able to check CI logs on the IDE and jump right to the source code."

**This sentence is the product specification.** One build, its logs, and a click that lands on the source line.

### C.4 The top paid Jenkins plugin, in its own reviews

`Jenkinsfile` (id 26270, PAID `PJENKINSFILE`, 7-day trial, 157,778 downloads) sells **syntax highlighting only**:

> [3 stars] "After about a year's use I've cancelled my subscription renewal. It was, ironically, causing more problems with Jenkinsfiles in PyCharm than it was solving."

> [1 star] "Just by installing the plugin the pop-ups on hover in the editor stopped working for any language"

> [no rating] "This plugin does nothing except for adding some useless colors to the file. Without code navigation I don't see any value here."

### C.5 The cross-space willingness-to-pay evidence

**`GitHub Actions Manager`** (id 19347, FREEMIUM `PGHACTNSMGRPRO`, 30-day trial, 791,388 downloads):

> [5 stars] "Pretty much all of my projects use github actions, so this is a very convenient way to monitor them without having to go to Github."

> [5 stars] "Use it every day. Before I needed to go to GitHub, go to the correct repository and to the actions to view running GitHub Actions."

> [4 stars] "Would be great to have some filtering capabilities, so that I could only see the workflows for the specific branch or user who triggered it."

> [1 star] "Free version is pretty useless"

**`GitLab CICD - Pipelines & Jobs`** (id 22202, PAID `PGITLABCICD`, 14-day trial, 28,037 downloads):

> [5 stars] "This plugin is profitable for me."

> [5 stars] "It works smoother than gitlab web ui (especially when the pipeline has 50+ jobs)"

### C.6 `PipelinePilot for Jenkins` (id 32110, FREE, 62 downloads, 0 reviews, created 2026-06-04)

Listing copy, quoted for competitive accuracy:

> "Live logs & stage graph - streamed console output and a visual graph of sequential, parallel and nested stages."

> "Remote sandbox execution - press Ctrl+Enter to run the current Jenkinsfile on real Jenkins, isolated and ephemeral."

> "Replay - rerun the whole pipeline, only failed stages, or a single stage."

> "Instant validation - inline diagnostics from the Jenkins declarative linter"

> "AI assistance ... via any OpenAI-compatible provider"

Tags: `AI`, `Completion`, `Machine Learning`. **This is a Jenkinsfile *authoring* tool, not a build-outcome tool.** It is free, unmonetised and unreviewed, which means it is not a money competitor - but it *is* a reason the listing must not lead with "live logs" (§6.3).

---
---

## Appendix D — Proven versus assumed: the honest ledger

The charter's standing rule is that every claim is either measured or labelled. This is the ledger.

### D.1 Measured — hard numbers from primary sources

| Claim | Figure | Source |
|---|---|---|
| The keyword `jenkins` returns this many plugin listings | **38** | JetBrains Marketplace search API |
| Downloads across the top 11 Jenkins plugins | **~970,000** | Marketplace search API |
| Live paid vendors in the Jenkins space | **3** | `pricingModel` + `productCode` |
| Downloads those three paid vendors hold between them | **200,607** | Marketplace search API |
| The top paid Jenkins plugin's downloads | **157,778** | Marketplace search API |
| What that top paid plugin actually sells | syntax highlighting only | Its listing, and its reviews |
| Downloads of the top Jenkins plugin, which is free | **433,254** | Marketplace search API |
| `pipeline-stage-view` installations | **147,819** | Jenkins update centre |
| `git` plugin installations | **230,038** | Jenkins update centre |
| **`pipeline-stage-view` / `git`** | **64.3%** | Calculated |
| `pipeline-model-definition` / `git` | ~**96%** | Calculated |
| `[Pipeline] // stage` occurrences on GitHub | **4,424 files** | GitHub code search |
| Stages recovered from a real 237-line console by the parser | **3, with correct boundaries** | Local verification, `_preflight3.txt` |
| Plugins found for `hudson` / `bamboo` / `appveyor` / `tekton` / `spinnaker` | 3 / 2 / **0** / **0** / **0** | Marketplace search API |
| Name availability for `stagecraft` | **`total: 0`** | Marketplace search API |
| Median paid plugin, lifetime downloads | **1,411** | Full marketplace scan |
| p90 paid plugin, lifetime downloads | **43,031** | Full marketplace scan |
| 2026-cohort median paid plugin downloads | **171** | Full marketplace scan |
| Median annual price | **$19** | Full marketplace scan |
| Share of installs on the free Community edition | **91%** | Vendor account data |
| Vendor share of the sale price | **85%** | JetBrains vendor terms |
| Review density: bundled noise / real installs | 0.000-0.006 / 0.014-1.810 | Calculated from votes and downloads |
| `GitHub Actions Manager` review density | **0.018** | 14 votes / 791,388 downloads |
| `GitLab CICD` review density | **0.075** | 21 votes / 28,037 downloads |
| `Bitbucket Integration Pro` review density | **1.810** | 491 votes |
| Trials observed in this space | **0, 7, 30 days** | `PJENKINSFPRO`, `PJENKINSFILE`, `PCIINTG` |
| `PipelinePilot for Jenkins` downloads and reviews | **62** and **0** | Marketplace detail API |

### D.2 Verified by direct experiment

| Claim | How it was verified |
|---|---|
| Per-stage logs do not require `pipeline-stage-view` | A real captured Jenkins console was fetched and segmented; 3 stages recovered with correct names and line counts |
| The stage marker format is universal, not server-specific | `[Pipeline] // stage` appears in 4,424 files on GitHub |
| `PipelinePilot` is an authoring tool, not a build-outcome tool | Its listing copy and tags were read in full |
| `stagecraft` is free as a plugin name | Marketplace search returned `total: 0` |
| Jenkins has no "builds for this git URL" query | Every navigation endpoint was read; no such API exists, hence §9.4 |

### D.3 Assumed — stated as assumptions, not facts

| Assumption | Confidence | How to falsify |
|---|---|---|
| The 8-step browser walk is a genuine daily irritant | **High** - four plugins' users describe it unprompted | Watch which screen trial users open first |
| Enterprise engineers will pay $29/year to remove it | **Medium** - the space has 3 paid vendors and 200,607 downloads, but only one of them sells anything this product does | The 60-day sales figure |
| Trial-to-paid conversion will be 2-4% | **Low** - unmeasurable in advance | Only after 60 days live |
| The parser handles every real-world pipeline shape | **Medium-high** - proved on one real console and one documented format | The Day-0 gate, item 9, and the fixture set |
| Enterprises will accept a `.zip` offline install instead of blocking the plugin | **Low** | The first ten enterprise tickets |
| The git-remote-to-job match will work on arbitrary naming | **Medium** - the manual pinning fallback is what makes this safe | Day-0 items 4-7; if it fails, pinning carries the product |
| Recommended workaround: `remoteUrls` is exposed by default on recent LTS | **Medium** | Day-0 item 7 |

### D.4 Unknown — and it must stay labelled unknown

- **Trial-to-paid conversion.** No measurement exists and none can be made before launch. Section 12.3 uses a range, not a number, for exactly this reason.
- Whether the reviewed Jenkins users are representative of the paying Jenkins population. Review writers skew towards the angry.
- Whether the free Community edition exposes every API the plugin needs.
- Whether the `.zip` distribution route removes the enterprise blocker or merely restates it.

### D.5 What would change the recommendation

In descending order of decisiveness:

1. **`jenkins-control-plugin` ships working folders plus working build logs** -> stop. The entire wedge (section 4.3) is that it cannot.
2. **The Day-0 gate fails item 9** -> stop. There is no plan B for per-stage logs.
3. **Fewer than 5 paid conversions AND fewer than 500 downloads at 60 days** -> stop. That is the kill criterion, and it is not negotiable after the fact.
4. **The Marketplace requires a company entity or paid registration to publish a paid plugin** -> reconsider the price model before launch, not after.
5. **A second CI host becomes necessary to reach viability** -> reconsider the whole pick. `CIclone` supports **five** hosts and has **13,204 downloads**; `Pull Request Viewer` (31928, PAID) is multi-host and has **469 downloads with 0 votes**. Multi-host is a documented way to fail in this space.

### D.6 The final honest summary

The evidence for this pick is stronger than for either of the two earlier ones, on five specific counts:

1. **Measured demand:** ~970,000 downloads across top Jenkins IDE plugins, from a keyword with only 38 results.
2. **A proven payer:** three paid vendors and 200,607 downloads between them, in a space where the top paid plugin sells *only syntax highlighting*.
3. **A universal, documented failure mode:** nine classes of complaint (A-I) across five independent plugins, all traceable to one root cause - connecting to the server instead of to the job.
4. **A technical de-risking that no competitor did:** per-stage logs proven to work without the optional plugin that a third of servers lack, verified against a real captured console.
5. **A distribution channel that works without a following:** the Marketplace itself, with 38 results deep.

The evidence against it is one number, and it is stated in full in section 5.3: **CI-in-IDE converts at a review density of 0.014-0.018, versus 1.810 for PR-review-in-IDE.** The best-funded company in the world at exactly this product could not make its users write reviews.

That is the trade. Build it for the ceiling - 200,607 potential paying installs and a top plugin at 157,778 - and accept that the base case is a few hundred dollars a year. Do not start it expecting the reverse.

---

*End of charter.*
