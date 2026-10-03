# Fixtures des réponses InnerTube (YouTube Music)

Ces fichiers servent aux tests des parsers (`InnerTubeParsersTest`) et du client (`YouTubeRemoteTest`).
Ils sont de deux natures, à ne pas confondre.

## Réponses réelles (dossier courant)

Source : dépôt **ytmusicapi** (https://github.com/sigma67/ytmusicapi, licence **MIT**, © sigma67),
fichiers `tests/data/2024_03_get_playlist.json` (playlist dont le compte est propriétaire, en-tête
`musicEditablePlaylistDetailHeaderRenderer`) et `tests/data/2024_03_get_playlist_public.json` (playlist
publique d'un autre auteur, en-tête `musicResponsiveHeaderRenderer`). Récupérés le 2026-10-02 depuis la branche `main`.

Modification unique : la liste `musicPlaylistShelfRenderer.contents` est tronquée à **4 éléments** (sur 100) pour
alléger le dépôt ; le jeton de continuation `continuations[0].nextContinuationData` et tout le reste de la réponse
sont conservés tels quels. Le JSON est compacté (aucune valeur modifiée).

| Fichier | Endpoint | Ce qu'il couvre |
| --- | --- | --- |
| `ytmusicapi_get_playlist_owned_trimmed.json` | `browse` (`VL<playlistId>`) | playlist possédée : titre d'édition, `playlistId`, `setVideoId` (menu `playlistEditEndpoint` et `playlistSetVideoId`), colonnes titre / artiste / album, durée, continuation historique |
| `ytmusicapi_get_playlist_public_trimmed.json` | `browse` (`VL<playlistId>`) | playlist non possédée (lecture seule), nombre de titres dans `secondSubtitle` |

## Réponses synthétiques (dossier `synthetic/`)

**Ce ne sont pas des captures réelles.** ytmusicapi, Metrolist et youtubei.js ne publient aucune réponse enregistrée
pour ces endpoints (leurs tests sont soit en direct avec un compte, soit sans fixture) ; aucun compte Google n'était
disponible pour en capturer. Ces fichiers sont écrits à la main, **au strict minimum de la structure lue dans le
code des parsers de référence**, avec des valeurs inventées (identifiants, noms) :

| Fichier | Endpoint | Structure suivie |
| --- | --- | --- |
| `account_menu.json`, `account_menu_signed_out.json` | `account/account_menu` | ytmusicapi `get_account_info` ; Metrolist `AccountMenuResponse` (`activeAccountHeaderRenderer`) |
| `library_playlists_grid.json`, `library_playlists_continuation.json` | `browse` `FEmusic_liked_playlists` | ytmusicapi `get_library_playlists` / `parse_playlist` ; Metrolist `LibraryPage.fromMusicTwoRowItemRenderer` (`gridRenderer`, `gridContinuation`) |
| `library_subscriptions.json`, `library_subscriptions_continuation.json` | `browse` `FEmusic_library_corpus_artists` | ytmusicapi `get_library_subscriptions` / `parse_artists` (`musicShelfRenderer`, `musicShelfContinuation`) |
| `playlist_continuation_modern.json`, `playlist_continuation_legacy.json` | `browse` (continuation) | ytmusicapi `get_continuations_2025` (`appendContinuationItemsAction`) et `get_continuations` (`musicPlaylistShelfContinuation`) |
| `edit_playlist_response.json` | `browse/edit_playlist` | ytmusicapi `add_playlist_items` (`status`, `playlistEditResults[].playlistEditVideoAddedResultData`) |

Endpoints **sans aucune fixture, ni réelle ni synthétique** : `like/like`, `like/removelike`,
`subscription/subscribe`, `subscription/unsubscribe`, `playlist/create` (le code ne lit presque rien de leur
réponse : succès HTTP, plus `playlistId` pour `playlist/create`), et `browse` `VLLM` / `VLLL` (même forme que
`get_playlist` pour `LM`, **non vérifié** pour `LL`).

Références : ytmusicapi (MIT) ; Metrolist et InnerTubeX (GPL-3.0, compatible avec ce dépôt) pour les en-têtes,
le contexte `WEB_REMIX` et les corps de requête.
