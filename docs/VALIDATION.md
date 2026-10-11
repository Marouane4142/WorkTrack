# Validation de WorkTrack 2.1.2

Vérification locale sous Windows avec JDK 21 et un émulateur Pixel 7 sous Android 16 (API 36).

| Vérification | Résultat |
|---|---|
| Tests JUnit du moteur, des lieux, sauvegardes et exports | 36 réussis, 0 échec |
| Tests Android : Compose, Room, repository, fichiers et PDF | 14 réussis, 0 échec |
| Android Lint debug | 0 erreur ; avertissements de versions et suggestions Android/Kotlin |
| Build debug | réussi |
| Build release avec R8 et réduction des ressources | réussi |
| Installation et lancement du release signé | réussis sur émulateur API 36 |

Commande de validation complète sous Windows :

```powershell
.\build-android.bat -Release -Test -ConnectedTests -Install
```

Les tests utilisent des bases et préférences isolées, des identités fictives et une semaine de référence anonymisée. Ses 35 activités donnent **1 845 minutes effectives (30,75 h), 510 minutes non effectives (8,50 h), 5 repas**. La franchise de 45 minutes s'applique dans les deux sens domicile ↔ site : les durées de 120, 90 et 30 minutes donnent respectivement 75, 45 et 0 minutes non effectives.

Les neuf combinaisons de lieux, les franchises personnalisées, les passages de minuit, les repas, les arrondis et les chevauchements sont couverts. Le calcul des heures de nuit intersecte au maximum deux plages par journée, sans parcourir chaque minute ; il est comparé à une référence minute par minute sur 240 combinaisons de fenêtres, horaires, durées et types d'activité, avec franchise et arrondi.

Les tests Compose vérifient les quatre destinations Navigation 3, la saisie d'un trajet, les champs conditionnels d'intervention, le préremplissage du départ et du site depuis un trajet précédent, le respect des corrections manuelles, l'absence de suggestions rapides sur les trajets, le refus des heures inversées et l'abandon confirmé d'une saisie.

Les tests Room vérifient la migration SQLite historique et la correction des anciens allers domicile → site : horaires et franchise enregistrée conservés, audit avant/après et absence de répétition au lancement suivant. Les sauvegardes sont vérifiées par aller-retour et validation des données.

Les tests de documents importent une image par URI, vérifient sa présence dans une sauvegarde, rendent chaque page d'un PDF long et contrôlent le logo et la signature. Ils vérifient aussi le nom exposé par FileProvider. Les exports PDF sont en A4 paysage, sans marque de l'application, avec une mention des franchises déjà déduites. Les CSV utilisent un BOM UTF-8, des décimales françaises et un séparateur point-virgule.

Les avertissements Lint ne sont pas masqués par une baseline. Les versions de la stack restent fixées ; les essais sur émulateur ne remplacent pas une validation sur chaque modèle de téléphone.

Rapports générés, exclus de Git : `app/build/reports/tests/testDebugUnitTest/`, `app/build/reports/androidTests/connected/debug/`, `app/build/reports/lint-results-debug.html`.
