# The Marketplace listing (§8.2–8.4, §11 Days 13-14)

Everything the listing needs, in one place. The code and metadata parts are in the repository; the
account and payment parts are external and cannot be done from here.

## Title

`Stagecraft • Jenkins Build & Log Viewer`

`Brand • what it does with the words the buyer searches`. `Jenkins` is the only keyword that matters;
`Build & Log` are the two words a stuck Jenkins user types. **Not** in the title: `Pipeline`, `CI/CD`,
`DevOps`, `AI` (§8.2).

## Description

**First line** (what the buyer sees before clicking):

> Your Jenkins build failed. See which stage broke, its log, and click the failing line straight into your editor — without leaving the IDE.

**Second line** (the differentiating work):

> Stagecraft finds your Jenkins server and job from your git remote. It never lists the whole server, so it works on installations with folders, multibranch jobs and thousands of jobs.

**Do not lead with "live logs"** — `PipelinePilot for Jenkins` and `CIclone` both claim it, and the
word now signals a partial feature. Lead with **your branch** and **jump to source** (§8.2).

## Tags

Functional only. **Never `AI` or `Machine Learning`** (§8.4). Suggested: `Jenkins`, `CI`, `Build`,
`Log`, `Pipeline`.

## Product code

`PSTAGECRAFT` (§8.3) — candidate, not yet registered. It is set in
[`build.gradle.kts`](../build.gradle.kts) as `productDescriptor { code = ... }`. It is **permanent
once a paid listing is live**, and it is the only public-API field that reliably proves a paid
listing exists.

## Price and trial

- **$29 / year per user.**
- **30-day trial** (§12.4: the buyer must ask an administrator for an API token, which takes days; a
  7-day trial produces cancelled renewals).

## Screenshots to capture

Build tree (branch builds without typing a job name), failed stage selected with its log scrolled to
the first error, Ctrl-click landing on a source line, and the Jenkinsfile lint response.

## External steps (not doable from the repository)

These require a JetBrains account and payment, so they are listed rather than done:

1. **Vendor account + trader-status registration** on the JetBrains Marketplace.
2. **Register the product code** `PSTAGECRAFT` (permanent once the paid listing is live).
3. **Publish the paid listing** with the 30-day trial and $29/year.
4. **Upload the plugin** (`./gradlew publishPlugin`, needs `PUBLISH_TOKEN` in the environment).
5. **Set the review-check reminder for day 7 after publication.**

## What is deliberately not in v1 (§7.5, §11)

No second CI host. No Jenkinsfile completion or colouring. No Jenkins administration. No AI, and so
no inference bill and no data leaving the customer's network. No free tier. No team licensing. No
enterprise SSO integration beyond a readable error message.
