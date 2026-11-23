# GitOps Studio

[![Latest release](https://img.shields.io/github/v/release/Purgator/gitops-studio)](https://github.com/Purgator/gitops-studio/releases/latest)
[![Build](https://github.com/Purgator/gitops-studio/actions/workflows/build.yml/badge.svg)](https://github.com/Purgator/gitops-studio/actions/workflows/build.yml)
[![License: GPL-3.0-or-later](https://img.shields.io/badge/license-GPL--3.0--or--later-blue)](LICENSE)

Drive your cloud repositories without leaving the IDE. GitOps Studio is a plugin for Rider and the other JetBrains IDEs that brings **pull requests, pipelines and work items** of GitHub, Azure DevOps, GitLab, Bitbucket and Gitea / Forgejo into one tool window, with the same workflow whatever the host.

## Features

- 🚀 **Create a pull request in seconds** — press `Ctrl+Alt+Shift+P`: the title and description are already filled from your commits (or from the repository's pull request template), the target is the default branch, and the branch is pushed for you. Add reviewers, labels, linked work items, mark it as a draft or set auto-complete, then press *Create*.
- 👀 **Review and complete** — every pull request shows its reviewers and votes, checks and branch policies, conversation and changed files (opened in the IDE's own diff). Approve, request changes, comment, reply, edit, publish a draft, merge with the strategy you want, abandon or reopen.
- ⚙️ **Pipelines** — recent runs of the current branch or of all branches, their jobs, and the log of any job opened in an editor tab. Start a pipeline on a branch, rerun or cancel a run.
- 📋 **Work items and issues** — search, filter, create, change state, comment, and *start working*: one click creates a branch named after the item.
- 📍 **Always in sight** — the status bar shows the pull request of the current branch (or offers to create one); *Open on …* and *Copy link* work on any file and line selection.
- 🧩 **Modular** — every host is a module with its own settings page and an on/off switch. A module that is off is never loaded.
- 🧭 **Guided setup** — the wizard detects the host of your project, pre-fills the server and signs in with the vendor CLI (`gh`, `az`, `glab`), the credentials git already uses, or a personal access token. Each sign-in is verified before it is saved.
- 🔎 **Inline blame** — the line under the caret shows who last changed it, when and in which commit; one action opens that commit in the Git log. The status bar shows the commits you have to push and to pull.
- 🪄 **Git helpers** — gitmojis in the commit message, from a picker or suggested as you type `feature`, `fix`, `docs`…, a clean-up of merged and orphaned local branches, a one-click sync that fast-forwards the default branch without leaving yours, and an optional automatic fetch.
- 🪶 **Lightweight** — under 1 MB, no bundled libraries, no background polling when the tool window is hidden, no telemetry.

### What each module supports

| | GitHub | Azure DevOps | GitLab | Bitbucket Cloud | Gitea / Forgejo |
|---|:-:|:-:|:-:|:-:|:-:|
| Create, edit, merge, close pull requests | ✅ | ✅ | ✅ | ✅ | ✅ |
| Draft pull requests | ✅ | ✅ | ✅ | ✅ | ✅ |
| Reviewers and approvals | ✅ | ✅ | ✅ | ✅ | ✅ |
| Request changes / reject | ✅ | ✅ | — | ✅ | ✅ |
| Labels | ✅ | ✅ | ✅ | — | ✅ |
| Link issues / work items | ✅ | ✅ | ✅ | — | ✅ |
| Auto-complete / auto-merge | ✅ | ✅ | ✅ | — | ✅ |
| Checks and policies | ✅ | ✅ | ✅ | ✅ | ✅ |
| Conversation and replies | ✅ | ✅ | ✅ | ✅ | ✅ |
| Pipelines: list runs and jobs | ✅ | ✅ | ✅ | ✅ | ✅ |
| Pipelines: job logs | ✅ | ✅ | ✅ | ✅ | ✅ |
| Pipelines: run / rerun / cancel | ✅ / ✅ / ✅ | ✅ / ✅ / ✅ | ✅ / ✅ / ✅ | ✅ / — / ✅ | ✅ / — / — |
| Issues / work items | ✅ | ✅ | ✅ | ✅ | ✅ |
| Clone from the account | ✅ | ✅ | ✅ | ✅ | ✅ |
| Self-hosted servers | Enterprise Server | Server 2022+ | Self-managed | — | ✅ |

> **Status.** This is a first release. The GitHub module was checked against the live API; the other modules are covered by tests on the payloads their APIs document, and Azure DevOps is the author's daily driver. If something misbehaves on your server, please [open an issue](https://github.com/Purgator/gitops-studio/issues).

## Install

You need a JetBrains IDE **2026.3 or newer** (Rider, IntelliJ IDEA, WebStorm, PyCharm, …) with the bundled Git plugin enabled.

1. Download `gitops-studio-x.y.z.zip` from the [latest release](https://github.com/Purgator/gitops-studio/releases/latest). Do not unzip it.
2. In the IDE, open **Settings → Plugins**, click the gear icon ⚙ at the top and choose **Install Plugin from Disk…**
3. Select the zip you downloaded, then click **Restart IDE**.
4. Open a project. A notification offers to **set up GitOps Studio**; you can also start the wizard at any time from **Tools → GitOps Studio → Setup Wizard…**

The wizard asks which modules you want (the one matching your project is already ticked) and how to sign in:

| Sign-in | When to pick it |
|---|---|
| **Command line login** (`gh`, `az`, `glab`) | You already use the vendor's CLI. Nothing to paste; the token is refreshed for you. |
| **Git credential manager** | You already push and pull over HTTPS. The plugin reuses what git has stored. |
| **Personal access token** | Works everywhere. *Create a token…* opens the right page; the form says which scopes to tick. |

Tokens you paste are stored in the IDE password safe, never in a settings file.

To update, install the new zip the same way. To remove the plugin, open **Settings → Plugins**, find *GitOps Studio* and choose *Uninstall*.

## Using it

| I want to… | Do this |
|---|---|
| Create a pull request | `Ctrl+Alt+Shift+P`, or **Git → Create Pull Request…**, or the **+** button of the tool window |
| See the pull request of my branch | Click `PR #…` in the status bar |
| Review, vote, merge | **GitOps Studio** tool window → *Pull Requests* → select one |
| See why a build failed | Tool window → *Pipelines* → select a run → double-click a job to open its log |
| Start working on a work item | Tool window → *Work Items* → *Start Working: Create Branch…* |
| Share a link to some code | Right-click in the editor → *Copy … Link* |
| Add a gitmoji to a commit | Start the message with a word such as `feature`, `fix` or `sparkles` and pick the suggestion, or use the smiley button of the commit message toolbar |
| See who last changed a line | Put the caret on it; right-click → *Show Commit of Current Line* for the full commit. **Git → Inline Blame** turns it on or off |
| See what I have to push or pull | `↑2 ↓1` in the status bar; click it to fetch |
| Delete stale local branches | **Git → Clean Up Branches…** |
| Turn a module on or off | **Settings → Tools → GitOps Studio → (module)** → the switch at the top |

When a project has several cloud repositories, a selector appears in the tool window's title bar.

---

## For developers

### Build, run, test

Requirements: JDK 25 (the 2026.3 platform runs on it). Everything else is fetched by the Gradle wrapper.

```bash
./gradlew buildPlugin      # → build/distributions/gitops-studio-<version>.zip
./gradlew runIde           # starts a sandbox IDE with the plugin
./gradlew test             # unit tests + headless IDE tests
./gradlew verifyPluginStructure
```

By default the build downloads IntelliJ IDEA `platformVersion` (see `gradle.properties`). To compile and run against an IDE that is already installed, which avoids a download of more than a gigabyte, create an untracked `local.properties` next to `build.gradle.kts`:

```properties
ide.path=C:/Users/you/AppData/Local/Programs/Rider
```

### Architecture

```
io.github.purgator.gitops
├── core
│   ├── model        Provider-neutral data: PullRequest, PipelineRun, Issue, RepoRef, …
│   ├── provider     The contract every host implements: HostingProvider + HostingClient
│   ├── http         ApiClient (JSON over HTTP), HttpTransport seam, null-safe JSON helpers
│   ├── accounts     Accounts (no secrets), CredentialService (password safe, CLI, git credential)
│   ├── modules      The `module` extension point and ModuleRegistry (which modules are on)
│   ├── settings     Application settings, per-provider preferences
│   └── RepoContext  git remotes of the project → RepoTarget (repository + provider + account)
├── providers        github, azure, gitlab, bitbucket, gitea: one provider + one client each
├── git              The local Git module: inline blame, ahead / behind, gitmoji, branch clean-up, sync
└── ui               Tool window, pull request flow, pipelines, issues, settings, setup wizard
```

A few decisions worth knowing before changing things:

- **One contract, many hosts.** The UI only knows `HostingProvider` (static facts: how to recognise a remote, how to authenticate, which `Capability` set it has, the words it uses) and `HostingClient` (blocking remote calls returning the neutral model). Anything a host cannot do is simply absent from its capability set, and the UI hides the matching control. There is no `if (github)` in the UI.
- **Modules are lazy.** A module is declared in `plugin.xml` through the `io.github.purgator.gitops.module` extension point. Its name, description and icon are attributes of that declaration, so settings and the wizard can list a module without loading its class. `ModuleRegistry.providers()` only instantiates the modules that are switched on.
- **One seam to the network.** Every request goes through `HttpTransport`. Production uses `JdkHttpTransport` (JDK HTTP client wired to the IDE's proxy and certificate settings; it follows redirects itself and never forwards credentials to another host). Tests use `FakeTransport`.
- **Credentials are resolved on demand.** `CredentialService` returns a `SecretSource` per account. Pasted tokens live in the password safe; CLI and git-credential tokens are asked to the tool, cached in memory for 20 minutes and refreshed once on a `401`.
- **Nothing remote runs on the UI thread.** `runAsync` and `runWithProgress` (in `core/util/Async.kt`) run the work on a pooled thread and deliver the result on the UI thread with the modality of the calling component, so the same code works in the tool window and inside dialogs.
- **Views talk through a topic.** After a mutation, `GitOps.refresh(project)` publishes on `GitOpsListener.TOPIC`; lists, details and the status bar widget reload themselves.

### Adding a host

1. Create `providers/<name>/<Name>Provider.kt` with a `GitOpsModule` class exposing an `object : HostingProvider`, and a `<Name>Client : HostingClient`. Use `ApiClient` and the helpers of `core/http/Json.kt`; the Azure DevOps module is the reference.
2. Declare only the capabilities you implement.
3. Register it in `plugin.xml`: a `<module …/>` line, an icon in `resources/icons/<id>.svg`, and a settings page (a one-line subclass of `ProviderConfigurable`).
4. Add the host to `ModuleGuess` so the wizard can pre-select it.
5. Test it with `FakeTransport` (see `src/test/.../providers`) and add its remote URL to `UiSmokeTest`, which then builds every dialog for it.

### Tests

- `providers/*Test` — remote URL parsing, request shapes and JSON mapping of each host, on a scripted transport.
- `CoreTest` — URL parsing, the API client (errors, credential refresh), small helpers.
- `GitFeaturesTest` — gitmoji suggestions, the blame parser (also against the installed git) and the ahead / behind counts.
- `PluginWiringTest` — boots the IDE headless with the plugin and checks that every module, action, settings page and extension declared in `plugin.xml` loads.
- `UiSmokeTest` — builds every dialog and panel for every provider; layout mistakes only fail at creation time.
- `LiveGitHubTest` — opt-in, read-only round trip against the real GitHub API using the `gh` login: `GITOPS_LIVE=owner/repo ./gradlew test --tests "*LiveGitHubTest*"`.

### Releasing

Bump `pluginVersion` in `gradle.properties`, add the notes to `CHANGELOG.md`, tag `vX.Y.Z` and push the tag. The *Release* workflow builds the plugin and attaches the zip to a draft GitHub release.

## Privacy

GitOps Studio talks only to the servers of the accounts you connect, with the credentials you chose. It collects nothing and sends nothing anywhere else.

## License

[GNU General Public License v3.0 or later](LICENSE) (`GPL-3.0-or-later`)
