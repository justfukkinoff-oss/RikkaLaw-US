# RikkaLaw-US — Legal Assistant Blueprint

RikkaLaw-US adapts the [RikkaHub Agent · Pure](https://github.com/wuyhong715/rikkahub-agent-pure)
Android agent into a U.S. legal research and case-management assistant. This document maps
each product requirement onto the base's existing (verified-in-code) capabilities and lists
the deltas still to build.

**Scope constants:** U.S. law only · English only · criminal law, post-conviction, habeas
corpus (28 U.S.C. §§ 2241/2254/2255, AEDPA), wrongful convictions. The assistant is a
research/analysis/drafting aid for **licensed counsel who review all output**; it never
provides legal advice.

The operating profile lives in [`skills/legal-assistant/SKILL.md`](../skills/legal-assistant/SKILL.md)
(also registered in the bundled skill catalog), which the agent loads for any matter work.

## Requirement → base capability map

| Requirement | Base capability (already in this codebase) | Delta |
|---|---|---|
| BYOK, OpenAI-style, model fetch | Multi-provider endpoints, model catalog sync, aliasing (upstream RikkaHub heritage) | — |
| Per-task model assignment | Settings model slots: chat, fast, memory, translate, OCR (`SettingModelPage.kt`) | Add + embedding and + verification slots once components need them |
| Knowledge base / grounding | **Cold memory**: Markdown KB indexed on demand (`memory_index` / `memory_read` / `memory_write`) — nothing pinned in context until read | Per-matter folder convention enforced by the skill |
| Memory layer | File-based memory + memory model slot, plus cold memory | — |
| Multi-step / long-horizon agent | The "Pure" hardening layer: usage ledger & budgets, tool-result caps, model-initiated compaction, execution retry, keep-alive, foreground long runs | Matter checkpoint pattern (skill § "Long-horizon investigations") |
| Agent tools & skills | Tool catalog + on-demand tool palette (`tool_search` / `tool_open`), skills runtime, expert sub-agents (`subagent_create` / `subagent_dispatch`, per-expert model/tools/memory) | Define `researcher` / `verifier` / `drafter` experts per matter |
| Web search | `search_web` / `scrape_web` / `web_fetch` + provider-backed search | — |
| MCP client (OAuth + header auth) | MCP support; the `oauth`/ module implements MCP OAuth 2.1 (loopback callback, metadata discovery, token refresh); custom HTTP headers supported for static-token servers | — |
| Legal lookup & verification (CourtListener / Eyecite-style) | Workspace shell + network, sub-agent isolation | Skill protocol § "Citation verification": `POST /api/rest/v4/citation-lookup/` with a stored token; quotations confirmed against opinion text; unverified marks enforced |
| Projects with document uploads | Linux workspace (proot) with `workspace_*` tools; file manager; shared-storage mounts | `~/matters/<slug>/{sources,corpus,drafts,notes}` layout; uploads via app or `/mnt/shared` |
| Root workspace + bootstrap | PRoot environments (Debian/Ubuntu/Alpine), package install, background jobs, Termux/Termux-session tools as alternative bridge | Skill § "Workspace bootstrap": python3, pip, curl, wget, beautifulsoup4, python-docx, requests |
| docx creation | python-docx inside the workspace (safer and far lighter than POI-on-Android; Android layer only receives the finished file) | Templates under `~/matters/<slug>/drafts/templates/` |
| Guardrail system prompt | Skills + assistant system prompts | Hard-coded in `skills/legal-assistant/SKILL.md`: no legal advice, U.S. only, English only, no unverified citations in deliverables, disclosure footer |

## Verification protocol (summary)

1. Parse every authority from the draft.
2. Verify case law with CourtListener citation-lookup (`Token` auth; token stored via the
   workflow secret store / encrypted settings — never in chat or corpus files).
3. Verify pinned quotations character-for-character against the opinion.
4. Emit a status table (`verified` / `unverified` / `statute — manual check`) next to the
   draft; unverified citations are stripped or flagged before anything reaches counsel.

This mirrors the anti-hallucination goal of commercial citation checkers (the
*Mata v. Avianca* class of failure) using only free infrastructure.

## Data sources for the knowledge base

| Source | Use | License |
|---|---|---|
| [CourtListener REST API v4](https://www.courtlistener.com/help/api/rest/) + [citation lookup](https://wiki.free.law/c/courtlistener/help/api/rest/v4/citation-lookup) | Opinions, dockets (RECAP), citation verification | Free (API token) |
| [Caselaw Access Project](https://case.law/caselaw/) | 6.7M U.S. decisions in bulk | CC0 |
| [National Registry of Exonerations](https://exonerationregistry.org/) | ~4,000 exoneration records with contributing factors | Free downloads |
| [Pile of Law](https://huggingface.co/datasets/pile-of-law/pile-of-law) | Large legal/administrative corpus for KB seeding | Open |
| uscode.house.gov / govinfo.gov bulk | Statutes & Federal Rules | Public domain |

## Roadmap

1. **Done:** base import + `skills/legal-assistant` profile + catalog entry.
2. **Done:** the native `citation_verify` local tool (Kotlin, in the new
   `legal_research` local-tool group) — CourtListener citation-lookup with per-citation
   verdicts (`verified` / `ambiguous` / `unverified` / `throttled`), the token kept in the
   on-device vault via `request_secret` (`COURTLISTENER_TOKEN` passed as `token_secret`),
   so verification no longer depends on workspace `curl` scripting. Seeded alongside it:
   a default "Counsel" assistant preconfigured with the legal system prompt, memory on,
   and the `legal_research` tool group enabled (its `legal-assistant` skill activates
   once installed from the catalog).
3. **Done:** the three default experts — `Researcher` / `Verifier` / `Drafter` agent
   definitions seeded into the expert library at boot (fixed ids, idempotent; a deliberate
   delete is recorded and never resurrected). `subagent_dispatch` names them directly.
4. **Done (first half):** the matter knowledge layer — the `corpus_import` local tool
   (registered with the cold-memory tools, so it only exists when a workspace + knowledge
   directory are configured): bulk-imports a matter's `corpus/` directory (`.md` / `.txt`
   / `.csv`, default source `corpus`) into normalized `kb-<name>.md` cold-memory documents
   with a provenance header, batch caps, and no clobbering of existing documents unless
   `overwrite` is requested. Matter layout convention is now
   `/workspace/matters/<matter-slug>/{sources,corpus,drafts,notes}` in the workspace file
   area. Remaining: per-project model assignments when embedding models are added.
5. **Done:** the docx template library — the bundled **legal-drafting** skill: §2254 and
   §2255 petition skeletons, a legal memorandum, and a public-records request letter
   (FOIA + state-law shell), a python-docx conversion recipe with base styling, and a
   mandatory cite-check review sheet embedded as the last table in every `.docx` (one row
   per authority, verdicts exactly as the verification protocol reported, initials column
   left for counsel). Unverified citations are bracketed in the body, never formatted as
   authorities; the Counsel assistant ships with the skill enabled and every default
   expert inherits it.

## License

Inherits AGPL-3.0 from the base lineage
(rikkahub/rikkahub → ExTV/rikkahub-agent → wuyhong715/rikkahub-agent-pure → RikkaLaw-US).
Not affiliated with or endorsed by any upstream project; see lineage in
[README.md](../README.md#why-this-project).
