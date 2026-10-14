# Repository invariants

Read `docs/ARCHITECTURE.md` before editing. Name the edited file's concern in the commit. Prefer functions and existing ownership over new frameworks or visibility-only rewrites.

1. One owner per rule domain. Extend the listed owner and its rules document; consolidate scattered rules before extending them. Function-level imports are not a cycle fix.
2. Never add a second copy of a rule, schema fact or repeated UI piece. Grep first, move the existing implementation, switch every caller without changing behavior, verify, then make the new change separately. Name any intentionally remaining copies in the commit.
3. No business arithmetic, thresholds or classifications in rendering. Views format values returned by owners. Platform-independent domains take and return plain values and do not import Android.

Tests and gates are part of the change, not a promise for later. `tools/RepositoryCheck.java` and the Verify workflow enforce the extracted boundaries and resource contracts. Keep credentials and generated files out of commits. Never install an update, publish a repository or run paid integrations merely to test a refactor.
