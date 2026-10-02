package com.danilkinkin.buckwheat.family

/**
 * Assigns a stable pseudonym to each member, keyed by member id.
 *
 * A family's AI summary talks about people, and the provider is a third party the user configured but
 * neither of us controls. Sending "Ananya spent 4,200 on groceries this month" is not the same act as
 * sending "Member B spent 4,200 on groceries this month": the first hands over a name, a number and a
 * category together, and from three months of those a provider can build a profile of a specific
 * person. The second hands over a share of a household and cannot, however many months accumulate.
 *
 * Keyed by member id rather than display name on purpose: the display name is the one thing this file
 * must never handle, so taking a list of them as input and then discarding them would be an odd way to
 * keep a promise. The order is the roster's own, and that is what makes "Member B" mean the same
 * person from one summary to the next.
 */
fun familyPseudonyms(memberIdsInRosterOrder: List<String>): Map<String, String> =
    memberIdsInRosterOrder
        .distinct()
        .mapIndexed { index, id -> id to pseudonymFor(index) }
        .toMap()

private val LETTERS = ('A'..'Z').map { it.toString() }

private fun pseudonymFor(index: Int): String = if (index < LETTERS.size) {
    "Member ${LETTERS[index]}"
} else {
    "Member ${index / LETTERS.size + 1}${LETTERS[index % LETTERS.size]}"
}

/** The household's figures, with every person already reduced to a pseudonym. */
data class PseudonymousFamilySnapshot(
    val lines: List<String>,
)

/**
 * Builds the text a family summary is written from.
 *
 * Pure, so the thing that decides what leaves the device can be tested without a network, a model or
 * a DataStore. Takes member ids and never display names, so there is no code path here by which a
 * name could reach the provider even if a future caller helpfully passed one.
 */
fun buildFamilySnapshot(
    budget: FamilyBudget,
    memberIdsInRosterOrder: List<String>,
    tags: Map<String, MemberTag> = emptyMap(),
): PseudonymousFamilySnapshot {
    val pseudonyms = familyPseudonyms(memberIdsInRosterOrder)
    val lines = mutableListOf<String>()

    lines += "Household pool ${budget.total} in total, of which ${budget.householdTier} is set aside for shared costs."
    lines += "Shared costs so far: ${budget.householdSpent}."

    budget.allocations.forEach { allocation ->
        val who = pseudonyms[allocation.memberId] ?: "Member ?"
        val tag = tags[allocation.memberId]
        val tagText = if (tag == null || tag == MemberTag.NOT_ENOUGH_DATA) {
            ""
        } else {
            " (${tag.name.lowercase().replace('_', ' ')})"
        }
        lines += "$who was given ${allocation.allocation}, has spent ${allocation.spent}, " +
            "and carries ${allocation.shareOfHousehold} of the shared costs, leaving ${allocation.remaining}$tagText."
    }

    lines += "Household remaining: ${budget.remaining}."
    return PseudonymousFamilySnapshot(lines)
}

/**
 * The prompt sent to the user's own AI provider.
 *
 * States the pseudonym rule inside the prompt itself, because a model asked to comment on "Member B"
 * will otherwise be tempted to guess, and the guess would be based on the numbers rather than the
 * name -- which is the point of the pseudonym, not an accident to be tolerated.
 *
 * No member name, display name, or member id appears anywhere in here or in [buildFamilySnapshot]'s
 * output. That is the invariant `FamilySnapshotPrivacyTest` exists to hold.
 */
fun familySummaryPrompt(snapshot: PseudonymousFamilySnapshot): String = buildString {
    appendLine(
        "You are helping a household understand a shared budget. The people in it are referred to " +
            "only as Member A, Member B and so on. Do not guess, infer or invent real names, and do not " +
            "speculate about who a member is."
    )
    appendLine()
    appendLine("The figures for this period are:")
    snapshot.lines.forEach { appendLine("- $it") }
    appendLine()
    append(
        "In three or four sentences, say where the household stands this period and what looks worth " +
            "attention. Be plain and specific about the numbers. Do not comment on anyone's character, " +
            "and do not give advice about what anyone should buy."
    )
}

/**
 * The same summary without a model, for when there is no key, no network, or AI is switched off.
 *
 * Deterministic and produced from the same figures, so the family screen shows real content in every
 * case and the AI version is an upgrade rather than the only way to see anything. This is the same
 * arrangement as the monthly report, and for the same reason: a feature that renders nothing offline
 * reads as broken to anyone on a train.
 */
fun offlineFamilySummary(snapshot: PseudonymousFamilySnapshot): String {
    val spent = snapshot.lines.filter { it.startsWith("Member ") }
    val household = snapshot.lines.firstOrNull { it.startsWith("Shared costs") }
    val left = snapshot.lines.lastOrNull { it.startsWith("Household remaining") }
    return buildString {
        append(household?.removePrefix("Shared costs so far: ").orEmpty())
        append(" spent on shared costs. ")
        append(left?.removePrefix("Household remaining: ").orEmpty())
        append(" left. ")
        append("${spent.size} members have an allocation this period.")
    }
}