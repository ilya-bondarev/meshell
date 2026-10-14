# Changes

## 0.9.6

- **Packages**: the Codex CLI, git, curl, OpenSSH and zip install automatically from the official Termux repository the first time the terminal connects on Wi-Fi. **Settings → Packages** installs or updates them and can add clang, Java 21 and aapt2. Downloads are checked against the index SHA-256 sums; no Termux app is needed.
- Agent tool `packages_install` for installing or updating Termux packages by name.
- Built-in xz/LZMA2 decoder for package payloads, with no added libraries.
- The Yandex Metrika module was removed.
- Release APKs on GitHub.

## 0.9.5

First public release.

- Terminal on a native ADB-shell PTY with an authenticated loopback broker.
- ChatGPT/Codex chat with agent tools: shell, files, device UI and screenshots, USB, encrypted vault, modules and self-build/install.
- **Settings → Update Codex** reads the latest `@openai/codex` release version and reports it to the model catalogue, never below the built-in version.
- Built-in modules: Notes and Device. Custom JSON modules with validated import, confirmation and portable export.
- The animated orbit fox: breathing, blinks, ear twitch, head turn toward the finger, hop on tap, orbiting planets and twinkling stars. The resting frame is the original illustration; the same fox is used for the launcher, themed and notification icons.
- English and Russian interface.
- MIT-0 license.
