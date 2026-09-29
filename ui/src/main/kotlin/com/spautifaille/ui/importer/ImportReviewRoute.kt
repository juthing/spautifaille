package com.spautifaille.ui.importer

import kotlinx.serialization.Serializable

/**
 * Écran de revue d'un import. L'argument `jobId` est lu par `ImportReviewViewModel` via
 * `SavedStateHandle["jobId"]` (les routes typées y déposent leurs champs sous leur nom).
 * À enregistrer dans le NavHost : `composable<ImportReviewRoute> { ImportReviewScreenRoot(onBack = navController::navigateUp) }`.
 */
@Serializable
data class ImportReviewRoute(val jobId: Long)
