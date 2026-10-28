# Validation de WorkTrack 2.2.1

Vérification locale sous Windows avec JDK 21 et un émulateur Pixel 7 sous Android 16 (API 36).

| Vérification | Résultat |
|---|---|
| Tests JUnit : moteur, lieux, sauvegardes, pointage XLSX | 43 réussis, 0 échec |
| Tests Android : Compose, Room, images, XLSX/PDF et ouverture | 19 réussis, 0 échec |
| Android Lint debug | 0 erreur ; avertissements de versions et suggestions Android/Kotlin |
| Build release avec R8 et réduction des ressources | réussi |
| Installation et lancement du release signé | réussis sur émulateur API 36 |

## Temps et données

Les tests utilisent des bases et préférences isolées, des identités fictives et une semaine anonymisée. Ses 35 activités donnent **1 845 minutes effectives (30,75 h), 510 minutes non effectives (8,50 h), 5 repas**. La franchise de 45 minutes s'applique dans les deux sens domicile ↔ site ; les durées de 120, 90 et 30 minutes donnent 75, 45 et 0 minutes non effectives.

Les neuf combinaisons de lieux, les franchises personnalisées, les passages de minuit, les repas, les arrondis et les chevauchements sont couverts. Le calcul des heures de nuit est comparé à une référence minute par minute sur 240 combinaisons. Les tests Room vérifient les migrations et la conservation des horaires, règles et journaux. Les sauvegardes avec images sont validées par aller-retour.

## Pointage hebdomadaire

L'écran propose deux formats : XLSX et PDF. Les tests Compose vérifient cette sélection, la semaine de cinq jours et l'absence des anciennes options. Les tests de saisie, préremplissage, corrections manuelles, horaires invalides et navigation restent présents.

Le XLSX reprend les cinq onglets, fusions, images et parties non modifiées du modèle. Les dates et horaires sont numériques ; les numéros d'affaire sont des textes, y compris `00042`. Les contrôles couvrent les jours vides, passages de minuit, bornes de semaine, pauses séparées, débordements de capacité, logo et signature.

Le PDF est produit depuis ce même classeur rempli. Les tests Android vérifient exactement **cinq pages A4 portrait (595 × 842 points)**, y compris pour une semaine avec quatre jours vides. Ils rendent les pages et contrôlent les pixels du logo et de la signature sur chaque page. Les cinq pages de référence ont été rendues et inspectées visuellement ; leur texte extrait concorde avec les dates et totaux du classeur et conserve les zéros initiaux des numéros d'affaire. Aucun tableau supplémentaire ou récapitulatif séparé n'est ajouté.

## Enregistrement et ouverture

La fenêtre de confirmation est testée avec **Ouvrir**, **Partager** et **Fermer**. Les tests de documents vérifient que l'enregistrement renvoie l'URI de destination réelle et que l'intention d'ouverture utilise cette URI, le bon type MIME, ClipData et un droit de lecture temporaire. Un lecteur absent produit un message compréhensible ; une activité externe n'est pas lancée dans ce test.

La génération et l'enregistrement sont testés sur Android. Le contrôle par l'interface Excel Windows n'est pas revendiqué. Les essais sur émulateur ne remplacent pas une validation sur chaque modèle de téléphone ou lecteur bureautique.

Les avertissements Lint ne sont pas masqués par une baseline. Les versions de la stack restent fixées. Les données d'exemple ne sont pas intégrées à l'APK et les clés de signature restent hors de Git.

Rapports générés, exclus de Git : `app/build/reports/tests/testDebugUnitTest/`, `app/build/reports/androidTests/connected/debug/`, `app/build/reports/lint-results-debug.html`.
