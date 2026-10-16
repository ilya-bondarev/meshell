# MeShell

<p align="center"><img src="docs/fox.gif" width="720" alt="The MeShell fox: it follows your finger, hops when tapped and blinks while planets orbit around it."></p>

Android terminal and ChatGPT agent workspace. Native Java UI, SQLite chat history, an authenticated ADB-shell broker and a small set of built-in modules. No Gradle or third-party runtime libraries are bundled.

- Android 10+ (API 29), ARM64.
- English and Russian: **Settings → Language**.
- The Codex CLI, git, curl and ssh install themselves on first start; see [Packages](#packages).
- Chat uses your own ChatGPT/Codex sign-in. **Settings → Update Codex** fetches the latest `@openai/codex` release number so the model catalogue matches current Codex clients; it never goes below the built-in version.
- Shell commands run as Android's ADB-shell user (uid 2000), not root. The app alone cannot start that privileged process.

## Install

Download [`MeShell.apk`](https://github.com/ilya-bondarev/meshell/releases/latest/download/MeShell.apk) from the latest [release](https://github.com/ilya-bondarev/meshell/releases), check it against the release's `SHA256SUMS`, and install it. Release APKs share one signing key (its certificate digest is in the release notes), so later releases install as updates. To build your own, see [Build](#build); an APK signed with another key must replace this one rather than update it.

## Start the terminal

After installing the APK, extract its broker and startup script on a computer with `adb`:

```sh
unzip -p MeShell.apk assets/meshelld > meshelld
unzip -p MeShell.apk assets/start.sh > start.sh
unzip -p MeShell.apk assets/mkshrc > mkshrc
adb shell mkdir -p /data/local/tmp/meshell/home
adb push meshelld start.sh /data/local/tmp/meshell/
# Fresh installations only; preserve an existing customized .mkshrc.
adb push mkshrc /data/local/tmp/meshell/home/.mkshrc
adb shell chmod 700 /data/local/tmp/meshell/meshelld
adb shell am start -W -n com.mesystem.shell/.MainActivity
adb shell sh /data/local/tmp/meshell/start.sh
```

Rebooting the phone requires restarting the broker through ADB (wireless debugging works). Command-line tools are not embedded in the APK; see [Packages](#packages).

## Packages

The first time the terminal connects on Wi-Fi, MeShell installs the **Codex CLI**, git, curl, OpenSSH and zip/unzip (about 90 MB download, 370 MB on disk). **Settings → Packages** installs or updates them at any time and optionally adds the build tools: clang, lld, make, Java 21 and aapt2 (about 200 MB more). The agent can do the same with `packages_install`, including other Termux packages by name, for example `python`.

Packages come from the official Termux repository (`packages.termux.dev`); the Termux app is not needed. MeShell resolves dependencies, checks every download against the repository index's SHA-256, rewrites the Termux prefix to `/data/local/tmp/meshell/toolusr`, and extracts the packages through the shell broker. They are on `PATH` in the terminal and in agent commands.

Use Codex in the terminal:

```sh
codex login --device-auth
codex
```

If ChatGPT rejects device sign-in, enable device code authorization for Codex in your ChatGPT security settings.

## Modules

| Module | What it does |
| --- | --- |
| Notes | Local text notes stored on the phone; send a note to chat. |
| Device | CPU, memory, battery, network traffic and sensor monitor. |
| Mascot | A floating fox over other apps showing the weekly ChatGPT allowance; drag to move, tap to refresh. Asks for the display-over-apps permission only when you turn it on. |
| GitHub | Your repositories, issues, pull requests and Actions runs through the official `gh` CLI, which installs on first use. Uses a fine-grained token kept in the vault. |

Custom modules are JSON definitions of shell actions. Get them from **Modules → Catalogue**, create them in **Modules → +**, import a JSON file, or ask the agent to write one.

### Catalogue

**Modules → Catalogue** lists community modules from [`meshell-tools`](https://github.com/ilya-bondarev/meshell-tools) with their author's GitHub account, permissions, size, downloads and a 1–5 rating with reviews. Search, filter by category and sort by popularity, rating or date. Installing shows the permissions first; the download is GitHub's counted release asset and must match the SHA-256 in the catalogue index. Updates appear on the module tile and install only when you confirm them.

Every module declares the permissions its commands need: `network`, `storage` (shared files), `screen` (screenshots and recording), `system` (settings, apps, input), `secrets` (vault values) and `packages` (Termux packages installed before the first run). MeShell detects these from the definition and rejects undeclared ones; a module runs only after you approve them. Declarations make modules readable, they do not sandbox them.

Rate an installed module from its catalogue page. With the GitHub module connected the rating is filed from your account; otherwise GitHub opens with it filled in. One rating per account and module counts.

The agent's tools cover the shell, files, the device UI and screenshots, USB devices, the encrypted vault, modules and the catalogue, and rebuilding/installing the app from its own sources on the phone.

## Build

Use Linux x86_64 or WSL2, JDK 17+, Clang for host tests, and the Android SDK command-line tools. Install the pinned SDK packages:

```sh
export ANDROID_HOME=/path/to/android-sdk
sdkmanager --package_file=tools/sdk-packages.txt
sh build.sh --unsigned
```

The unsigned, aligned APK is `build/aligned.apk`; it cannot be installed until signed. The build runs repository, broker and file-write gates, compiles Android integration tests, and executes plain-Java domain tests. Device integration tests are a separate step:

```sh
# An Android 14 emulator or an Android 14/15 phone connected to adb; no app installation required.
sh tests/run_device.sh
```

The integration harness uses Android internal APIs. It passes on an Android 14 (API 34) emulator and an Android 15 phone; the stock Android 15 emulator images fail to load their system font map under `app_process`. This is not a claim of test-harness compatibility with every supported app version.

### Signing

Use a private keystore outside the repository. A new development key can be created with `keytool -genkeypair -storetype PKCS12 -keystore /private/path/meshell.p12 -alias meshell -keyalg RSA -keysize 3072 -validity 3650`; keytool prompts for the password.

```bash
read -rsp 'Keystore password: ' MESHELL_KEY_PASSWORD; echo
export MESHELL_KEY_PASSWORD
sh build.sh /private/path/meshell.p12
unset MESHELL_KEY_PASSWORD
```

Set `MESHELL_KEY_ALIAS` if your alias differs. The result is `build/MeShell.apk`. Keep the same signing key for updates.

### On-phone build

The supported layout is `/data/local/tmp/meshell/toolusr` (ARM64 tools) and `/data/local/tmp/meshell/home/sdk` (`android.jar`, `d8.jar`, `apksigner.jar`). **Settings → Packages** with build tools fills `toolusr`; the SDK jars in `home/sdk` are copied separately. `device/setup_toolchain.sh` checks the installation. On-phone outputs go to `$HOME/build` and the build executes the Android integration suite before signing. Override the output directory with `MESHELL_BUILD_DIR`.

The agent's `build_self` tool expects the sources in `/data/local/tmp/meshell/home/source` and a vault entry `meshell-signing` (kind `signing`, JSON with `keystore` as base64, `password` and optional `alias`).

## Share modules

Long-press your module → **Publish to catalogue**, choose a category, version and description, and confirm that you wrote it under MIT-0. MeShell files a submission on GitHub from your account (or opens a prefilled one); a maintainer reviews the commands and permissions, and once approved the module appears in the catalogue under your GitHub name. **Modules → + → Publish to catalogue** shows the rules.

Long-press a custom module → **Export JSON** → inspect the definition → **Save file** or **Copy text**. Import from **Modules → + → Import JSON file** or paste JSON; the permissions are shown and approved before installation or replacement. Installation does not run its commands.

Exports remove local working directories and personal vault alias names and declare the detected permissions. Recipients create the referenced vault aliases locally; never put credential values in JSON commands.

## Repository

See [architecture](docs/ARCHITECTURE.md), [security](SECURITY.md), [contributing](CONTRIBUTING.md) and [changes](CHANGELOG.md).

GitHub's **Verify** workflow scans history for secrets, builds the APK and runs the integration tests in an Android 14 (API 34) emulator.

## License

[MIT-0](LICENSE) (MIT No Attribution): use, modify, redistribute and sell without an attribution requirement. External tools and services keep their own terms; see [third-party notes](THIRD_PARTY.md).
