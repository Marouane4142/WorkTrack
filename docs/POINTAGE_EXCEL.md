# Feuille de pointage Excel

L'export XLSX remplit une copie du modèle fourni, `Feuille Pointages Apprentis UBBAK.xlsx`. Il conserve les cinq onglets Lundi, Mardi, Mercredi, Jeudi et Vendredi, les cellules fusionnées, colonnes, bordures, polices et dessins. Le fichier original à la racine n'est pas modifié.

## Utilisation

Dans **Exports**, le format **Feuille de pointage Excel** est sélectionné par défaut. Choisissez une date de la semaine souhaitée : l'application utilise le lundi correspondant et remplit les cinq feuilles jusqu'au vendredi. Enregistrez le fichier avec le sélecteur Android ou partagez-le directement. Les activités des jours sans saisie ne sont pas inventées : la feuille reste datée, avec des totaux à zéro.

Les données proviennent de l'historique et du profil des réglages. Aucune nouvelle saisie des horaires dans Excel n'est nécessaire. Les modifications manuelles effectuées ensuite dans le classeur ne sont pas réimportées dans l'application ; pour recalculer une franchise ou des heures de nuit après correction d'un horaire, modifiez l'activité dans l'application puis exportez à nouveau.

## Correspondance des cellules

| Zone du modèle | Données remplies |
|---|---|
| B3, D3, D4 | Date du jour, nom et prénom |
| H3, G4, H4 | Numéro de semaine ISO, lundi et vendredi |
| B13:C15 | Horaires des pauses enregistrées |
| B17:C51 | Horaires bruts des trajets et activités, sans retirer de minutes à l'affichage |
| D18, D20, etc. | Nom du site d'intervention, Bureau ou libellé de l'activité |
| E17, E19, etc. | N° d'affaire, conservé comme texte avec ses zéros initiaux |
| G17:H51 | Heures effectives et non effectives déjà calculées selon les règles enregistrées |
| G53:H53 | Totaux de jour, formules de somme déduisant les heures de nuit |
| G54:H54 | Heures de nuit calculées par l'application |
| H58 | Nombre de repas, sans montant financier |
| Cadre VISA TECHNICIEN | Image de signature configurée dans les réglages |

Le dernier trajet de retour au domicile utilise la ligne 51. Les autres trajets et interventions occupent les lignes prévues, en conservant leur ordre. Une pause supplémentaire reste un intervalle distinct ; des pauses séparées ne sont pas fusionnées en une plage contenant un temps travaillé.

Les dates, horaires et durées sont de véritables valeurs numériques Excel avec des formats adaptés. Une fin à minuit s'affiche 24:00 pour la journée qui se termine. Une activité traversant minuit est ventilée sur les jours concernés. La franchise n'est pas appliquée une seconde fois à minuit.

## Présentation et limites

Le classeur conserve le logo d'origine lorsque le profil n'en contient pas. Un logo personnel et une signature sont intégrés au fichier, sans lien vers une image externe. Les cinq feuilles disposent d'une zone d'impression A4 portrait d'une page, conforme au format du modèle. Les PDF séparés restent en paysage.

Le modèle d'origine ne contient pas de formules. L'export ajoute les formules des totaux de jour et leur résultat calculé, afin que les valeurs soient visibles dès l'ouverture, puis demande un recalcul automatique dans Excel.

Les lignes Astreinte, Grand déplacement et Autres primes restent vides. Leurs libellés appartiennent au modèle fourni ; aucun calcul financier n'est ajouté à l'application.

Le modèle a un nombre fixe de lignes. Une journée qui dépasse les emplacements disponibles provoque un message explicite avant la génération ; aucune activité n'est omise ou tronquée pour faire tenir les totaux. Le CSV détaillé reste disponible. Les activités du samedi et dimanche restent dans l'historique et peuvent être exportées en PDF ou CSV ; elles ne sont pas déplacées sur un des cinq jours du modèle.

L'écriture utilise les API ZIP/XML de la plateforme et le moteur de temps existant. Elle ne nécessite ni Excel sur le téléphone, ni connexion Internet, ni bibliothèque bureautique supplémentaire.
