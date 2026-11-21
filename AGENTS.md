# spofify-mobile

## User-facing design and information

This applies to every user-facing surface in Spofify, including admin tools, setup, settings, lists, dialogs, empty and error states, and notifications.

- Build for direct understanding. Use hierarchy, grouping, familiar controls, clear labels, and visible state so users can tell what matters and what they can do without reading instructions. If prose is needed to explain the interface, improve the interface first.
- Show the information itself: a heading, value, status, or action. Trust familiar terms. Delete redundant copy rather than shortening it. No default subtitles, tooltips, or filler.
- Add short, specific context only when it changes a decision, makes a consequence clear, or helps someone recover. Put optional details on demand and implementation mechanics in docs. Preserve accessible names and states.
- Do not show retry buttons or equivalent retry actions. Automatically retry transient failures a reasonable, bounded number of times with backoff; retry writes only when safe to repeat. Stop on permanent failures or cancellation and show the resulting state without asking the user to retry.
- Review the whole surface before shipping. Remove anything the design or another label already communicates.
