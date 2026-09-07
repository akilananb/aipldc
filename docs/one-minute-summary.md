# AI PDLC — one-minute spoken summary

Speaking transcript, timed for ~60 seconds at a normal presenting pace (~150 wpm). Read it
straight through once to check your own timing before using it live.

---

Ninety minutes, one idea: the loop was never the product — the workflow around it is. We grow
one diagram, two boxes at a time, into a full software factory.

Three actors do all the work: code for anything deterministic, agents for reasoning, engineers
for judgment. A card gets grilled into a spec, two humans gate it, then a plan agent splits the
work into isolated parallel lanes — each proven by the repo's own tests. A review agent flags
blockers, a second gate signs the PR, a release agent drafts the sign-off pack, and a monitor
agent watches the deploy — if something trips, it files a card and the wheel turns again.

We ran this live. Every failure — duplicate cards, a lost id, cards stuck in "new" — came down to
one word: idempotency. Retries happen, so every write must be safe to repeat.

So don't ask how to build an agent loop. Ask what repeatable workflow you already do by hand —
that's the thing worth turning into a factory.

---

*(174 words · ~60–65s at a natural presenting pace)*
