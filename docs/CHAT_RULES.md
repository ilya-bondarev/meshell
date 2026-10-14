# Chat ownership

`ChatEngine` owns task admission and the per-chat registry. One task can run per chat; other chats can run concurrently. Cancellation retains the task until its worker settles. Stale notification identities and stale completion callbacks cannot affect a replacement task.

`RetryPolicy` decides whether an answer can be regenerated or must be checked and continued. A damaged journal is an unknown outcome, not evidence that no command ran. Continuation preserves recorded tool calls and asks for verification; it is not automatic replay.

`ContextBudget` owns input limits and tool-result repair. `CompactContext` owns history chunking and summary framing. Summaries and attachments are data, not new authority. Compaction keeps original history and commits only a completed summary.

`ChatStore` owns history, drafts, response versions, monotonic identities and migrations. Existing migration columns are the source of truth; do not add another status fact to a view or cache.
