# Contributing

1. Name the concern of each changed file and find the existing owner of the rule.
2. Grep for the expression, threshold, format or schema fact before adding logic. Move all existing copies before introducing another caller.
3. Keep behavior-preserving refactoring separate from behavior changes. Include the output comparison when changing numeric rules.
4. Keep Android dependencies outside `src/services/`, SQL in persistence, and business rules outside views. Use the owner contracts rather than another module's tables or caches.
5. Add UI text to both resource files. Do not use translated labels as persisted identifiers. Keep accessibility names; avoid tooltips, welcome tips and long explanatory screen copy.
6. Run `sh tests/run_phone.sh` on the build host and `sh tests/run_device.sh` on a connected Android 14 emulator or Android 14/15 phone. On a provisioned phone, the first script runs integration tests directly; pass `en` or `ru` for the selected locale.

Keep tests focused on contracts and real regressions; do not rebuild the deleted micro-assertion suite. CI must pass before merging. ChatGPT calls need a real account and are not exercised by the offline suite. Do not put credentials in PRs or CI settings used by untrusted PR code.
