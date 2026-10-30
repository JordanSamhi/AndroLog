# Plan de présentation

**AndroLog: Instrumentation d'Apps Android en Boîte Noire**

Jordan Samhi · Université du Luxembourg · 15 minutes, dont 2 minutes de questions

## Objectif

Présenter AndroLog, un outil qui mesure la couverture de code d’une app Android à partir de son APK. La présentation se concentre sur l’interface graphique et les nouvelles fonctionnalités de logs personnalisés. La démonstration montre ce que devient une URL reçue par une app et quelle méthode la vérifie.

## Déroulement

| Temps | Partie | Contenu |
| --- | --- | --- |
| 0:00–1:30 | Introduction | Présenter AndroLog, son origine académique et la couverture des instructions, méthodes, classes et composants Android. |
| 1:30–3:00 | Fonctionnement | Expliquer les étapes : APK original, ajout des logs, APK instrumenté, test et rapport. Présenter la nouvelle interface graphique. |
| 3:00–5:00 | Logs personnalisés | Parcourir les packages, classes et méthodes, choisir une instruction et présenter les trois options. Activer aussi le graphe d’appels dynamique. |
| 5:00–7:00 | Test sur émulateur | Utiliser un APK déjà instrumenté, lancer Test on device, tester une URL acceptée et cliquer sur Stop & analyze. |
| 7:00–10:30 | Résultats | Montrer la couverture, les points verts et rouges, la valeur enregistrée, le code correspondant et l’appel confirmé dans le graphe. |
| 10:30–12:00 | Lire les observations | Expliquer ce que signifient un point non observé, un log placé avant une instruction et une cible déclarée. |
| 12:00–13:00 | Partage | Montrer le rapport HTML et expliquer comment reprendre l’analyse avec l’APK instrumenté et les logs. |
| 13:00–15:00 | Questions | Répondre aux questions du public. |

## Supports prévus

Quelques diapositives présenteront la couverture de code, l’interface et les logs personnalisés. La plus grande partie sera consacrée à la démonstration. Les diapositives finales pourront être préparées après l’acceptation ; ce document présente le plan prévu.

<!-- pagebreak -->

# Démonstration

## App utilisée

L’app `com.example.andrologsstic` reçoit une URL par lien profond. Elle la normalise, choisit une implémentation de `UrlPolicy` et affiche si l’URL est acceptée ou refusée. Cette app a été créée pour la démonstration : elle ne fait pas de requête réseau et n’accède pas à des fichiers.

1. **Ouvrir l’APK original.** Choisir les niveaux de couverture à mesurer : instructions, méthodes, classes et composants Android. Parcourir ensuite les packages, la classe et la méthode qui traitent l’URL. Le code affiché est du Jimple et les noms de variables peuvent être différents de ceux du code source.
2. **Choisir les logs personnalisés.** Ajouter un point Statement reached dans chaque branche, un message avec Custom message avant le choix de la politique et un point Variable value pour l’URL normalisée. Pour voir le résultat d’une instruction, placer le log avant l’instruction suivante.
3. **Tester l’app.** Activer le graphe d’appels dynamique, puis instrumenter l’APK. Pour gagner du temps pendant la présentation, utiliser ensuite l’APK déjà instrumenté. Lancer Test on device, tester l’URL acceptée et cliquer sur Stop & analyze.
4. **Lire les résultats.** Montrer la couverture, puis les points atteints et non atteints ainsi que l’URL enregistrée. Cliquer sur un point pour afficher son emplacement dans le code. La branche de refus est rouge dans cette capture ; elle est verte dans le rapport d’une exécution avec une URL refusée.
5. **Explorer le graphe.** Montrer l’appel à `UrlPolicy.allow` et l’implémentation appelée. Expliquer la différence entre une cible confirmée par la trace et une cible déclarée dont l’exécution n’a pas pu être confirmée.
6. **Partager les résultats.** Ouvrir le rapport HTML et montrer que l’APK instrumenté et le fichier de logs permettent de reprendre l’analyse sur une autre machine.

## Préparation

L’émulateur, le SDK et les fichiers seront préparés avant la présentation. Un APK déjà instrumenté permettra de ne pas attendre l’instrumentation pendant la démonstration.

Si le test sur l’émulateur ne fonctionne pas, nous utiliserons les logs et les rapports enregistrés à l’avance. Ils sont fournis avec le dossier, avec les étapes pour reproduire les trois scénarios.

## Limites

Un point rouge indique qu’il n’a pas été observé dans la capture, pas qu’il est inaccessible. Un log ajouté avant une instruction indique qu’elle a été atteinte, pas qu’elle s’est terminée. La couverture et le graphe dépendent des actions effectuées et des logs collectés.
