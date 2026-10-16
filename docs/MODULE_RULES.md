# Module ownership

`ModuleHub` owns definition preparation, installation, enablement, permission consent, export and dispatch. `ModuleDefinition` interprets the module interchange contract in `res/raw/module_schema.json`; that file is the source of truth for fields, types, limits, permissions and portable-field annotations. `ModuleStore` owns persistence. Callers do not read its tables.

The contract uses objects, arrays, strings and integers, with required fields, property names, patterns, enums, unique items, bounds and unknown-field rejection. `x-maxJsonCharacters` bounds compact JSON using Java string length, `x-uniqueBy` rejects duplicate action IDs, `x-local` removes a field during portable export, and `x-portableAliases` replaces vault references with their environment-variable names. There is no general schema framework or remote schema resolution.

## Permissions

`x-permissions` names the declared list. A non-empty field annotated with `x-impliesPermission` (`env_secrets` → `secrets`, `packages` → `packages`) and a command matching an `x-permissionPatterns` expression imply a permission. Validation rejects a definition whose implied permissions are not declared; effective permissions are declared plus implied, in vocabulary order. Patterns recognise common commands, not every way of reaching a capability.

Install metadata records the source (`local` or `catalog`), catalogue version, author and granted permissions. A custom action runs only when every effective permission is granted. File, paste and catalogue installs show the permissions and grant them on confirmation; agent installs keep earlier grants, so a new permission waits for the user in the module page. Modules stored before install metadata existed have no metadata row value and keep running. Packages listed in `packages` are installed through `Packages.ensure` before the action runs.

## Import, export and catalogue

Import shows the permissions, offers the complete validated JSON and requires confirmation before saving or replacing a same-ID module. Saving does not execute commands. Agent-facing `module_install` also uses the same validation contract; the UI is not a second validator.

Portable export includes only contract fields and declares the effective permissions. It omits `cwd`, replaces personal vault aliases with neutral placeholders and excludes ignored legacy/UI fields. `ModuleHub` blocks output containing a known vault secret value. Inline credentials unknown to the vault or personal data embedded in commands may still exist: authors must inspect exported commands before publishing.

The separate [`meshell-tools`](https://github.com/ilya-bondarev/meshell-tools) project owns catalogue metadata, the index and statistics, and the GitHub issue protocol for ratings and submissions; `MarketRules` writes that protocol and the app never parses it. The catalogue vendors a pinned snapshot of this app-owned schema with its source commit and SHA-256 and does not edit it independently. `Marketplace` reads `index.json` and `stats.json` from the catalogue's Pages site, installs from the GitHub release asset named in the index (counted by GitHub) and rejects a download whose SHA-256 differs. There is no automatic install or update; each needs the user's confirmation.

Modules run with the broker's ADB-shell capabilities, not a per-module sandbox. Import, export, publishing and catalogue browsing never execute an action.
