# Module ownership

`ModuleHub` owns definition preparation, installation, enablement, export and dispatch. `ModuleDefinition` interprets the module interchange contract in `res/raw/module_schema.json`; that file is the source of truth for fields, types, limits and portable-field annotations. `ModuleStore` owns persistence. Callers do not read its tables.

The contract uses objects, arrays, strings and integers, with required fields, property names, patterns, bounds and unknown-field rejection. `x-maxJsonCharacters` bounds compact JSON using Java string length, `x-uniqueBy` rejects duplicate action IDs, `x-local` removes a field during portable export, and `x-portableAliases` replaces vault references with their environment-variable names. There is no general schema framework or remote schema resolution.

Import shows the complete validated JSON and requires confirmation before saving or replacing a same-ID module. Saving does not execute commands. Agent-facing `module_install` also uses the same validation contract; the UI is not a second validator.

Portable export includes only contract fields. It omits `cwd`, replaces personal vault aliases with neutral placeholders and excludes ignored legacy/UI fields. `ModuleHub` blocks output containing a known vault secret value. Inline credentials unknown to the vault or personal data embedded in commands may still exist: authors must inspect exported commands and scan before publishing. Recipients provision referenced secrets locally and restore a required working directory themselves.

The separate [`meshell-tools`](https://github.com/ilya-bondarev/meshell-tools) project stores catalogue metadata outside `module.json`. It vendors a pinned snapshot of this app-owned schema, records its source commit and SHA-256, and does not edit that schema independently. Publication reviews module IDs for built-in collisions; the app rejects those on import. Definition validation is stricter than older releases: unsupported fields, duplicate action IDs and invalid optional values now fail visibly.

Modules run with the broker's ADB-shell capabilities, not a per-module sandbox. Import and export never execute an action. No online upload, automatic install or automatic update is added to the app; authors publish through GitHub forks and reviewed PRs, users download JSON and import it explicitly.
