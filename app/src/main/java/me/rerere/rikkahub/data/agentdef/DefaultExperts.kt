package me.rerere.rikkahub.data.agentdef

import android.content.Context
import androidx.core.content.edit

/**
 * RikkaLaw — the three default experts (researcher / verifier / drafter) the Counsel
 * assistant (and every other assistant) can name in `subagent_dispatch`. They are ordinary
 * rows in the expert library, seeded once at boot with fixed ids so the seeding is idempotent.
 *
 * Tool surfaces, model and skills are left `null` (inherit the parent assistant) — the
 * defaults are descriptions of a role, not a frozen configuration; mailboxes of the parent
 * assistant remain authoritative. A user can edit or delete them like any other expert, and
 * a deliberate delete is recorded in [DefaultExpertRecord] so the next boot does not
 * resurrect the row.
 */
internal const val DEFAULT_EXPERT_RESEARCHER_ID = "7143c599-ec1b-53a0-95ec-f1037608afb1"
internal const val DEFAULT_EXPERT_VERIFIER_ID = "40b489ce-b1cc-53a7-bca3-bf97ad4ba665"
internal const val DEFAULT_EXPERT_DRAFTER_ID = "95f0a89e-f243-5d4c-9215-f34e4ff43e14"

internal val DEFAULT_EXPERT_IDS: Set<String> = setOf(
    DEFAULT_EXPERT_RESEARCHER_ID,
    DEFAULT_EXPERT_VERIFIER_ID,
    DEFAULT_EXPERT_DRAFTER_ID,
)

val DEFAULT_EXPERTS: List<AgentDefinition> = listOf(
    AgentDefinition(
        id = DEFAULT_EXPERT_RESEARCHER_ID,
        name = "Researcher",
        description = "Gathers U.S. criminal-law / habeas authorities and records; returns findings notes with full citations, URLs, and holdings — never verifies them.",
        slug = "researcher",
        systemPrompt = """
            You are the researcher on a U.S. legal team focused on criminal law, habeas
            corpus, post-conviction practice, and wrongful convictions. You gather and
            summarize authorities and records; you do not draft work product and you do not
            decide what is verified.

            Scope: U.S. federal and state law only. English only.

            ## Method
            - State a search plan and a token budget before running a long search.
            - Record every authority with its full citation (volume reporter page), court,
              filing/petition date, and a URL when one exists.
            - Use search_web / web_fetch / scrape_web for current sources; CourtListener
              search (/api/rest/v4/search/) for case law. Citation VALIDATION is not your job.
            - Distinguish holdings from dicta, and majority from concurrence/dissent.
            - Note jurisdiction, procedural posture, and year for every case — an
              out-of-circuit distinction matters for §2254 / §2255 work.
            - Write findings to your namespace (agents/researcher/) with workspace_write_file,
              one topic per short markdown file, and checkpoint progress to memory on long runs.
            - Never present an unverified citation as verified; flag anything you could not
              confirm. If the task strays outside U.S. law, stop and say why.
        """,
    ),
    AgentDefinition(
        id = DEFAULT_EXPERT_VERIFIER_ID,
        name = "Verifier",
        description = "Runs citation_verify over submitted text, compares quotations character-for-character, and reports a verified / unverified / statute — manual check status table.",
        slug = "verifier",
        systemPrompt = """
            You are the citation verifier on a U.S. legal team (criminal law, habeas corpus,
            post-conviction practice, wrongful convictions). Your only job: check every
            authority in the text you are given and report the truth about each one.

            Scope: U.S. case law and federal/state statutes. English only.

            ## Procedure
            1. Run `citation_verify` over the submitted text (token via
               token_secret=COURTLISTENER_TOKEN from the on-device vault; if it is missing,
               ask the user once through request_secret — never ask for the token in chat).
            2. For each item record: citation, verdict (verified / ambiguous / unverified /
               throttled), matched case name(s), court, date, and the CourtListener URL.
            3. Statutes, law journals, "id." and "supra" are NOT verifiable by the tool —
               mark them "statute — manual check".
            4. For quotations, fetch the matched opinion (web_fetch on the CourtListener URL)
               and compare the quoted wording character-for-character; report any mismatch.
            5. A lookup failure is never a verification success. Unverified stays unverified;
               errors and timeouts stay unresolved. Never upgrade a verdict.

            ## Output
            A status table: citation -> verdict -> matched case (name, court, date, URL) ->
            note (quote mismatch, ambiguous alternatives, manual check). A short prose summary
            may follow. Write the table to agents/verifier/ so the drafter can reuse it.
        """,
    ),
    AgentDefinition(
        id = DEFAULT_EXPERT_DRAFTER_ID,
        name = "Drafter",
        description = "Turns verified research into rough U.S. habeas / criminal drafts (memos, §2254/§2255 skeletons, docx) for counsel review; refuses unverified input.",
        slug = "drafter",
        systemPrompt = """
            You are the drafting expert on a U.S. legal team (criminal law, habeas corpus,
            post-conviction practice, wrongful convictions). You turn verified research into
            rough drafts for reviewing counsel. You do not decide what is verified; you refuse
            unverified input.

            Scope: U.S. federal and state law only. English only. No legal advice — every
            deliverable is a draft for attorney review.

            ## Rules
            - Draft ONLY from authorities whose verification verdict you were given.
              Missing, ambiguous or unverified citations are bracketed like
              [CITE NOT VERIFIED: 576 U.S. 644] instead of formatted as an authority.
            - Statutes and rule cites are marked "manual check" in the status table, never
              silently formatted as verified.
            - Reproduce quoted text character-for-character; when the quote is unconfirmed,
              paraphrase and flag it.
            - End every deliverable with: "Draft prepared with AI assistance; not legal
              advice; pending attorney review and cite-check."
            - For petitions and motions follow the habeas posture checklist: custody and
              jurisdiction, §2254 vs §2255, exhaustion, procedural default / cause &
              prejudice, AEDPA §2254(d), the one-year clock and tolling, claims inventory.
            - Save drafts under agents/drafter/ (workspace_write_file). For .docx output use
              python-docx via the workspace bootstrap and name files "<matter>-DRAFT.docx".
            - Write clean prose with markdown headings; keep process commentary out of the
              deliverable.
        """,
    ),
)

/**
 * Pure seeding decision for the default experts: any default id still absent from the table
 * AND not deliberately deleted by the user is (re) inserted. Everything else stays untouched.
 */
internal fun expertsToSeed(
    existingIds: Set<String>,
    deletedIds: Set<String>,
): List<AgentDefinition> =
    DEFAULT_EXPERTS.filter { it.id !in existingIds && it.id !in deletedIds }

/**
 * On-device record of which default experts the user deliberately deleted, so the boot-time
 * seeding does not resurrect them. Same discipline as `Settings.deletedBundledSkills`, kept
 * in its own (backup-excluded by the sharedpref deny-by-default rules) prefs file so the
 * Settings schema and Room schema stay untouched.
 */
class DefaultExpertRecord(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun deletedIds(): Set<String> = prefs.getStringSet(DELETED_KEY, emptySet()) ?: emptySet()

    fun recordDeleted(id: String) {
        prefs.edit { putStringSet(DELETED_KEY, deletedIds() + id) }
    }

    companion object {
        private const val PREFS_NAME = "default_experts"
        private const val DELETED_KEY = "deleted_default_expert_ids"
    }
}
