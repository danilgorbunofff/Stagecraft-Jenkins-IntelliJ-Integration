# The compatibility matrix (§9.2, §11 Days 13-14)

The 25 cells Stagecraft must be proven on before shipping. Each cell is one run of the headless
probe, [`tools/compat-matrix.sh`](../tools/compat-matrix.sh) → `./gradlew compatProbe`
([`CompatProbe.kt`](../src/main/kotlin/dev/stagecraft/cli/CompatProbe.kt)), which exercises the exact
API surface the plugin uses and prints `PASS` / `FAIL` / `SKIP` per check.

| Jenkins | CSRF off | CSRF on | HTTPS | Behind proxy | Token | Password |
|---|---|---|---|---|---|---|
| 2.204.x LTS | ☐ | ☐ | ☐ | ☐ | ☐ | n/a |
| 2.319.x LTS | ☐ | ☐ | ☐ | ☐ | ☐ | n/a |
| 2.414.x LTS | ☐ | ☐ | ☐ | ☐ | ☐ | n/a |
| 2.479.x LTS | ☐ | ☐ | ☐ | ☐ | ☐ | n/a |
| newest LTS | ☐ | ☐ | ☐ | ☐ | ☐ | n/a |

`☑` = run and passing, `☒` = run and failing, `☐` = not yet run.

## What the probe checks

1. **identity** — `GET /me/api/json` returns the configured user (§9.2).
2. **version** — `X-Jenkins` is read; a below-floor server warns, never refuses (§9.8).
3. **job-tree** — one bounded `tree=` request (§9.4).
4. **lint** — `POST /pipeline-model-converter/validate` with the crumb matrix (§7.4, §9.2).
5. **builds / console** — the branch's builds and a bounded console read (§9.7).
6. **stage-view / test-report** — present → `PASS`, 404 → `SKIP` (optional endpoints, §9.3/§9.6).

A `SKIP` is not a failure: `pipeline-stage-view` and a published test report are optional, and the
whole design is built so their absence degrades rather than breaks.

## Containers already available (from Day 0)

| Version | URL | Container |
|---|---|---|
| 2.541.3 | `http://localhost:18080` | `stagecraft-jenkins` |
| 2.479.3 | `http://localhost:38080` | `stagecraft-jenkins-2479` |

## The 2.204.x row — decision

The charter asks whether the 2.204.x row is worth its cost (a Java 8 image plus plugin versions
contemporary with 2019) or whether the floor moves up. **Decision: keep the Jenkins floor at
2.204.1 LTS and warn-and-proceed (§9.8), but treat the 2.204.x *row* as the lowest priority.** The
plugin's IDE floor is now 2024.2 (`since-build 242`, §9.1), so the audience for a 2019 Jenkins server
reached from a 2024+ IDE is small; the code paths it would exercise (crumb behaviour, `/me`) are
already covered by the 2.479.x and newest rows. Revisit if a customer asks.

## Running it

```sh
# One cell:
STAGECRAFT_USER=admin STAGECRAFT_TOKEN=... \
  ./gradlew compatProbe --args="--base=http://localhost:18080 --build=http://localhost:18080/job/multibranch-demo/job/main/1/"

# A file of cells (see tools/cells.example.txt):
STAGECRAFT_USER=admin STAGECRAFT_TOKEN=... ./tools/compat-matrix.sh tools/cells.example.txt
```

Credentials are read from the environment so a token never lands in a shell history or the repository.

## Status in this repository

The probe, the harness and the cell template are committed and compile. **The cells have not been run
in this environment** (no Docker daemon here); the two known containers and the exact commands are
above, so a run is a few minutes of work on a machine with Docker.
