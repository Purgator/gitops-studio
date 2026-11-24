# Changelog

## Unreleased

### Pull requests

- **Creation moved into the tool window.** The form is no longer a dialog: start a pull request, hide it, look at something else and come back to it as you left it with the **+** button.
- **Insert Commits** adds the subject of every commit of the branch to the description.
- **Generate** writes the description with a command of your choice (`claude -p`, `ollama run …`, anything that reads a prompt on its standard input). The prompt is a setting, and the result can be appended, replace the description, or rewrite the current one (useful with a template). JetBrains AI Assistant cannot be called by other plugins, hence the command.
- **Merge commit message**: the merge dialog shows the message and lets you edit it; a default template (`🔀 {title} (#{id})`, …) can be set in the settings and also applies to auto-complete.
- After a **push**, a notification offers to create the pull request when the branch has none.

### Pipelines

- The right-hand list shows the **stages** of the selected run with their jobs beneath, and the list of runs shows one mark per stage (Azure DevOps).
- **Rerun Stage** and **Rerun Failed Jobs of Stage** (Azure DevOps).
- New filter **Main branch**, next to *Current branch* and *All branches*.

### Interface

- The entries of the editor, project view and tab context menus are grouped in one **GitOps Studio** submenu.
- Fixed *Open on Azure DevOps* and *Copy Azure DevOps Link* for repositories cloned from the old `org.visualstudio.com` address: the links lacked the organisation.

### Git

- **Inline blame**: the line under the caret shows who last changed it, when and in which commit. *Show Commit of Current Line* opens that commit in the Git log; **Git → Inline Blame** turns the annotation on or off. The text is a format of your own: `{author}`, `{subject}`, `{hash}`, `{ago}`, `{date}` or `{date:dd/MM/yyyy HH:mm}`.
- The **status bar** shows the commits the current branch has to push and to pull (`↑2 ↓1`); click it to fetch.
- **Automatic fetch** at an interval of your choice. Off by default.
- **Gitmoji suggestions**: type `feature`, `fix`, `docs`, `refactor`… or the name of a gitmoji (`sparkles`) as the first word of a commit message and pick the gitmoji from the completion list. The picker also finds gitmojis by these words.
- Picking a gitmoji now leaves the caret right after it, ready to type the message.
- *Sync with Remote* no longer prunes remote branches unless you turn the option on.

## 0.1.0

First release.

### One workflow for every host

GitHub, Azure DevOps, GitLab, Bitbucket Cloud and Gitea / Forgejo, each as a module you can switch on or off. Modules that are off are never loaded.

### Pull requests

- **Create in seconds** (`Ctrl+Alt+Shift+P`): title and description filled from your commits or the repository template, default target branch, automatic push, reviewers, labels, linked work items, draft, auto-complete.
- **Review and complete**: reviewers and votes, checks and branch policies, conversation with replies, changed files in the IDE diff, approve / request changes, edit, publish a draft, merge with the strategy of your choice, abandon, reopen, check out the branch.
- The **status bar** shows the pull request of the current branch, or offers to create one.

### Pipelines

- Recent runs of the current branch or of every branch, their jobs, and job logs opened in an editor tab.
- Run a pipeline on a branch, rerun or cancel a run.

### Work items and issues

- Search, filter, create, change state, comment.
- *Start Working* creates a branch named after the item, from a template you choose.

### Git helpers

- Gitmoji picker in the commit message toolbar.
- Clean-up of local branches that are merged or whose remote branch is gone.
- One-click sync: fetch, prune and fast-forward the default branch without leaving yours.

### Setup and settings

- Guided setup that detects the host of your project and signs in with the vendor CLI (`gh`, `az`, `glab`), the git credential manager or a personal access token, verified before it is saved.
- One settings page per module, with its on/off switch, its accounts and its defaults.
- *Clone from Cloud*, *Open on …* and *Copy link* for any file and line selection.
