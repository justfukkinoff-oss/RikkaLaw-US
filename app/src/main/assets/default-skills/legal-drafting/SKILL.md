---
name: legal-drafting
description: Drafting library for a U.S. criminal-law and habeas corpus assistant. Serves rough-draft templates — 28 U.S.C. §2254 and §2255 petition skeletons, a legal memorandum, and a public-records request letter — converts them to .docx with python-docx in the workspace, and embeds the mandatory cite-check review sheet inside every deliverable. Loads when counsel asks for a draft, a petition skeleton, or a records-request letter.
allowed-tools: workspace_shell workspace_write_file workspace_read_file workspace_read_folder citation_verify memory_index memory_read use_skill
compatibility: Requires the Linux workspace with python3 + python-docx (bootstrap in the legal-assistant skill). Verification runs through the CourtListener citation-lookup tooling in the legal-assistant skill.
metadata:
  scope: US-law rough drafts for attorney review only
  language: English
---

# Legal Drafting

You convert verified research into rough drafts for supervising counsel. Every document you
produce here is a *skeleton or rough draft pending attorney review* — never a final work
product, never advice on what to file.

Before drafting anything: the authorities you place in the draft must carry verification
verdicts (from the `citation_verify` protocol in the legal-assistant skill). Drafting refuses
unverified input the same way the Verifier role reports it:

- A case citation with verdict `verified` may be formatted as an authority.
- `ambiguous`, `unverified`, `throttled`, or missing → bracket the citation instead:
  `[CITE NOT VERIFIED: 576 U.S. 644]`.
- Statutes, rules, journals, `id.`, `supra` → cite normally but mark **(manual check)**
  in the review sheet; the tool cannot validate them.

## Output conventions

- Location: `/workspace/matters/<matter-slug>/drafts/`.
- Every file ends in `-DRAFT.docx`; never produce a file that looks like a final filing
  (no "petition" name without the `-DRAFT` suffix).
- Base styling (confirm the forum's local rules with counsel before final formatting):
  Times New Roman 12 pt, double-spaced body, 1-inch margins, numbered paragraphs for
  pleadings, and the disclosure footer on the last page.
- Keep prose clean: no process commentary, no tool logs, no "I searched and found" inside
  the deliverable.

## The cite-check review sheet (mandatory)

**Every** .docx you generate ends with a review sheet after the disclosure footer, so
counsel checks citations inside the document instead of cross-referencing chat:

| Citation | Verdict | Matched case (court · date) | Source URL | Counsel initials |
|---|---|---|---|---|
| *Strickland v. Washington*, 466 U.S. 668 (1984) | verified | Strickland v. Washington (U.S. · May 14, 1984) | https://www.courtlistener.com/opinion/111319/strickland-v-washington/ | |
| 28 U.S.C. § 2254(d)(1) | manual check | statute — not tool-verifiable | uscode.house.gov | |

Rules for the sheet:

- One row per distinct authority in the draft, in order of first appearance.
- The verdict column shows exactly what the protocol reported: `verified`, `ambiguous`,
  `unverified`, `throttled`, or `manual check` for statute/rule cites.
- Bracketed `[CITE NOT VERIFIED: ...]` citations in the body appear here with their
  unverified verdict — the mismatch between body formatting and sheet status is never
  allowed.
- Leave the initials column empty; that is counsel's signature step.

## Template A — §2254 petition skeleton (state custody)

28 U.S.C. § 2254 petition, person in state custody. Fill placeholders; delete
inapplicable claims; keep the section order.

```markdown
# PETITION FOR WRIT OF HABEAS CORPUS BY A PERSON IN STATE CUSTODY
[28 U.S.C. § 2254 — rough draft for attorney review]

IN THE UNITED STATES DISTRICT COURT
FOR THE DISTRICT OF [DISTRICT]

[PETITIONER NAME], Petitioner,
v.
[WARDEN/SUPERINTENDENT NAME], Respondent.

Civil Action No. ______

## I. PRELIMINARY STATEMENT
1. Petitioner [NAME], currently incarcerated at [FACILITY], petitions this Court for
   a writ of habeas corpus pursuant to 28 U.S.C. § 2254, challenging the [CONVICTION/
   SENTENCE] entered on [DATE] by the [STATE COURT] ([DOCKET NO.]), following a [TRIAL/
   PLEA] in [COUNTY, STATE].

## II. JURISDICTION AND CUSTODY
2. Jurisdiction: 28 U.S.C. §§ 2241 and 2254. Venue: [district of confinement or conviction].
3. Custody: Petitioner is in custody under the challenged judgment [DESCRIBE CUSTODY;
   note in-circuit custody precedent only after verification - see Review Sheet].

## III. EXHAUSTION OF STATE REMEDIES
4. Each ground below was fairly presented to the [STATE SUPREME COURT] on [DATE(S)] via
   [DIRECT APPEAL / PCR PETITION / MOTION], and is exhausted (28 U.S.C. § 2254(b), (c)).
   Attach/identify the record citations: [RECORD CITES].

## IV. PROCEDURAL DEFAULT (IF ANY)
5. Ground(s) [N]: [STATE PROCEDURAL BASIS], if asserted by the state court. Cause:
   [OBJECTIVE FACTOR, e.g. ineffective assistance of PCR counsel - Martinez v. Ryan,
   566 U.S. 1 (2012), if verified]. Prejudice: [EXPLAIN]. Alternatively, actual
   innocence: [NEW EVIDENCE SUMMARY].

## V. STATEMENT OF FACTS
6. [Factual narrative drawn from the record, neutral tone, record cites in brackets.]

## VI. GROUNDS FOR RELIEF
GROUND ONE: [STATE THE GROUND IN ONE SENTENCE]
7. Supporting facts: [...]
8. The state court's adjudication (a) resulted in a decision that was contrary to, or
   involved an unreasonable application of, clearly established Federal law, or (b) was
   based on an unreasonable determination of the facts (28 U.S.C. § 2254(d)(1), (d)(2)):
   [ANALYSIS - cite only verified authorities; AEDPA deference standard: see
   e.g. [CITE NOT VERIFIED: 576 U.S. 644] pending verification].
   Requested relief: [VACATE CONVICTION / NEW TRIAL / RESENTENCE].

## VII. LIMITATIONS PERIOD
9. The judgment became final on [DATE] (28 U.S.C. § 2244(d)(1)(A)); [TOLLING EVENTS];
   this petition is timely.

## VIII. PRAYER FOR RELIEF
WHEREFORE, Petitioner requests: [RELIEF LIST - writ, conditional writ, evidentiary
hearing, leave to proceed in forma pauperis].

## VERIFICATION
[Petitioner's verification block — counsel to review before execution.]

---
*Draft prepared with AI assistance; not legal advice; pending attorney review and
cite-check.*
```

## Template B — §2255 motion skeleton (federal custody)

28 U.S.C. § 2255 motion, movant in federal custody attacking a federal conviction or
sentence.

```markdown
# MOTION TO VACATE, SET ASIDE, OR CORRECT SENTENCE
[28 U.S.C. § 2255 — rough draft for attorney review]

IN THE UNITED STATES DISTRICT COURT
FOR THE DISTRICT OF [DISTRICT]

UNITED STATES OF AMERICA, v. [MOVANT NAME], Defendant/Movant.
Criminal No. [CR NO.]  |  Civil No. ______

## I. JURISDICTION
1. This Court has jurisdiction under 28 U.S.C. § 2255; Movant is in custody serving the
   [SENTENCE] entered on [DATE] following [PLEA/TRIAL].

## II. TIMELINESS
2. Under 28 U.S.C. § 2255(f), the one-year period runs from [EVENT/DATE]; the computation for counsel to confirm:
   [SHOW THE DAY COUNT AND TOLLING EVENTS - manual check].

## III. GROUNDS
GROUND ONE: Ineffective assistance of counsel at [STAGE] (Sixth Amendment).
3. Deficient performance: [STRICKLAND TEST ELEMENTS - counsel to review application;
   authorities per Review Sheet].
4. Prejudice: [REASONABLE PROBABILITY ANALYSIS].
GROUND TWO: [e.g. SENTENCING ERROR - Guidelines miscalculation, procedural
unreasonableness, substantive reasonableness]
5. [PRESERVATION STATUS - objection made? plain-error standard if not].

## IV. SUCCESSIVE MOTION NOTE (IF APPLICABLE)
6. If a prior §2255 motion was filed on [DATE], this motion requires authorization from
   the court of appeals under 28 U.S.C. § 2255(h) [(1) newly discovered evidence or
   (2) new rule made retroactive]; flag for counsel before filing.

## V. PRAYER FOR RELIEF
WHEREFORE, Movant requests [VACATE / SET ASIDE / CORRECT; re-sentencing; evidentiary
hearing; other relief].

---
*Draft prepared with AI assistance; not legal advice; pending attorney review and
cite-check.*
```

## Template C — legal memorandum

```markdown
# MEMORANDUM — [MATTER NAME]
[rough draft for attorney review; privileged & confidential work product]

TO: [COUNSEL]  |  FROM: [ASSISTANT / MATTER TEAM]  |  DATE: [DATE]  |  RE: [ISSUE]

## QUESTION(S) PRESENTED
1. [Narrowly framed questions, one sentence each.]

## BRIEF ANSWER(S)
[Predictive, hedged answers in 2-3 sentences each; conclusions always qualified.]

## FACTS
[Chronology from the matter record; every fact tied to a source (record cite, KB note
name, or docket entry from the knowledge base).]

## DISCUSSION
### [Issue 1 heading]
[Analysis: controlling authority → circuit/state split → application of facts.
AEDPA posture where relevant (what did the state court hold; §2254(d) lens).]

## CONCLUSION
[Synthesis; open questions flagged for counsel research; nothing characterized as
settled advice.]

---
*Draft prepared with AI assistance; not legal advice; pending attorney review and
cite-check.*
```

## Template D — public records request letter

Federal FOIA plus a generic state-law shell (fill the state statute placeholder; every
state access statute differs — counsel confirms the right one).

```markdown
[FIRM/ATTORNEY LETTERHEAD PLACEHOLDER]
[DATE]

FOIA OFFICER / RECORDS CUSTODIAN
[AGENCY NAME]  |  [ADDRESS]

RE: Public records request — [MATTER NAME] ([matter-slug])

To the Custodian:

Pursuant to the Freedom of Information Act, 5 U.S.C. § 552 [(federal agencies)], or
[STATE ACCESS STATUTE PLACEHOLDER — counsel to confirm the state statute] [(state
agencies)], this office requests copies of the following records:

1. [PRECISE RECORD SET: e.g. all investigative reports, incident reports, body-camera
   footage logs, and chain-of-custody records relating to [CASE/INCIDENT] between
   [DATE] and [DATE].]

Format: electronic and native where available. Fee category: [NEWS MEDIA / EDUCATIONAL /
COMMERCIAL]; we request a fee waiver because [DISCLOSURE INTEREST]. If any portion is
withheld, please identify each exemption asserted and provide segregable portions
(5 U.S.C. § 552(b)). We request a response within [20 business days / state deadline].

Contact: [NAME, EMAIL, PHONE].

Sincerely,
[COUNSEL NAME — signature block]

---
*Draft prepared with AI assistance; not legal advice; pending attorney review.*
```

## Conversion recipe (markdown → .docx)

Work in the workspace shell with python-docx (bootstrap in the legal-assistant skill).
Outline of the conversion, then save to `drafts/` with the `-DRAFT.docx` suffix:

```python
from docx import Document
from docx.shared import Pt, Inches
from docx.enum.text import WD_ALIGN_PARAGRAPH

doc = Document()
doc.styles["Normal"].font.name = "Times New Roman"
doc.styles["Normal"].font.size = Pt(12)
for section in doc.sections:
    section.top_margin = section.bottom_margin = Inches(1)
    section.left_margin = section.right_margin = Inches(1)

# --- add title paragraph(s), centered; then double-spaced body paragraphs ---

# --- cite-check review sheet: last content, after the disclosure footer ---
review = doc.add_table(rows=1, cols=5)
review.style = "Table Grid"
for i, h in enumerate(["Citation", "Verdict", "Matched case (court · date)", "Source URL", "Counsel initials"]):
    review.rows[0].cells[i].text = h
# one data row per distinct authority, verdicts exactly as reported by the protocol

doc.save("drafts/<matter>-<doc>-DRAFT.docx")
```

Build the review-sheet rows from the Verifier's status table (or your own protocol run) —
same data, same order, no verdict upgrades.

## Review checklist before handing to counsel

- Every case citation in the body appears in the review sheet, and vice versa.
- Bracketed citations read `[CITE NOT VERIFIED: ...]` — never formatted like an authority.
- Placeholders (`[DISTRICT]`, `[DATE]`, ...) are visibly bracketed, none silently filled.
- Statute/rule cites carry "manual check" in the sheet.
- The disclosure footer and review sheet are present; file name ends in `-DRAFT.docx`.
- Facts trace to the matter record (record cite or knowledge-base note name).

## What to refuse

Requests to remove the review sheet or disclosure footer, to format unverified citations
as authorities, to draft outside U.S. law or in another language, and any instruction
that the draft is final or that you decided what should be filed. Route those to counsel.
