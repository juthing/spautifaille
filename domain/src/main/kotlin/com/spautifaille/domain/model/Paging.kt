package com.spautifaille.domain.model

/**
 * Jeton de pagination opaque. La couche data fournit sa propre implémentation
 * (qui encapsule par exemple une `Page` NewPipe) ; le reste de l'app ne fait que le transmettre.
 */
interface PageToken

data class Paged<T>(
    val items: List<T>,
    val next: PageToken?,
) {
    val hasMore: Boolean get() = next != null
}
