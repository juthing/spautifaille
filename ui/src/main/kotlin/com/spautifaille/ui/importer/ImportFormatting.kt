package com.spautifaille.ui.importer

import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import com.spautifaille.domain.importer.ImportJob
import com.spautifaille.domain.importer.ImportJobState
import com.spautifaille.domain.importer.ImportedTrack
import com.spautifaille.ui.R
import com.spautifaille.ui.common.UiText
import com.spautifaille.ui.common.toMessage
import com.spautifaille.ui.components.formatDuration
import kotlin.math.roundToInt

/** « Artiste 1, Artiste 2 · 3:25 » : artistes et durée du titre importé, sans les parties absentes. */
fun ImportedTrack.subtitle(): String =
    listOfNotNull(
        artists.filter { it.isNotBlank() }.joinToString(", ").ifEmpty { null },
        durationMs?.takeIf { it > 0 }?.let(::formatDuration),
    ).joinToString(" · ")

/** Score de correspondance en pourcentage entier (0 à 100). */
fun scorePercent(score: Double): Int = (score * 100).roundToInt().coerceIn(0, 100)

/** Progression du job dans [0, 1]. */
val ImportJob.progress: Float
    get() = if (total <= 0) 1f else (processed.toFloat() / total).coerceIn(0f, 1f)

val ImportJob.isRunning: Boolean get() = state == ImportJobState.RUNNING

/**
 * Message pour une erreur d'import. Les importers produisent des messages lisibles (français) sous forme
 * d'`AppError.Unknown(detail)` : on les affiche tels quels ; toute autre exception donne un message générique.
 */
fun Throwable.toImportMessage(): UiText {
    val error = (this as? AppException)?.error ?: return UiText.of(R.string.import_error_generic)
    return if (error is AppError.Unknown && !error.detail.isNullOrBlank()) {
        UiText.Plain(error.detail!!)
    } else {
        UiText.of(error.toMessage())
    }
}

/** Étape d'un import vue par l'utilisateur (la lecture de la source est faite dès que le job existe). */
enum class ImportStage { MATCHING, NEEDS_REVIEW, DONE, FAILED }

fun ImportJob.stage(): ImportStage = when (state) {
    ImportJobState.RUNNING -> ImportStage.MATCHING
    ImportJobState.FAILED -> ImportStage.FAILED
    ImportJobState.COMPLETED -> if (needsReview + notFound > 0) ImportStage.NEEDS_REVIEW else ImportStage.DONE
}

enum class StepState { DONE, CURRENT, FAILED, PENDING }

/** État des trois étapes affichées (lecture, correspondance, vérification) pour [stage]. */
fun stepStates(stage: ImportStage): List<StepState> = when (stage) {
    ImportStage.MATCHING -> listOf(StepState.DONE, StepState.CURRENT, StepState.PENDING)
    ImportStage.NEEDS_REVIEW -> listOf(StepState.DONE, StepState.DONE, StepState.CURRENT)
    ImportStage.DONE -> listOf(StepState.DONE, StepState.DONE, StepState.DONE)
    ImportStage.FAILED -> listOf(StepState.DONE, StepState.FAILED, StepState.PENDING)
}

/** Retour haptique à donner quand un job passe de [previous] à [current] ; `null` si rien à signaler. */
enum class JobFeedback { CONFIRM, REJECT }

fun jobFeedback(previous: ImportJobState?, current: ImportJobState): JobFeedback? {
    if (previous != ImportJobState.RUNNING) return null
    return when (current) {
        ImportJobState.COMPLETED -> JobFeedback.CONFIRM
        ImportJobState.FAILED -> JobFeedback.REJECT
        ImportJobState.RUNNING -> null
    }
}
