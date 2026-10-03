# Prompt : compte YouTube et synchronisation bidirectionnelle (IMPLÉMENTÉ)

> **Statut : implémenté** (voir la section « Compte YouTube / synchro » de `CLAUDE.md`). Ce prompt est conservé comme cahier des charges. Restent à vérifier sur appareil avec un vrai compte : la connexion WebView et les endpoints en conditions réelles.

Prompt prêt à donner à un agent de code (Claude Code) pour implémenter la connexion optionnelle à un compte
Google/YouTube et la synchronisation des likes, abonnements et playlists. Décisions déjà prises avec l'utilisateur :

- **API interne InnerTube (YouTube Music) avec les cookies de session**, comme InnerTune / Metrolist — pas l'API
  officielle YouTube Data v3 (qui exigerait un projet Google Cloud, OAuth et un quota de ~200 écritures/jour).
- Connexion **optionnelle** : sans compte, l'app fonctionne exactement comme aujourd'hui.
- Likes et abonnements synchronisés dans les deux sens ; playlists : on n'importe **que** celles choisies.
- Photo de profil ronde dans un coin de l'app.

Risques connus : API non documentée (peut casser quand YouTube change), contraire aux conditions d'utilisation de
YouTube (risque faible mais non nul pour le compte), la WebView de connexion Google peut être refusée.

---

Tu travailles sur Spautifaille, un lecteur audio Android personnel (Kotlin, Compose Material 3, Media3, Room, Hilt,
WorkManager, DataStore, OkHttp, GPL-3.0) qui lit YouTube via NewPipeExtractor, sans compte.

**Avant tout** : pars de la branche principale à jour, puis lis `CLAUDE.md` en entier (stack, modules, règles de
dépendance `ui → domain` uniquement, conventions UDF / Route+Screen / Material 3 strict / `Icons.Filled` / chaînes FR /
`AppException(AppError)` / dispatchers injectés / **Room : toute évolution de schéma = nouvelle version + `Migration`
explicite + test `MigrationTestHelper`, pas de destructive migration**).

Machine limitée : lance Gradle avec `-Dorg.gradle.jvmargs=-Xmx2g -Pkotlin.compiler.execution.strategy=in-process
--max-workers=2` si d'autres builds tournent, et ne lance jamais `./gradlew --stop`. Si Maven Central renvoie 429,
utilise un init script hors dépôt qui place le miroir Google en premier. Tu DOIS faire des recherches web et lire le
code source des références ci-dessous avant d'écrire le code réseau : ne devine jamais une structure JSON InnerTube
de mémoire.

## Objectif : connexion optionnelle à un compte Google/YouTube et synchronisation bidirectionnelle

### Références (lis leur code actuel)
- **ytmusicapi** (Python, référence de fait, MIT) : https://github.com/sigma67/ytmusicapi
  - `ytmusicapi/helpers.py` : `get_authorization`, `sapisid_from_cookie` (cookie `__Secure-3PAPISID`), en-tête
    `Authorization: SAPISIDHASH <ts>_<sha1(ts + " " + SAPISID + " " + origin)>`, contexte `WEB_REMIX` +
    `clientVersion "1.YYYYMMDD.01.00"`, en-têtes `X-Goog-AuthUser`, `X-Origin` / `Origin: https://music.youtube.com`.
  - `mixins/library.py` : `get_library_playlists`, `get_library_subscriptions`, `get_liked_songs`,
    `rate_song` → `like/like`, `like/removelike`, `like/dislike` ; `subscribe_artists` → `subscription/subscribe` /
    `subscription/unsubscribe` (channelIds) ; `get_account_info` → `account/account_menu`.
  - `mixins/playlists.py` : `get_playlist`, `create_playlist` → `playlist/create`, ajout / retrait / déplacement via
    `browse/edit_playlist` avec `ACTION_ADD_VIDEO`, `ACTION_REMOVE_VIDEO` (nécessite `setVideoId`),
    `ACTION_MOVE_VIDEO_BEFORE` (`setVideoId` + `movedSetVideoIdSuccessor`).
  - Les **parsers** (`parsers/`, `navigation.py`) donnent les chemins JSON exacts.
  - Docs : https://ytmusicapi.readthedocs.io/en/latest/
- **Metrolist** (Kotlin Android, GPL-3.0, compatible) : https://github.com/MetrolistGroup/Metrolist
  - Écran de connexion `app/src/main/kotlin/com/metrolist/music/ui/screens/LoginScreen.kt` : WebView sur
    `https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com`, cookies via
    `CookieManager.getInstance().getCookie("https://music.youtube.com")`, connexion valide quand le cookie `SAPISID`
    est présent, récupération de `window.yt.config_.VISITOR_DATA` et `DATASYNC_ID` via interface JavaScript
    (retry 20 × 500 ms), validation via `accountInfo()`.
  - Le module `innertube/` (et la couche « InnerTubeX ») contient les modèles Kotlin des réponses : explore-le pour
    les chemins JSON et l'usage de `dataSyncId` / `onBehalfOfUser`.
- **youtubei.js** (JS) en second avis sur la forme des réponses.
- Si Google bloque la WebView (« Ce navigateur n'est peut-être pas sécurisé »), regarde comment Metrolist / InnerTune
  contournent (User-Agent, etc.).

### Fonctionnalités à livrer

1. **Compte (optionnel)**
   - Réglages : entrée « Compte YouTube » : se connecter (écran WebView plein écran), état connecté (avatar, nom,
     e-mail/handle), « Synchroniser maintenant », dernière synchro, nombre d'actions en attente, option « Likes :
     synchroniser uniquement la musique » (désactivée par défaut → liste complète des vidéos aimées, playlist `LL` ;
     activée → `LM` « Musique likée » ; vérifier dans ytmusicapi/Metrolist comment lire LL vs LM), **mode diagnostic**
     affichant la dernière erreur de synchro (endpoint + message), se déconnecter (efface cookies WebView + stockage).
   - Identifiants chiffrés (Android Keystore + AES/GCM dans DataStore ; `security-crypto` est déprécié). Ne jamais
     logger les cookies.
   - **Avatar rond dans un coin de l'app** : en haut à droite des TopAppBar des écrans principaux (Accueil,
     Recherche, Bibliothèque) ; photo de profil Google (Coil, `CircleShape`, 32 dp) quand connecté, sinon
     `Icons.Filled.AccountCircle` qui mène à la connexion. Au toucher : menu de compte (nom, e-mail, « Synchronisé il
     y a X » / « N actions en attente », Synchroniser maintenant, Réglages du compte, Se déconnecter). Petit badge
     d'erreur si la dernière synchro a échoué.

2. **Likes bidirectionnels** : aimer / ne plus aimer dans l'app → `like/like` / `like/removelike` ; à la synchro,
   importer les likes YouTube dans les « Titres likés » locaux et inversement. Réconciliation **à trois voies** avec
   un instantané du dernier état synchronisé. Au premier lien du compte : union des deux ensembles (ne rien supprimer).
   Note : « Musique likée » (LM) de YouTube Music est une vue filtrée de « Vidéos J'aime » (LL) qui exclut les vidéos
   non musicales.

3. **Abonnements bidirectionnels** : s'abonner / se désabonner dans l'app ↔ YouTube (`subscription/subscribe` /
   `unsubscribe` avec channelId), et importer les abonnements YouTube dans l'onglet Artistes. Les abonnements locaux
   sont indexés par l'URL de la chaîne (`Artist.url`) : extraire / normaliser le channelId (`UC...`). Même
   réconciliation à trois voies.

4. **Playlists** : ne PAS tout importer automatiquement. Dans le flux d'ajout / import de playlist existant, ajouter
   une **3ᵉ option « Importer depuis YouTube »** visible seulement si connecté : liste des playlists du compte
   (vignette, nom, nombre de titres) avec cases à cocher, puis import des playlists choisies, qui deviennent des
   **playlists liées** (id de playlist YouTube + `setVideoId` des entrées). Pour une playlist liée, synchro
   bidirectionnelle : ajout / suppression / réordonnancement locaux → `browse/edit_playlist`, et changements distants
   récupérés à la synchro (trois voies sur la liste ordonnée : ajouts/suppressions des deux côtés ; pour l'ordre, si
   un seul côté a changé prendre ce côté, sinon le distant). Les actions groupées de la sélection multiple passent par
   le même chemin. Badge « Synchronisée avec YouTube » et option « Délier ». Facultatif : « Publier sur YouTube » pour
   une playlist locale non liée (`playlist/create` puis lien).

5. **Hors ligne / robustesse** : toutes les écritures distantes passent par une **file d'actions en attente
   persistée** (table Room), rejouée par un `CoroutineWorker` (contrainte réseau, backoff exponentiel, unique work) ;
   synchro complète périodique (ex. toutes les 6 h, réseau requis), au démarrage si dernière synchro > 1 h, et bouton
   manuel. 401/403 ou cookie expiré → état « Reconnexion nécessaire » sans effacer les données locales. Parsing
   tolérant : un champ manquant produit une erreur de synchro claire (visible dans le diagnostic), jamais un crash.
   Mapping en `AppException(AppError)` avec messages FR.

### Architecture (respecter `CLAUDE.md`)
- `:domain` : modèles (`YouTubeAccount`, `SyncStatus`, `RemoteLibraryPlaylist`…), interfaces (`AccountRepository`,
  `LibrarySync`…), **logique de réconciliation à trois voies en Kotlin pur** (ensembles et listes ordonnées), use cases.
- `:data` : package `youtube/` : client InnerTube authentifié (OkHttp injecté, intercepteur SAPISIDHASH), parsers JSON
  tolérants, stockage chiffré, entités/DAO Room (lien playlist ↔ id YouTube, `setVideoId` par entrée, instantanés de
  synchro, file d'actions) **avec nouvelle version + Migration + test MigrationTestHelper**, workers, bind Hilt. Les
  repositories existants (likes, abonnements, playlists) enfilent les actions distantes quand le compte est connecté et
  l'objet lié, sans casser leurs tests.
- WebView de connexion dans `:ui` (ou `:app`) : capture cookies + visitorData + dataSyncId et les passe au domaine.
- `:ui` : écrans Route+Screen, ViewModels UDF.

### Tests obligatoires
- Réconciliation à trois voies (ensembles : ajout local, suppression distante, conflits, premier lien = union ; listes
  ordonnées : ajouts/suppressions croisés, réordonnancement d'un seul côté).
- SAPISIDHASH (valeur attendue pour cookie/horodatage fixes, comparée à ytmusicapi).
- Parsers sur **des réponses JSON réelles** tirées des jeux de tests de ytmusicapi / Metrolist / youtubei.js
  (provenance et licence dans un README du dossier de fixtures) ; signaler tout endpoint sans fixture.
- File d'actions + worker (Robolectric / WorkManager testing), migration Room, ViewModels (MockK + Turbine), stockage
  chiffré (roundtrip, via abstraction si Keystore indisponible).
- Optionnel : test réel désactivé par défaut (modèle `SPAUTIFAILLE_LIVE_TESTS`) avec cookie en variable d'environnement.

## Exigences
- `./gradlew :domain:test testDebugUnitTest` puis `./gradlew lintDebug assembleDebug` doivent passer.
- Mettre à jour `CLAUDE.md` (section courte « Compte YouTube / synchro »).
- Commits logiques en français.
- Rapport final honnête : ce qui est fait, ce qui manque, et ce qui n'a pas pu être vérifié (connexion réelle,
  endpoints réels sans compte Google ni appareil).
