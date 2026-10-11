# Contribuer à WorkTrack

Les issues et pull requests sont les bienvenues. Décrivez le comportement attendu, les étapes de reproduction et la version Android concernée. Utilisez des données fictives dans les captures, relevés et sauvegardes joints.

## Développement

Consultez le [README](README.md) pour installer le JDK et le SDK. Gardez les calculs en minutes entières dans `domain/`, les accès aux données dans `data/`, et l'état des écrans dans le ViewModel. Les activités enregistrées conservent leurs règles de calcul : un changement de réglage ne doit pas réécrire silencieusement l'historique.

Avant une pull request, exécutez :

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

Sous Windows, utilisez `gradlew.bat`. Si votre changement touche la saisie, Room ou les exports, exécutez également `:app:connectedDebugAndroidTest` avec un émulateur ou un téléphone connecté. Ajoutez un test de comportement lorsqu'une règle de temps ou une migration change.

Ne versionnez ni clé de signature, ni `local.properties`, ni sauvegarde personnelle, ni document professionnel réel. Les APK sont distribués via les releases GitHub.

En proposant une contribution, vous acceptez qu'elle soit distribuée sous la [licence MIT](LICENSE) du projet.
