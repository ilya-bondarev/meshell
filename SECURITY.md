# Security

MeShell is a command-execution application, not an isolation boundary for untrusted agents or modules. A tool can modify files, Android settings and paid accounts with the authority granted to it. Stopping a task does not undo completed actions; an interrupted command may have executed.

## Boundaries

- The app runs as its Android app user. The native broker is started explicitly through ADB as uid 2000 and listens on loopback. Loopback alone is not authentication: the broker also verifies a shared-key handshake.
- The bootstrap provider requires Android's DUMP permission and checks for shell/root callers. Do not grant broad access to that provider or expose the broker through a network tunnel.
- Credentials are encrypted with Android Keystore. Agent-facing listing returns aliases and types, never values. Aliases themselves can contain personal information.
- Agent calls share phone files and accounts across chats. A sequence of calls is not a transaction. App and external shell operations do not all share the file-tool lock.
- Self-updates require the existing signing identity. The updater attempts rollback, but cannot guarantee recovery after abrupt termination or power loss.
- HTTPS and Android permissions remain enforced. No safety claim is made for arbitrary imported shell modules.

Chat requests send the selected conversation context and attachments to OpenAI under your ChatGPT account. The Codex update check reads the public npm registry. The Metrika module calls Yandex with the token you store. Review provider terms, retention and costs before use.

## Publishing

Never publish keystores, signing passwords, account tokens, vault files, chat databases, logs or the phone's full HOME. `.gitignore` prevents common accidental additions; it does not clean already-committed history. The Verify workflow scans that history with Gitleaks and uses no release credentials.

## Reporting

Use GitHub's private vulnerability reporting for this repository. Do not put tokens or an operational exploit against a real account in a public issue.
