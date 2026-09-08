# AI PDLC — one-minute spoken summary

Speaking transcript, timed for ~60 seconds at a normal presenting pace (~150 wpm). Read it
straight through once to check your own timing before using it live.

---

Let me take you on a journey — how one piece of work moves through the AI PDLC, from an engineer
prompting an agent to a full software factory.

Three actors carry it: code does anything deterministic, agents do the reasoning, engineers hold
judgment. A card is born on the board, grilled into a spec, and stops at its first human gate. It
splits into parallel lanes proven by the repo's own tests, then flows through review and release,
each signed by a gate. Deploy isn't the end — a monitor keeps watching, and a trip restarts the
journey.

We walked this live. Every failure — duplicate cards, a lost id, stuck cards — came down to one
word: idempotency. Every write must survive a retry.

Don't ask how to build an agent loop. Ask what journey your team already makes by hand — that's
the one worth automating.

---

*(149 words · ~60s at 150 wpm)*
