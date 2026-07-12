package com.example.goforitGit.feature.challenges.model

/**
 * Client-side models for the Personal Challenges feature.
 *
 * These are plain, immutable value types. The Android client NEVER computes
 * targets, rewards, baselines, progress or completion — every value here is a
 * faithful copy of what the server-authoritative Cloud Functions returned.
 *
 * Parsing is defensive: unknown / missing fields fall back to safe defaults so
 * a malformed payload can never crash the screen.
 */

enum class ChallengeType(val wire: String) {
    RAISE_BASELINE("raise_baseline"),
    STUDY_BREAK_BOOST("study_break_boost"),
    CAMPUS_EXPLORER("campus_explorer");

    companion object {
        fun fromWire(value: String?): ChallengeType? =
            entries.firstOrNull { it.wire == value }
    }
}

enum class ChallengeDifficulty(val wire: String) {
    EASY("easy"),
    MEDIUM("medium"),
    HARD("hard");

    companion object {
        fun fromWire(value: String?): ChallengeDifficulty? =
            entries.firstOrNull { it.wire == value }
    }
}

enum class ChallengeStatus(val wire: String) {
    AVAILABLE("available"),
    ACTIVE("active"),
    COMPLETED("completed"),
    EXPIRED("expired");

    companion object {
        fun fromWire(value: String?): ChallengeStatus =
            entries.firstOrNull { it.wire == value } ?: ACTIVE
    }
}

/** A single Raise-Your-Baseline difficulty tier as computed by the server. */
data class DifficultyTier(
    val difficulty: ChallengeDifficulty,
    val percent: Double,
    val targetSteps: Int,
    val reward: Int,
)

/** Raise Your Baseline offer. */
data class RaiseBaselineOffer(
    val available: Boolean,
    val reason: String?,
    val baselineSteps: Int,
    val tiers: List<DifficultyTier>,
)

/** Study-Break Boost offer. */
data class StudyBreakOffer(
    val available: Boolean,
    val reason: String?,
    val selectedIntervalIndex: Int?,
    val selectedIntervalLabel: String?,
    val goalSteps: Int,
    val reward: Int,
)

/** Campus Explorer offer. */
data class CampusExplorerOffer(
    val available: Boolean,
    val reason: String?,
    val goalSteps: Int,
    val stationGoal: Int,
    val reward: Int,
)

/** The three offers shown when the user has not yet chosen a challenge today. */
data class ChallengeOffers(
    val raiseBaseline: RaiseBaselineOffer,
    val studyBreak: StudyBreakOffer,
    val campusExplorer: CampusExplorerOffer,
)

/** The user's chosen challenge for today (active, completed or expired). */
data class ActiveChallenge(
    val type: ChallengeType,
    val difficulty: ChallengeDifficulty?,
    val status: ChallengeStatus,
    val rewardPoints: Int,
    val baselineSteps: Int,
    val targetSteps: Int,
    val progressSteps: Int,
    val selectedIntervalIndex: Int?,
    val selectedIntervalLabel: String?,
    /** Study-Break window bounds (epoch ms); 0 when not a windowed challenge. */
    val windowStartMs: Long,
    val windowEndMs: Long,
    val stationGoal: Int,
    val stationVisitQualified: Boolean,
    val rewardGranted: Boolean,
    val acceptedAtMs: Long,
    val completedAtMs: Long,
) {
    val isCompleted: Boolean get() = status == ChallengeStatus.COMPLETED
    val isExpired: Boolean get() = status == ChallengeStatus.EXPIRED
}

/**
 * Full canonical state returned by getMyPersonalChallenges / acceptPersonalChallenge.
 *
 * Exactly one of [active] / [offers] is normally non-null:
 *  - [active] present  -> a challenge was chosen today.
 *  - [offers] present  -> nothing chosen yet; show the three choices.
 */
data class PersonalChallengesState(
    val dayKey: String,
    val timezone: String,
    val serverNowMs: Long,
    val midnightMs: Long,
    val active: ActiveChallenge?,
    val offers: ChallengeOffers?,
) {
    companion object {

        private fun asMap(value: Any?): Map<*, *>? = value as? Map<*, *>
        private fun asInt(value: Any?, def: Int = 0): Int = (value as? Number)?.toInt() ?: def
        private fun asLong(value: Any?, def: Long = 0L): Long = (value as? Number)?.toLong() ?: def
        private fun asDouble(value: Any?, def: Double = 0.0): Double =
            (value as? Number)?.toDouble() ?: def
        private fun asBool(value: Any?, def: Boolean = false): Boolean =
            value as? Boolean ?: def
        private fun asString(value: Any?): String? = value as? String

        fun fromMap(map: Map<*, *>): PersonalChallengesState {
            val activeMap = asMap(map["active"])
            val offersMap = asMap(map["offers"])

            return PersonalChallengesState(
                dayKey = asString(map["dayKey"]) ?: "",
                timezone = asString(map["timezone"]) ?: "Asia/Jerusalem",
                serverNowMs = asLong(map["serverNowMs"]),
                midnightMs = asLong(map["midnightMs"]),
                active = activeMap?.let { parseActive(it) },
                offers = offersMap?.let { parseOffers(it) },
            )
        }

        private fun parseActive(m: Map<*, *>): ActiveChallenge? {
            val type = ChallengeType.fromWire(asString(m["challengeType"])) ?: return null
            return ActiveChallenge(
                type = type,
                difficulty = ChallengeDifficulty.fromWire(asString(m["difficulty"])),
                status = ChallengeStatus.fromWire(asString(m["status"])),
                rewardPoints = asInt(m["rewardPoints"]),
                baselineSteps = asInt(m["baselineSteps"]),
                targetSteps = asInt(m["targetSteps"]),
                progressSteps = asInt(m["progressSteps"]),
                selectedIntervalIndex = (m["selectedIntervalIndex"] as? Number)?.toInt(),
                selectedIntervalLabel = asString(m["selectedIntervalLabel"]),
                windowStartMs = asLong(m["windowStartMs"]),
                windowEndMs = asLong(m["windowEndMs"]),
                stationGoal = asInt(m["stationGoal"], 1),
                stationVisitQualified = asBool(m["stationVisitQualified"]),
                rewardGranted = asBool(m["challengeRewardGranted"]),
                acceptedAtMs = asLong(m["acceptedAtMs"]),
                completedAtMs = asLong(m["completedAtMs"]),
            )
        }

        private fun parseOffers(m: Map<*, *>): ChallengeOffers {
            return ChallengeOffers(
                raiseBaseline = parseRaiseBaseline(asMap(m["raise_baseline"])),
                studyBreak = parseStudyBreak(asMap(m["study_break_boost"])),
                campusExplorer = parseCampusExplorer(asMap(m["campus_explorer"])),
            )
        }

        private fun parseRaiseBaseline(m: Map<*, *>?): RaiseBaselineOffer {
            if (m == null) {
                return RaiseBaselineOffer(false, null, 0, emptyList())
            }
            val diffsMap = asMap(m["difficulties"])
            val tiers = mutableListOf<DifficultyTier>()
            for (d in ChallengeDifficulty.entries) {
                val tierMap = asMap(diffsMap?.get(d.wire)) ?: continue
                tiers += DifficultyTier(
                    difficulty = d,
                    percent = asDouble(tierMap["percent"]),
                    targetSteps = asInt(tierMap["targetSteps"]),
                    reward = asInt(tierMap["reward"]),
                )
            }
            return RaiseBaselineOffer(
                available = asBool(m["available"]),
                reason = asString(m["reason"]),
                baselineSteps = asInt(m["baselineSteps"]),
                tiers = tiers,
            )
        }

        private fun parseStudyBreak(m: Map<*, *>?): StudyBreakOffer {
            if (m == null) {
                return StudyBreakOffer(false, null, null, null, 800, 60)
            }
            return StudyBreakOffer(
                available = asBool(m["available"]),
                reason = asString(m["reason"]),
                selectedIntervalIndex = (m["selectedIntervalIndex"] as? Number)?.toInt(),
                selectedIntervalLabel = asString(m["selectedIntervalLabel"]),
                goalSteps = asInt(m["goalSteps"], 800),
                reward = asInt(m["reward"], 60),
            )
        }

        private fun parseCampusExplorer(m: Map<*, *>?): CampusExplorerOffer {
            if (m == null) {
                return CampusExplorerOffer(false, null, 1200, 1, 100)
            }
            return CampusExplorerOffer(
                available = asBool(m["available"]),
                reason = asString(m["reason"]),
                goalSteps = asInt(m["goalSteps"], 1200),
                stationGoal = asInt(m["stationGoal"], 1),
                reward = asInt(m["reward"], 100),
            )
        }
    }
}