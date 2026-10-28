# WorkTrack

[![Android](https://github.com/Marouane4142/WorkTrack/actions/workflows/android.yml/badge.svg)](https://github.com/Marouane4142/WorkTrack/actions/workflows/android.yml)
[![Licence MIT](https://img.shields.io/badge/licence-MIT-green.svg)](LICENSE)
[![Android 7.0+](https://img.shields.io/badge/Android-7.0%2B-3DDC84.svg)](https://github.com/Marouane4142/WorkTrack/releases/latest)

Application Android de suivi du temps de travail, en français, écrite en Kotlin et Jetpack Compose. Les données restent sur le téléphone ; aucun compte ou accès Internet n'est nécessaire. WorkTrack distingue les heures effectives et non effectives, conserve les durées brutes et remplit le modèle de pointage hebdomadaire fourni, en Excel ou en PDF. Aucun calcul financier n'est inclus.

**[Télécharger l'APK](https://github.com/Marouane4142/WorkTrack/releases/latest)** · [Contribuer](CONTRIBUTING.md) · [Validation](docs/VALIDATION.md)

## Fonctionnalités

- Tableau de bord : totaux quotidiens, hebdomadaires et mensuels, graphique de la semaine et compteur de repas.
- Saisie des interventions, travail au bureau, trajets, pauses et autres activités ; contrôle des horaires et confirmation des chevauchements.
- Trajet unique avec départ et destination typés : Domicile, Bureau ou Site client. Le dernier lieu connu préremplit le départ ; un trajet vers un client préremplit le site de l'intervention suivante. Les corrections manuelles sont conservées.
- Historique avec calendrier, recherche, filtres, modification et duplication d'une activité ou d'une journée.
- Export principal **Excel XLSX** : une semaine du lundi au vendredi, avec les cinq onglets du modèle original, dates, identité, horaires, affaires, pauses, heures effectives/non effectives, totaux jour/nuit, repas et signature. Le logo du modèle est conservé, ou remplacé par celui configuré dans le profil. [Détails du remplissage](docs/POINTAGE_EXCEL.md).
- Le **même pointage en PDF** : exactement cinq pages A4 portrait, une par jour, reprenant la grille du classeur rempli, ses valeurs, logo et signature. Les jours sans activité gardent leur page datée.
- Après l'enregistrement : **Ouvrir**, Partager ou Fermer. Le bouton Ouvrir transmet le fichier réellement enregistré à une application compatible installée sur le téléphone.
- Préférences de thème clair/sombre/système, affichage des durées, premier jour de semaine, franchise des trajets, seuil des repas, arrondi et plage de nuit.
- Sauvegarde JSON comprenant les images, restauration par fusion ou remplacement, journal des modifications et recalcul explicite d'une période.

Les deux formats portent le nom `Feuilles_heures_hebdomadaire_NOM PRENOM_YYYY-MM-DD.xlsx` ou `.pdf`, avec la date du lundi. Les relevés n'affichent aucune marque de l'application. Une installation neuve ne contient aucune activité d'exemple.

## Règles de temps

| Activité | Effectif | Non effectif |
|---|---:|---:|
| Domicile ↔ site client | 0 | `max(0, durée brute − franchise)` |
| Domicile ↔ bureau | 0 | 0 |
| Bureau ↔ site client | durée complète | 0 |
| Site client ↔ site client | durée complète | 0 |
| Intervention client / travail au bureau | durée complète | 0 |
| Pause | 0 | 0 |

La franchise par défaut est de **45 minutes dans chaque sens domicile ↔ site client** et peut être modifiée. Un trajet de 2 h donne 1,25 h non effective ; 1 h 30 donne 0,75 h ; 30 minutes donnent zéro. Tous les trajets restent enregistrés avec leur durée brute.

Les calculs utilisent des minutes entières. Chaque activité conserve les règles appliquées lors de son enregistrement : modifier un réglage ne recalcule pas l'historique. Les repas sont comptés à partir des heures effectives, avec un seuil de 4 h et un maximum d'un repas par journée par défaut.

Une fin antérieure au début est refusée sur une même journée. L'option **Fin le lendemain** permet de saisir une activité qui traverse minuit, dans la limite de 24 h. Les heures sont ventilées par journée civile ; la franchise est déduite une seule fois au début du trajet. Les heures de nuit sont une information de durée.

## Stack

| Composant | Version / choix |
|---|---|
| Kotlin et plugins Compose / serialization | 2.4.21 |
| Jetpack Compose UI, Foundation, Animation | 1.12.1 |
| Material 3 Expressive | 1.5.0-beta01 |
| Navigation 3 | 1.2.0 |
| Room / SQLite | 2.8.5 |
| Jetpack DataStore | 1.2.1 |
| Architecture | MVVM + Repository |
| Hilt | 2.60.1 |
| Coroutines + StateFlow | 1.10.2 |
| KSP | 2.3.12 |
| JUnit / tests Compose | 4.13.2 / 1.12.1 |
| Gradle / Android Gradle Plugin | 9.6.0 / 9.4.1 |
| Android | minimum API 24, cible API 36, compilation API 37.0 |
| JDK | 21 ; bytecode Java 17 avec désucrage `java.time` |

AGP utilise Kotlin intégré ; le build racine fixe son compilateur à la version 2.4.21. Material 3 utilise les API Expressive officielles de la version bêta. Les dépendances sont fixées dans les scripts Gradle.

## Construire

Installez **JDK 21**, Android SDK Platform **37.0**, Build Tools **36.0.0** et Platform Tools. Configurez `JAVA_HOME` et `ANDROID_HOME`, ou indiquez le SDK dans un fichier local `local.properties` contenant `sdk.dir=/chemin/vers/Android/Sdk`. Android Studio permet aussi d'importer le projet et fournit les aperçus Compose. Le premier build télécharge les dépendances ; l'application installée fonctionne hors ligne.

```sh
git clone https://github.com/Marouane4142/WorkTrack.git
cd WorkTrack
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Sous Windows, utilisez `gradlew.bat`, ou le script PowerShell fourni :

```powershell
.\build-android.bat -Test
.\build-android.bat -Test -ConnectedTests  # Appareil ou émulateur connecté
.\build-android.bat -Install
```

L'APK debug se trouve dans `app/build/outputs/apk/debug/`. Le script Windows le copie aussi dans `artifacts/` avec sa somme SHA-256. Les paramètres `-AndroidSdk` et `-DebugKeystore` permettent de sélectionner d'autres chemins. Le script détecte les JDK installés et, si elle existe, la clé de test Android locale de Visual Studio.

### Build optimisé et signature

```sh
./gradlew :app:assembleRelease
```

Le build release active **R8 et la réduction des ressources**. Sans configuration de signature, il produit `app/build/outputs/apk/release/app-release-unsigned.apk`. Pour signer votre distribution, fournissez ces variables d'environnement avant le build :

```text
WORKTRACK_KEYSTORE          chemin vers votre keystore
WORKTRACK_STORE_PASSWORD    mot de passe du keystore
WORKTRACK_KEY_ALIAS         alias de la clé
WORKTRACK_KEY_PASSWORD      mot de passe de la clé
```

Les clés ne sont jamais stockées dans le dépôt. Le script Windows `-Release` utilise une clé de distribution fournie par ces variables, sinon la clé de test locale. Les APK de test proposés dans les releases utilisent la clé historique pour permettre les mises à jour des installations précédentes. Un APK que vous compilez avec votre propre clé ne peut pas remplacer une installation signée avec une autre clé.

## Organisation

```text
app/src/main/java/fr/worktrack/
  domain/       modèles, calculs, lieux et validation
  data/         Room, DataStore, repository et migrations
  di/           injection Hilt
  export/       pointage XLSX/PDF, images, ouverture et partage
  ui/           ViewModel, navigation et écrans Compose
  ui/theme/     thème Material 3 Expressive
app/src/test/         tests JUnit et semaine anonymisée
app/src/androidTest/  tests Compose, Room, repository et documents
app/schemas/          schéma Room versionné
docs/                 résultats de validation
scripts/              build Windows
.github/workflows/    vérification continue
```

Les caches, sorties de build, APK, clés, préférences locales et documents personnels sont exclus de Git. La compatibilité avec les anciennes données SQLite est conservée dans le code Kotlin de migration ; le dépôt ne contient aucun projet .NET.

## Vérifier

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
./gradlew :app:connectedDebugAndroidTest  # Émulateur ou téléphone connecté
```

La semaine de référence anonymisée comprend 35 activités : **30,75 h effectives, 8,50 h non effectives, 5 repas**. Les tests couvrent les trajets, les franchises, les horaires, les passages de minuit, les heures de nuit, les règles enregistrées, le préremplissage, les sauvegardes, les migrations et les exports avec images. Les rapports générés sont dans `app/build/reports/`.

GitHub Actions vérifie les tests JUnit, Android Lint et les builds debug/release à chaque push et pull request. Les tests sur appareil sont exécutés séparément, avec la commande ci-dessus. Le build automatique ne reçoit aucune clé de signature privée.

## Licence

WorkTrack est distribué sous la [licence MIT](LICENSE). Les bibliothèques utilisées conservent leurs licences respectives.
