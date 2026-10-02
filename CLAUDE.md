# Spautifaille

Lecteur audio Android natif en streaming, alimenté par YouTube via NewPipeExtractor. Usage personnel, distribué en APK (pas de Play Store). Licence GPL-3.0 (imposée par NewPipeExtractor).

Priorités : robustesse, architecture propre, intégration système parfaite, Material 3 strict.

## Stack (versions dans `gradle/libs.versions.toml`, vérifiées le 2026-09-28)
- AGP 9.4.1 avec **Kotlin intégré** : ne jamais appliquer `org.jetbrains.kotlin.android`, pas de kapt (KSP uniquement), `kotlin { compilerOptions {} }`
- Gradle 9.6.0, JDK 21 (bytecode 17), Kotlin 2.4.20, KSP 2.3.12
- compileSdk/targetSdk 37, minSdk 26, core library desugaring (`desugar_jdk_libs_nio`, requis par NewPipe)
- Compose BOM 2026.09.00 (Material 3 1.4.0), `material-icons-extended` (**toujours `Icons.Filled`**), navigation-suite, Navigation Compose 2.10 (routes typées `@Serializable`)
- Media3 1.11.1, Room 2.8.5, Hilt 2.60.1, WorkManager 2.12.0, DataStore 1.2.1, Coil 3.6.3, OkHttp 5.5.0, material-color-utilities 5.0.1 (thème « Musique en cours » : HCT, `SchemeTonalSpot`, quantification Celebi)
- NewPipeExtractor : commit épinglé (voir « Mettre à jour NewPipeExtractor »)

## Modules
```
:app     Application Hilt, MainActivity, câblage DI final (bind des interfaces domain), init NewPipe, manifeste
:domain  Kotlin/JVM pur. Modèles, interfaces (repositories, PlaybackController, PlaylistImporter, RecommendationSource),
         use cases, matching d'import (TrackMatcher), filtrage des recommandations. AUCUNE dépendance Android/NewPipe.
:data    Implémentations : newpipe/ (Downloader OkHttp, StreamRepository), local/ (Room, DataStore),
         importer/ (parsers CSV/JSON, importers), download/ (WorkManager), recommendation/
:player  PlaybackService (MediaLibraryService), ExoPlayer, data sources (résolution tardive, cache, hors ligne),
         notification, Android Auto, persistance de la file, implémentation de PlaybackController (MediaController)
:ui      Thème M3 dynamique, écrans Compose, ViewModels. Dépend uniquement de :domain (+ :player pour rien d'autre
         que des types si nécessaire : préférer l'interface PlaybackController du domain)
```
Règles de dépendance : `app → ui, player, data, domain` ; `ui → domain` ; `player → domain` ; `data → domain`.
Plugins de convention dans `build-logic/` : `spautifaille.android.application|library|compose`, `spautifaille.hilt`, `spautifaille.jvm.library`.

## Commandes
```bash
./gradlew assembleDebug                 # APK debug : app/build/outputs/apk/debug/
./gradlew testDebugUnitTest             # tests JVM Android (Robolectric inclus)
./gradlew :domain:test                  # tests du module Kotlin pur
./gradlew lintDebug
./gradlew :data:testDebugUnitTest --tests "*ExportifyParserTest*"
```
SDK Android : `local.properties` → `sdk.dir=/root/android-sdk` (installé par `.claude/hooks/session-start.sh` en session web).
Pas d'émulateur dans le conteneur : Room/migrations/Media3 se testent via Robolectric en tests unitaires.
Machine limitée (4 CPU / 15 Go) : en parallèle, utiliser `-Dorg.gradle.jvmargs=-Xmx2g -Pkotlin.compiler.execution.strategy=in-process --max-workers=2`. **Ne jamais lancer `./gradlew --stop` quand d'autres builds tournent** (tue les daemons de tous les worktrees).
Tests réseau réels contre YouTube (désactivés par défaut) : `SPAUTIFAILLE_LIVE_TESTS=1 ./gradlew :data:testDebugUnitTest --tests "*LiveYoutubeTest*"`.
Robolectric 4.17 : épingler `sdk = 35` (SDK 37 échoue sur JDK 21).

CI : `.github/workflows/android.yml` (tests, lint, APK debug + release en artefacts téléchargeables).

## Conventions
- **UDF** : ViewModel expose un `StateFlow<XxxUiState>` (+ éventuellement un `Flow` d'événements one-shot) ; les composables reçoivent l'état et des lambdas. Aucune logique métier dans les composables.
- Écrans : `XxxRoute` (récupère le VM via `hiltViewModel()`, collecte avec `collectAsStateWithLifecycle`) + `XxxScreen` (stateless, prévisualisable).
- Material 3 strict : `Scaffold`, `TopAppBar`/`LargeTopAppBar`, `NavigationSuiteScaffold`, `ModalBottomSheet`, `ListItem`, cartes. Couleurs dynamiques, clair/sombre. Pas de couleurs en dur hors thème.
- Erreurs : les repositories lèvent `AppException(AppError)` ; `:data` mappe les exceptions NewPipe/réseau. Messages utilisateur dans `:ui` (`AppError.toMessage()`), en français.
- Coroutines : injecter `@IoDispatcher` / `@DefaultDispatcher` / `@ApplicationScope` (`domain/di/Qualifiers.kt`), ne pas coder `Dispatchers.IO` en dur dans les classes testables.
- **Ne jamais persister une URL de flux** (`ResolvedStream`) : elles expirent. Les `MediaItem` utilisent l'URI stable `spautifaille://track/<videoId>`.
- NewPipeExtractor n'est importé que dans `:data` (package `newpipe`) et, pour les helpers d'en-têtes (User-Agent VisionOS), dans `:player` via une abstraction si possible.
- Room : `exportSchema = true` (`data/schemas`), **toute évolution de schéma = nouvelle version + `Migration` explicite + test `MigrationTestHelper`**. Pas de `fallbackToDestructiveMigration`.
- Media3 : beaucoup d'API sont `@UnstableApi` → `@OptIn(UnstableApi::class)` localisé sur la classe concernée.
- Chaînes UI en français, **tutoiement partout** (app personnelle), réparties par zone : `strings.xml` (navigation, actions et erreurs communes, recherche, lecteur) + `strings_<zone>.xml` (`home`, `library`, `settings`, `import`, `downloads`, `discovery`, `player`, `misc`). Pas de chaîne inutilisée : vérifier par grep avant d'en laisser une.
- Retours haptiques : toujours via `LocalAppHaptics.current` (`ui/common/Haptics.kt`, sémantique `click`/`toggle`/`confirm`/`reject`/`longPress`/`tick`…), jamais `LocalHapticFeedback` directement. Les composants partagés (`TrackListItem`…) le font déjà : ne pas le doubler chez l'appelant ; jamais à chaque frame.
- Espacements, largeurs et tailles dans `ui/theme/Dimens.kt` (`Spacing`, `ScreenHorizontalPadding`, `SectionSpacing`, `ListBottomPadding`, `ContentMaxWidth`, `ArtworkSize`) plutôt que des `dp` en dur. États vides / erreur / chargement : `EmptyState`, `ErrorState`, `LoadingState` (`ui/components/States.kt`).
- En-têtes d'écran : `TopAppBar` standard (jamais `LargeTopAppBar` : grand blanc en haut). Navigation : 3 onglets racine (Accueil, Bibliothèque, Réglages) ; la recherche est un écran poussé depuis l'Accueil (`SearchRoute`).
- Tests : JUnit4 + kotlinx-coroutines-test + Turbine + MockK ; Robolectric pour Room/Android. Tests obligatoires : parsers d'import, scoring du matching, use cases, filtrage des recommandations, DAO + migrations.

## Architecture de lecture
- `PlaybackService` (MediaLibraryService, foreground `mediaPlayback`) possède l'ExoPlayer et la MediaSession. L'UI passe par `PlaybackController` (MediaController) : le player ne vit jamais dans l'UI.
- Chaîne de data sources : `CacheDataSource(SimpleCache)` → `ResolvingDataSource` (fichier téléchargé prioritaire, sinon `StreamRepository.resolveAudio`, cache TTL mémoire invalidé sur 403) → `YoutubeHttpDataSource` (portage Media3 de celui de NewPipe : User-Agent VisionOS, POST `{0x78,0x00}`, `&range=` au lieu de l'en-tête Range, `&rn=`).
- Focus audio + becoming noisy gérés par ExoPlayer ; « précédent » = redémarrage si > 3 s (comportement natif de `seekToPrevious`).
- Bouton like dans la notification via `setMediaButtonPreferences` + `SessionCommand` custom.
- Reprise : file persistée (`QueueStateStore`) + `onPlaybackResumption`.

## Reconnaissance musicale (bouton de la recherche)
- `:domain/recognition` : `MusicRecognizer`, `AudioCapture`, `RecognizeMusicUseCase` (tentatives à 4 s, 8 s puis 12 s d'audio, arrêt à la première correspondance). `:data/recognition` : `SignatureGenerator` + `SignatureFormat` (portage Kotlin de l'algorithme de **SongRec**, GPL-3.0, en-têtes de provenance à conserver), `ShazamMusicRecognizer` (endpoint **non officiel** `amp.shazam.com/discovery/v5/...`, sans clé), `AudioRecordCapture` (16 kHz mono PCM16). `:ui` : `RecognitionViewModel` + `RecognitionSheet` ; permission `RECORD_AUDIO` demandée au clic seulement.
- Aucune URL/audio n'est persisté ; seule l'empreinte (signature) part vers Shazam, avec une géolocalisation fictive fixe (comme SongRec).
- Test de référence : `data/src/test/resources/recognition/ref_songrec.sig` a été produit par le code Rust de SongRec (mêmes pics que le portage Kotlin). Test réel : `SPAUTIFAILLE_LIVE_TESTS=1 ./gradlew :data:testDebugUnitTest --tests "*LiveShazamTest*"` (`SPAUTIFAILLE_LIVE_PCM=<fichier.pcm>` pour un vrai extrait).
- Si l'endpoint change ou disparaît : `AppError.RecognitionUnavailable` s'affiche, rien d'autre n'est impacté.

## Mettre à jour NewPipeExtractor
On suit le commit épinglé par l'app NewPipe (testé en production) :
1. Lire `https://raw.githubusercontent.com/TeamNewPipe/NewPipe/dev/gradle/libs.versions.toml` → clé `teamnewpipe-newpipe-extractor`.
2. Reporter le hash dans `newpipeExtractor` de `gradle/libs.versions.toml`.
   Puis copier `.pom`, `.module` et `.jar` depuis `https://jitpack.io/com/github/TeamNewPipe/NewPipeExtractor/<hash>/` dans `gradle/vendor-repo/` (même arborescence ; idem pour `nanojson` si son commit change) et supprimer l'ancienne version. JitPack renvoie parfois 404 aux runners GitHub : la CI ne dépend que de cette copie.
3. Comparer `DownloaderImpl.java` et `player/datasource/YoutubeHttpDataSource.java` de NewPipe avec nos portages.
4. Vérifier `app/proguard-rules.pro` de NewPipe (règles Rhino).

## Pièges connus
- YouTube : seul le client VisionOS est utilisé par l'extracteur (pas de poToken). Les URL `&c=VISIONOS` exigent le User-Agent VisionOS (`YoutubeParsingHelper.getVisionOsUserAgent`) ; sinon 403.
- `AudioStream.isUrl()==false` → contenu = manifeste DASH, pas une URL.
- Vidéos « made for kids » non lisibles ; `SignInConfirmNotBotException` / HTTP 429 = throttling IP (`AppError.BotDetected`).
- Le `Downloader` ne doit pas écraser les en-têtes fournis par la requête NewPipe (User-Agent spécifique) et doit renvoyer les réponses non-2xx sans lever (sauf 429 → `ReCaptchaException`).
- Recherche YouTube Music : les paramètres de filtre de NewPipeExtractor sont périmés → `data/src/main/java/.../PatchedYoutubeMusicSearchExtractor.java` (copie avec paramètres à jour). Titres/Albums peuvent renvoyer « aucun résultat » selon l'IP → repli automatique (Titres → vidéos YTM → vidéos, Albums → playlists) dans `NewPipeStreamRepository.search`. À retirer quand l'upstream corrige.
- Signature release : `app/build.gradle.kts` lit `SPAUTIFAILLE_KEYSTORE_PATH`, `SPAUTIFAILLE_KEYSTORE_PASSWORD`, `SPAUTIFAILLE_KEY_ALIAS`, `SPAUTIFAILLE_KEY_PASSWORD` (en CI : secrets GitHub, dont `SPAUTIFAILLE_KEYSTORE_BASE64` décodé en .jks) ; absentes → repli clé de debug + warning (APK non mettable à jour par-dessus). Ne jamais commiter de keystore.
- Gradle 9 échoue si un module a des sources de test mais aucun test découvert (`failOnNoDiscoveredTests`).
- JitPack ne sert que `com.github.*` (filtre dans `settings.gradle.kts`). Maven Central passe par le miroir Google (évite les 429).
