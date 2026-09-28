package com.spautifaille.domain.di

import javax.inject.Qualifier

/** Dispatcher pour les E/S (réseau, disque). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** Dispatcher pour le calcul (parsing, scoring). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/** CoroutineScope applicatif (SupervisorJob), vit aussi longtemps que le process. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
