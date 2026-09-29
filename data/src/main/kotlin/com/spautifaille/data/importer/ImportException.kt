package com.spautifaille.data.importer

/**
 * Échec de lecture d'une source d'import, avec un message lisible (français) destiné à l'utilisateur.
 * [ImportRepositoryImpl] la convertit en `AppException(AppError.Unknown(message))` pour l'UI.
 */
class ImportException(message: String, cause: Throwable? = null) : Exception(message, cause)
