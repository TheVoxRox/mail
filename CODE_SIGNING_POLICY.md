# Code signing policy

> **Not in effect yet.** VoxRox Mail releases currently ship without an
> Authenticode signature — accepted residual risk AR-4 in
> [SECURITY_THREAT_MODEL.md](SECURITY_THREAT_MODEL.md). This page is the policy
> the project will follow once [SignPath Foundation](https://signpath.org/)
> accepts it for free code signing, and it is published ahead of the
> application because the application asks for it. Until this notice is
> removed, nothing below describes a binary that has shipped.

Free code signing provided by [SignPath.io](https://about.signpath.io/),
certificate by [SignPath Foundation](https://signpath.org/).

## What gets signed

Only files built from this repository by the
[Windows Signed Release](.github/workflows/windows-signed-release.yml) workflow:

- the installer, `voxrox-mail-<version>-windows-x64-setup.exe`;
- the desktop shell, `voxrox-mail.exe`;
- the backend launcher, `voxrox-mail-backend.exe`.

The workflow runs on a GitHub-hosted runner, builds from a version tag, refuses
to run when that tag does not name the commit it builds, and records build
provenance for the installer. [RELEASE_PROCESS.md](docs/RELEASE_PROCESS.md)
describes the whole release. Nothing built anywhere else is signed: not a local
build, not a build of a fork, not a build of a pull request. Third-party
binaries that ship unchanged inside the installer, such as the bundled Java
runtime, are not signed by this project.

Every signed file names itself `VoxRox Mail` in its version resource and
carries the version number of the release it belongs to.

## Team roles

The project has one maintainer, who holds every role:

| Role                     | Members                                        |
| ------------------------ | ---------------------------------------------- |
| Committers and reviewers | [@Luke-Lacina](https://github.com/Luke-Lacina) |
| Approvers                | [@Luke-Lacina](https://github.com/Luke-Lacina) |

Changes reach `main` through pull requests. A change from anyone else,
dependency updates opened by Dependabot included, is reviewed by the maintainer
before it merges, and every signing request is approved by hand. Every team
member is required to use multi-factor authentication for GitHub and for
SignPath.

## Privacy

The application sends information to other networked systems only in the cases
the [privacy policy](PRIVACY.en.md) lists: the mail servers and sign-in
providers the user adds, remote images in a message the user chooses to load,
and a check for updates on GitHub at startup. The installer shows this summary
with a link to the privacy policy before it installs anything, and asks whether
the check may run; the answer can be changed later in Settings → About. The
application collects no telemetry, analytics or crash reports.

## Reporting a problem

A signed file this policy does not cover, or one that behaves differently from
what this page describes, is a security issue: report it as
[SECURITY.md](SECURITY.md) describes.
