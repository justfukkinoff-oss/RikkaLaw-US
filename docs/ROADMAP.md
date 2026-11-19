# RikkaLaw-U.S. — Build Roadmap

A mobile-first, fully on-device legal research and drafting tool for **U.S. criminal law,
post-conviction, habeas corpus, and wrongful-conviction work**. English only. No off-device
server, no Docker. The assistant supports **licensed counsel who review all output**; it does
not give legal advice.

This file is the source of truth for what is being built and in what order. Each phase lands
as one or more PRs that must pass the `debug-apk` CI build before merge.

## Design principles

1. **Narrow, not generic.** Every feature that does not serve legal research, document
   analysis, drafting, or the agent runtime that powers them is removed — not hidden.
2. **Behavior is in code, not personas.** Guardrails, retrieval, citation checks, and drafting
   formats are enforced by Kotlin code paths and non-editable system prompts, not by a
   character card the user can overwrite.
3. **Everything stays on the phone.** Indexes, embeddings, memory, projects, and the Linux
   workspace live in app storage. The only network calls are the ones the user configures:
   model providers (BYOK), MCP servers, web search, and public legal sources.
4. **Exact text is first-class.** Legal work depends on verbatim quotations and pin cites, so
   full-text / exact-phrase retrieval is a peer of semantic search, not an afterthought.

## Architecture (target)

```
 ┌───────────────────────── Android app (Kotlin / Compose) ─────────────────────────┐
 │  Chat + Agent runtime (multi-step, long-horizon, sub-agents, compaction, budgets)│
 │     │                                                                            │
 │     ├─ Hardwired legal system layer (main assistant + Researcher/Verifier/Drafter)│
 │     ├─ Tools: legal_search · quote_check · citation_verify · case_lookup ·        │
 │     │         statute_lookup · docx_create · web search/fetch · workspace · MCP   │
 │     │                                                                            │
 │  Projects (matters) ── uploads ── Ingestion pipeline                              │
 │     │                   parse (PDF/DOCX/TXT/MD/EPUB) → OCR (model slot) →         │
 │     │                   legal-aware chunking (page + ¶ + citation-safe) →         │
 │     │                   FTS5 index  +  embeddings (model slot)                    │
 │     │                                                                            │
 │  Legal RAG engine (SQLite on device)                                              │
 │     BM25 (FTS5, porter + exact-phrase) ─┐                                         │
 │     Vector cosine (float32 BLOBs)  ─────┼─ RRF fusion → rerank (model slot) →     │
 │     Citation index (extracted cites) ───┘   exact-span verification → results    │
 │                                                                                  │
 │  Memory layer (hot/cold memory, per-project notes)                                │
 │  BYOK providers: Gemini API · Gemini OAuth · OpenRouter · Groq · NVIDIA NIM ·      │
 │                  + any OpenAI-compatible endpoint                                 │
 │  Model slots: chat · fast · sub-agent · embedding · rerank · OCR · title/compact │
 │  MCP client: streamable HTTP/SSE, header auth, OAuth 2.1 (PKCE, DCR, refresh)    │
 │  Linux workspace: PRoot (sandboxed, optional) or Termux bridge                    │
 └──────────────────────────────────────────────────────────────────────────────────┘
```

## Phases

### Phase 1 — Trim to the legal core
- [x] Built-in providers reduced to Gemini (API key), Gemini OAuth, OpenRouter, Groq, NVIDIA
      NIM; custom OpenAI-compatible providers can still be added.
- [x] Remove local tool groups with no legal use: SMS read/send, contacts, call log,
      location, telephony/Wi-Fi/sensors, torch/vibrate/brightness/volume, wallpaper, NFC,
      Shizuku, screen automation/accessibility, app launcher, virtual display, notification
      listener, Telegram bot, keyboard control, media player, image/video generation,
      system intents, external (Tasker) automation, battery/audio info.
- [x] Strip the matching Android permissions from the manifest.
- [x] English only: drop the ar / ja / ko / ru / zh / zh-TW resource sets.
- [ ] Pass 2: delete the now-unreferenced tool sources, services (accessibility,
      notification listener), the Shizuku / Telegram / keyboard modules, lorebooks, and the
      Codex / Grok / AICore / LiteRT / llama.cpp provider code paths and native builds.
- [x] Settings screens, string resources, workspace and assistant screens translated to English.
- [ ] Translate remaining Chinese code comments and debug/developer screens.

### Phase 2 — Legal RAG engine (on-device)
- [x] `legal/` package (raw SQLite via the app DB helper rather than Room): entities `LegalProject`, `LegalDocument`, `LegalChunk`,
      `LegalCitation`; FTS5 virtual table `legal_chunk_fts` (porter unicode61 for keyword,
      plus trigram table for exact / partial-phrase matching).
- [x] Legal-aware chunker: respects pages, paragraphs, numbered ¶s, headings
      (`I.`, `A.`, `1.`), footnotes; never splits a citation or a quoted passage.
- [x] Citation extractor (eyecite-style, Kotlin): U.S., S. Ct., L. Ed. 2d, F./F.2d–F.4th,
      F. Supp. 1–3d, F. App'x, B.R., state reporters, regional reporters, `U.S.C. §`,
      `C.F.R.`, Fed. R. Crim. P./Evid./App. P., AEDPA sections, short forms and *Id.*
- [x] Embedding client (OpenAI-compatible `/embeddings`, Gemini `embedContent`),
      batched, resumable, stored as float32 BLOBs per chunk.
- [x] Rerank client (Cohere/Jina/NVIDIA-style `/rerank`), optional.
- [x] Hybrid retriever: BM25 + vector + citation hits fused with Reciprocal Rank Fusion,
      then optional rerank; every hit returns document, page, and character span.
- [x] Tools: `legal_search` (modes: hybrid / exact / keyword / semantic / citation),
      `quote_check` (verify a quotation appears verbatim in project sources, with
      fuzzy-diff when it does not).

### Phase 3 — Projects & uploads
- [x] Projects screen: create matter, upload documents (multi-select SAF), per-project
      "auto-embed uploads" toggle, indexing progress, re-index, delete.
- [x] Files area gets direct upload with the same auto-embed option.
- [ ] Conversations can be attached to a project; retrieval is scoped to it by default.
- [x] OCR pass for scanned PDFs using the OCR model slot (vision model), page-by-page.

### Phase 4 — Model slots & BYOK
- [x] Add `embedding`, `rerank`, and `ocr` slots alongside chat / fast / sub-agent.
- [x] Model fetch already exists; add embedding / rerank model detection.
- [x] NVIDIA NIM rerank + embedding presets.

### Phase 5 — Hardwired legal system layer
- [x] Non-editable base prompt injected ahead of any user prompt for the main assistant and
      every sub-agent: role (assistant to licensed counsel), no legal advice, U.S. law only,
      English only, criminal / habeas / wrongful-conviction focus, verification duties,
      output disclosure footer.
- [x] Role prompts for Researcher / Verifier / Drafter sub-agents, also non-editable; the
      user may only append.

### Phase 6 — Legal lookup & verification (Cetient / Pico-style)
- [x] `citation_verify` (CourtListener citation-lookup).
- [x] `case_law_search` / `case_law_opinion` (CourtListener search + opinion text).
- [ ] `docket_get` (RECAP).
- [x] `us_code_section` (GovInfo, most recent edition).
- [ ] eCFR and Federal Rules lookups.
- [ ] Verification report: every authority in a draft → verified / ambiguous / not found,
      quotation match against opinion text, flag overruled/abrogated where data allows.

### Phase 7 — Drafting & docx
- [x] python-docx templates inside the workspace (legal-drafting skill).
- [x] Native Kotlin `docx_create` tool (pure OOXML writer, no POI) so drafting works with no
      workspace installed: pleading caption, headings, numbered paragraphs, footnotes.
      A table of authorities or cite-check sheet only appears if the model writes it as
      content; neither is generated automatically from citations yet.
- [ ] Auto-generated table of authorities and cite-check sheet from `legal_authorities`.

### Phase 8 — Workspace bootstrap
- [x] One-tap PRoot bootstrap profiles: `core` (python3, pip, curl, wget, git, jq),
      `research` (requests, beautifulsoup4, lxml, pdfplumber, pypdf), `drafting`
      (python-docx, docxtpl), `ocr` (tesseract-ocr, ocrmypdf), `citations` (eyecite).
- [x] Termux bridge stays as the alternative when Termux is already installed.
- [x] Bootstrap runs as a background process with step i/n progress, a live log, Stop, and
      "Run in background"; it re-attaches when the screen is reopened. Distro packages are
      installed in one batch (per-package fallback). Full log: `/root/.rikkalaw/bootstrap.log`.

### Phase 9 — MCP OAuth hardening
- [x] OAuth 2.1 coordinator, metadata discovery, loopback callback, token refresh exist.
- [x] DCR fallback to manual client id / secret / scopes, POST probe for resource metadata,
      token expiry, re-authorize and sign-out in the MCP settings page.
- [ ] Audit end-to-end against a real OAuth MCP server.

### Field fixes (Nov 2026)
- [x] Legal Research tools (legal_search, legal_document_read, citation_verify, case law,
      U.S. Code, docx_create, …) are always on for every assistant and sub-agent; before
      this, only the Counsel assistant had them.
- [x] Files → Uploads shows an AI-index panel (embedded / pending / not embedded / failed /
      needs OCR, the current embedding model, and live progress), a status for each file,
      and "Index for AI search", "Embed now", and "Choose model" actions.
- [x] Embedding starts automatically for pending documents once an embedding model is
      selected.
- [x] Settings text fields no longer re-type earlier characters: the settings store updates
      memory first, coalesces disk writes, and ignores stale DataStore re-emissions.
