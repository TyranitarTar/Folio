# Folio 0.14 layout decisions — 23 October 2026

The existing reading, audio, voice and café data remain authoritative. This is
an interface revision; imports do not reset an existing title's saved position.

| Area | Compact layout |
|---|---|
| Bottom navigation | Books · Audiobooks · Café · Browse · More; Café stays in the centre even before it is enabled. |
| Books | Cover grid/list, search, All / New / Unread / Read, existing sorting/shelves, + import. New means never started; Unread means unfinished. |
| Audiobooks | Matching library hierarchy: search, status filters, sort, cover/icon rows with progress, Resume and Read where linked. + opens the import screen. |
| Audio import | Files / narrated book / single book folder / persistent library root; saved source, refresh, relink and import progress live here. |
| Reader | Book and chapter page counts, clearly labelled Listen action, persistent playback controls. Long press offers Copy, online Danish lookup and saved highlights. |
| Guided reading | Resume and Start share the first row. Focus mode has a small live example. No countdown/practice/automatic target escalation. Every reader modal pauses pacing. |
| Read aloud | Resume and Read from here first; voice/rate next; performance test and chapter preparation share a row. Threads are one advanced row. |
| Voices | Compact model summary, language/quality/demand/size/state, immediate sample/install actions; voice variants and technical detail expand on demand. |
| Information | Total words and current chapter first. Chapter distribution and counting details are expandable. |

Large text and narrow screens use scrolling/flow layouts rather than stacked
fixed-height controls. Import and maintenance controls do not occupy the audio
library home. Existing filter names are migrated explicitly, not silently
reinterpreted. Manual read/unread state is independent of saved positions.

Mihon's supplied source is a reference for navigation, settings sections and
library hierarchy. New Folio layouts retain the existing licensed attribution;
no unrelated manga features or proprietary audiobook-player code are copied.

The dictionary action opens an encoded query in the external browser. Verified
23 October: [Den Danske Ordbog](https://ordnet.dk/ddo/ordbog?query=kat).
It is online; no dictionary corpus is bundled.

Six original launcher drafts were presented. The user selected **2 — Bookmark Cat**; adaptive, legacy and themed resources now use that design.
