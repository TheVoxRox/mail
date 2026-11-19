# Verifying the installer

<!-- The same procedure in Czech is the "Ověření před spuštěním" section of
frontend/END_USER_README.md. A change to one is a change to both. -->

The Windows installer of VoxRox Mail is not signed with an Authenticode
certificate yet; how releases will be signed is in the
[Code signing policy](../CODE_SIGNING_POLICY.md). This page says what Windows
shows because of that, and how to check, before you run the installer, that
it is the file this project built and published.

The same steps in Czech are in the
[user guide](../frontend/END_USER_README.md#ověření-před-spuštěním).

## Where to download

Only from the [Releases page](https://github.com/TheVoxRox/mail/releases) of
this repository. The installer is
`voxrox-mail-<version>-windows-x64-setup.exe`, and a fresh install needs no
administrator rights. A copy from anywhere else is what the checks below exist
to catch.

## The SmartScreen warning

Because the installer has no signature, Windows SmartScreen shows "Windows
protected your PC" and names the publisher as unknown. To install, choose
**More info**, then **Run anyway** — after you have verified the file.

## Before you run it

The first two checks use the [GitHub CLI](https://cli.github.com/), `gh`,
signed in to any GitHub account (`gh auth login`): until it is signed in,
neither command runs. Run them in PowerShell in the folder you downloaded the
installer to, with `<version>` replaced by the version you downloaded, for
example `0.2.0`.

### 1. Build provenance: who built it

This is the strongest check. It confirms that the installer was built by this
repository's official release workflow. The proof is cryptographic, through
Sigstore, so replacing the file in a release does not get past it:

```powershell
gh attestation verify "voxrox-mail-<version>-windows-x64-setup.exe" --repo TheVoxRox/mail
```

### 2. Release asset: the file that was published

Releases published after 0.2.0-beta.1 are also immutable: once a release is
out, GitHub lets no one replace a file in it. This confirms that the installer
you downloaded is exactly the one in the release:

```powershell
gh release verify-asset v<version> "voxrox-mail-<version>-windows-x64-setup.exe" --repo TheVoxRox/mail
```

### 3. Checksum: a quick integrity check

The `voxrox-mail-<version>-windows-x64-setup.exe.sha256` file in the release
only tells you that the download is not damaged. On its own it does not protect
against a deliberate swap, because it sits in the same release as the
installer:

```powershell
certutil -hashfile "voxrox-mail-<version>-windows-x64-setup.exe" SHA256
```

The printed hash must match the value in the `.sha256` file.

## If a check fails

Do not run the installer. Download it again from the Releases page and repeat
the check. If it still fails, report it as [SECURITY.md](../SECURITY.md)
describes.

## Updates

An update installed from inside the app needs none of this: the app verifies
the signature of every update itself before it installs it.
