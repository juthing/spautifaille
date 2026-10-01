# Spautifaille

Lecteur audio Android en streaming, alimenté par YouTube via [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor). Sans compte, sans pub, sans Play Store. Usage personnel, licence GPL-3.0.

## Installer l'APK

1. Ouvrir l'onglet **Actions** du dépôt GitHub, puis le dernier run vert de « Android CI » sur la branche voulue.
2. En bas de la page, télécharger l'artefact **spautifaille-release-apk** (optimisé, ~5 Mo) ou **spautifaille-debug-apk** (non optimisé, installable à côté de la release : identifiant `com.spautifaille.app.debug`).
3. Dézipper puis ouvrir l'`.apk` sur le téléphone (autoriser « Installer des applis inconnues » pour le navigateur ou le gestionnaire de fichiers).

> L'APK release est signé avec une clé fixe (secrets GitHub Actions) : les mises à jour s'installent par-dessus la version précédente, sans désinstaller. Seule la toute première installation signée avec cette clé nécessite de désinstaller une ancienne version signée autrement (clé de debug d'un ancien run, par exemple). Play Protect peut avertir (« développeur inconnu ») : choisir « Installer quand même ».

Configuration minimale : Android 8.0 (API 26). Cible : Android 17 (API 37).

## Fonctionnalités

- **Navigation** : trois onglets (Accueil, Bibliothèque, Réglages). La recherche s'ouvre depuis la barre en haut de l'Accueil ; l'Historique et « Tes artistes » s'ouvrent depuis les sections de l'Accueil.
- **Accueil** : salutation, raccourcis (Titres likés, playlists), reprise de l'écoute récente, artistes suivis, découverte.
- **Recherche** : clavier ouvert à l'arrivée, historique, suggestions, filtres Titres / Vidéos / Albums / Playlists / Artistes, pagination.
- **Lecture audio uniquement**, meilleur débit disponible (ou « Économie de données » dans les paramètres). L'URL du flux est résolue juste avant la lecture et n'est jamais enregistrée.
- **Intégration système** : notification média, écran de verrouillage, bouton « J'aime » dans la notification, casque filaire/Bluetooth, Android Auto, pause sur appel / baisse du volume, pause au débranchement du casque, reprise de lecture après redémarrage.
- **Contrôles** : précédent (redémarre le titre après 3 s), suivant, aléatoire, répétition (aucune / tout / un), file d'attente éditable (glisser-déposer, lire ensuite, retirer), vitesse, minuteur de veille (durée ou fin du titre).
- **Bibliothèque** : uniquement des playlists. « Titres likés » et « Téléchargés » sont épinglées en tête (« Téléchargés » passe en premier hors ligne) ; le bouton « + » crée une playlist vide ou lance un import ; appui long sur une playlist pour lire, télécharger, renommer ou supprimer. Dans une playlist : réordonner, retirer par balayage (avec annulation), télécharger. Hors ligne, seuls les titres téléchargés restent lisibles.
- **Réglages** façon Android, par catégories : Lecture, Téléchargements, Importer, Apparence (thème, couleurs dynamiques), Stockage (cache), Découverte (clé Last.fm), À propos.
- **Import de playlists** : URL YouTube / YouTube Music, fichiers Exportify (CSV), export de données Spotify (JSON), Google Takeout (CSV), CSV générique (titre, artiste, album, durée). Accessible depuis le « + » de la Bibliothèque ou depuis Réglages > Importer. Les correspondances incertaines sont marquées « à vérifier » et peuvent être corrigées dans l'écran de revue.
- **Téléchargements** hors ligne (Wi-Fi uniquement en option), reprise automatique, gestion de l'espace (Réglages > Téléchargements). Un titre téléchargé est toujours lu depuis le fichier local.
- **Découverte** : titres similaires à tes likes et écoutes (titres liés YouTube), rafraîchis en tâche de fond.

## Checklist de test sur téléphone

1. Toucher la barre de recherche de l'Accueil, chercher un titre et le lancer : le son démarre, la notification média apparaît avec pochette, titre, artiste et barre de progression.
2. Verrouiller l'écran : contrôles présents, seek fonctionnel.
3. Appuyer sur ♥ dans la notification : le titre apparaît dans « Titres likés ».
4. Brancher/débrancher un casque filaire (pause), tester les boutons d'un casque Bluetooth.
5. Recevoir un appel : la musique se met en pause puis reprend.
6. File d'attente : « Lire ensuite », réordonner par glisser-déposer, retirer par balayage.
7. Minuteur de veille 5 min : la lecture s'arrête en fondu.
8. Télécharger une playlist, passer en mode avion, lire : lecture depuis les fichiers locaux.
9. Importer un CSV Exportify : suivre la progression, ouvrir la revue des titres « à vérifier ».
10. Fermer l'app depuis les applis récentes pendant la lecture, puis relancer la lecture depuis les contrôles média du système : reprise au bon titre.

En cas de problème, noter le titre concerné et le message affiché.

## Développement

Voir [CLAUDE.md](CLAUDE.md) : architecture (modules `app`, `domain`, `data`, `player`, `ui`), commandes Gradle, conventions et pièges connus (YouTube, NewPipe, R8).

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug :domain:test
SPAUTIFAILLE_LIVE_TESTS=1 ./gradlew :data:testDebugUnitTest --tests "*LiveYoutubeTest*"   # tests réels contre YouTube
```
