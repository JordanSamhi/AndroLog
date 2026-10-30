# Plan de présentation

**AndroLog: Instrumentation d'Apps Android en Boîte Noire**

Présentation d’un outil · Jordan Samhi · 15 minutes, dont 2 minutes de questions

## Objectif

Montrer comment utiliser AndroLog pour ajouter des logs à un APK, tester l’application et lire les résultats. La démonstration part d’une question simple : que devient une URL reçue par l’application et quelle méthode vérifie cette URL ?

## Déroulement

| Temps | Partie | Contenu |
| --- | --- | --- |
| 0:00–1:30 | Introduction | Présenter l’application et les questions : quelle valeur est utilisée, quelle méthode est appelée et quelle branche est exécutée ? |
| 1:30–3:00 | Fonctionnement | Expliquer les étapes : APK original, ajout des logs, APK instrumenté, exécution et rapport. Présenter brièvement Soot et Jimple. |
| 3:00–5:00 | Choix des logs | Parcourir les classes et les méthodes, choisir une instruction et présenter les trois options de logs personnalisés. Activer le graphe d’appels. |
| 5:00–7:00 | Test sur émulateur | Utiliser un APK déjà instrumenté, lancer Test on device, tester une URL acceptée et cliquer sur Stop & analyze. |
| 7:00–10:30 | Résultats | Montrer la couverture, les points verts et rouges, la valeur enregistrée, le code correspondant et l’appel confirmé dans le graphe. |
| 10:30–12:00 | Limites | Expliquer ce que signifient un point non observé et une cible déclarée, ainsi que les effets possibles de l’instrumentation. |
| 12:00–13:00 | Partage | Montrer le rapport HTML et expliquer comment reprendre l’analyse avec l’APK instrumenté et les logs. |
| 13:00–15:00 | Questions | Répondre aux questions du public. |

## Supports prévus

La présentation utilisera quelques diapositives pour expliquer l’outil et ses limites. La plus grande partie sera consacrée à la démonstration. Les diapositives finales pourront être préparées après l’acceptation ; ce document présente le plan prévu.

<!-- pagebreak -->

# Démonstration

## Application utilisée

L’application `com.example.andrologsstic` reçoit une URL par lien profond. Elle la normalise, choisit une implémentation de `UrlPolicy` et affiche si l’URL est acceptée ou refusée. C’est une application créée pour la démonstration : elle ne fait pas de requête réseau et n’accède pas à des fichiers.

1. **Ouvrir l’APK original.** Dans AndroLog, parcourir la classe et la méthode qui traitent l’URL. Expliquer que le code affiché est du Jimple et que les noms de variables peuvent être différents de ceux du code source.
2. **Choisir les logs.** Ajouter un point Statement reached dans chaque branche, un message avant le choix de la politique et un point Variable value pour l’URL normalisée. Pour voir le résultat d’une instruction, placer le log avant l’instruction suivante.
3. **Tester l’application.** Activer les sondes de couverture et le graphe d’appels, puis instrumenter l’APK. Pour gagner du temps pendant la présentation, utiliser ensuite l’APK déjà instrumenté. Lancer Test on device, tester l’URL acceptée et cliquer sur Stop & analyze.
4. **Lire les résultats.** Montrer les points atteints et non atteints ainsi que l’URL enregistrée. Cliquer sur un point pour afficher son emplacement dans le code. La branche de refus est rouge dans cette capture ; elle est verte dans le rapport d’une exécution avec une URL refusée.
5. **Explorer le graphe.** Montrer l’appel à `UrlPolicy.allow` et l’implémentation appelée. Expliquer la différence entre une cible confirmée par la trace et une cible déclarée dont l’exécution n’a pas pu être confirmée.
6. **Partager les résultats.** Ouvrir le rapport HTML et montrer que l’APK instrumenté et le fichier de logs permettent de reprendre l’analyse sur une autre machine.

## Préparation

L’émulateur, le SDK et les fichiers seront préparés avant la présentation. Un APK déjà instrumenté permettra de ne pas attendre l’instrumentation pendant la démonstration.

Si le test sur l’émulateur ne fonctionne pas, nous utiliserons les logs et les rapports enregistrés à l’avance. Ils sont fournis avec le dossier, avec les étapes pour reproduire les trois scénarios.

## Limites

AndroLog ne détecte pas automatiquement les vulnérabilités et ne teste pas toutes les entrées possibles. La couverture et le graphe dépendent des actions effectuées et des logs collectés. La démonstration montre comment vérifier une hypothèse sur le comportement d’une application.
