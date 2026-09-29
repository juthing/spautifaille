package com.spautifaille.data.di

import com.spautifaille.data.recommendation.DiscoveryRepositoryImpl
import com.spautifaille.data.recommendation.YouTubeRelatedSource
import com.spautifaille.domain.recommendation.DiscoveryRepository
import com.spautifaille.domain.recommendation.RecommendationSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Découverte : dépôt + multibinding des sources de similarité (`Set<RecommendationSource>`). */
@Module
@InstallIn(SingletonComponent::class)
abstract class RecommendationModule {
    @Binds abstract fun bindDiscoveryRepository(impl: DiscoveryRepositoryImpl): DiscoveryRepository

    @Binds @IntoSet abstract fun bindYouTubeRelatedSource(impl: YouTubeRelatedSource): RecommendationSource
}
