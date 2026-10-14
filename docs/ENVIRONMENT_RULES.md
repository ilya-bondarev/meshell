# Environment ownership

- `SecretFields` classifies sensitive field names. `SecretVault` masks stored values in tool output and module results. Secret values are not catalog metadata.
- `AgentTools` owns file-tool writes; `ShellBroker.tar` packs the private payload passed on stdin. `replace_text` compares the exact observed UTF-8 contents under its shared lock before rename. A conflict returns exit code 3 without changing the destination. Arbitrary shell commands and external editors do not use this lock.
- `ChatEngine` owns task admission. Scheduled installation keeps self-operation admission closed until process replacement. A failed updater must not silently admit tasks while its outcome is uncertain.
- `device/install_self.sh` owns installation. It locks concurrent updaters, snapshots the APK, verifies signing identity, extracts broker/start from that same APK, and attempts rollback after partial failure. A rollback failure is not success.
- `native/meshelld.c` owns authenticated transport. Pending authentication has an incremental deadline and cannot block PTY processing. Independent command workers do not type into the user terminal.
- `build.sh` owns APK construction and signing. `device/build_phone.sh` delegates to it. The current-source test suite runs before publishing a signed APK; an older classes jar is not test evidence.

After an unknown installation outcome, check `$HOME/update-result.txt` and installed state before taking action. Do not automatically resume or repeat installation. Recovery after power loss or process kill is not guaranteed.
