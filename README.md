# MeShell

<p align="center"><img src="docs/fox.gif" width="720" alt="The MeShell fox: it follows your finger, hops when tapped and blinks while planets orbit around it."></p>

Android terminal and ChatGPT agent workspace. Native Java UI, SQLite chat history, an authenticated ADB-shell broker and a small set of built-in modules. No Gradle or third-party runtime libraries are bundled.

- Android 10+ (API 29), ARM64.
- English and Russian: **Settings → Language**.
- Chat uses your own ChatGPT/Codex sign-in. **Settings → Update Codex** fetches the latest `@openai/codex` release number so the model catalogue matches current Codex clients; it never goes below the built-in version.
- Shell commands run as Android's ADB-shell user (uid 2000), not root. The app alone cannot start that privileged process.

## Modules

| Module | What it does |
| --- | --- |
| Notes | Local text notes stored on the phone; send a note to chat. |
| Device | CPU, memory, battery, network traffic and sensor monitor. |

Custom modules are JSON definitions of shell actions. Create them in **Modules → +**, import a JSON file, or ask the agent to write one. The [`meshell-tools`](https://github.com/ilya-bondarev/meshell-tools) catalogue collects shareable definitions.

The agent's tools cover the shell, files, the device UI and screenshots, USB devices, the encrypted vault, modules, and rebuilding/installing the app from its own sources on the phone.

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

The supported layout is `/data/local/tmp/meshell/toolusr` (ARM64 tools) and `/data/local/tmp/meshell/home/sdk` (`android.jar`, `d8.jar`, `apksigner.jar`). `device/setup_toolchain.sh` checks an existing installation; it does **not** download tools. On-phone outputs go to `$HOME/build` and the build executes the Android integration suite before signing. Override the output directory with `MESHELL_BUILD_DIR`.

The agent's `build_self` tool expects the sources in `/data/local/tmp/meshell/home/source` and a vault entry `meshell-signing` (kind `signing`, JSON with `keystore` as base64, `password` and optional `alias`).

## Start the terminal

After installing your signed APK, extract its broker and startup script on the build host:

```sh
unzip -p build/MeShell.apk assets/meshelld > build/meshelld
unzip -p build/MeShell.apk assets/start.sh > build/start.sh
unzip -p build/MeShell.apk assets/mkshrc > build/mkshrc
adb shell mkdir -p /data/local/tmp/meshell/home
adb push build/meshelld build/start.sh /data/local/tmp/meshell/
# Fresh installations only; preserve an existing customized .mkshrc.
adb push build/mkshrc /data/local/tmp/meshell/home/.mkshrc
adb shell chmod 700 /data/local/tmp/meshell/meshelld
adb shell am start -W -n com.mesystem.shell/.MainActivity
adb shell sh /data/local/tmp/meshell/start.sh
```

Rebooting the phone requires restarting the broker through ADB. Tools such as curl, a JDK or Clang are provisioned separately; they are not embedded in the APK.

## Share modules

Long-press a custom module → **Export JSON** → inspect the definition → **Save file** or **Copy text**. Import from **Modules → + → Import JSON file** or paste JSON; confirm the full definition before installation or replacement. Installation does not run its commands.

Exports remove local working directories and personal vault alias names. Recipients create the referenced vault aliases locally; never put credential values in JSON commands.

## Repository

See [architecture](docs/ARCHITECTURE.md), [security](SECURITY.md), [contributing](CONTRIBUTING.md) and [changes](CHANGELOG.md).

GitHub's **Verify** workflow scans history for secrets, builds the APK and runs the integration tests in an Android 14 (API 34) emulator.

## License

[MIT-0](LICENSE) (MIT No Attribution): use, modify, redistribute and sell without an attribution requirement. External tools and services keep their own terms; see [third-party notes](THIRD_PARTY.md).
