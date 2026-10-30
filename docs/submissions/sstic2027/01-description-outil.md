# AndroLog : observer l’exécution d’un APK sans son code source

**Jordan Samhi · Université du Luxembourg · Présentation d’un outil, 15 minutes**

## Du code suspect à une observation

Lors de l’audit d’une application Android, lire le code ne suffit pas toujours : un contrôle est-il effectivement atteint ? Quelle valeur reçoit-il après transformation ? Quelle implémentation est appelée derrière une interface ? AndroLog permet d’ajouter des observations à un APK, sans disposer du projet Java ou Kotlin, puis de les retrouver dans un rapport lié au code.

L’outil réécrit le bytecode avec Soot et AndroSpecter, ajoute les sondes choisies et produit un APK signé pour le test. Son interface de bureau réunit l’instrumentation, la capture sur un téléphone ou un émulateur et l’analyse après exécution. L’analyste conserve la main sur les entrées et les parcours à tester.

## Trois modes de journalisation

Dans l’arborescence paquet → classe → méthode, l’analyste sélectionne une instruction Jimple et choisit **Statement reached**, pour enregistrer son passage sans saisir de texte ; **Custom message**, pour ajouter un message ; ou **Variable value**, pour capturer une variable locale initialisée. Les nombres, booléens, chaînes et références d’objets sont pris en charge ; les objets utilisent leur représentation `toString()`. Les observations sont insérées avant l’instruction choisie.

Des sondes automatiques mesurent aussi la couverture des instructions, méthodes, classes et composants Android. Le graphe d’appels dynamique distingue les cibles confirmées à l’exécution des cibles seulement déclarées. Le rapport affiche les points observés ou absents de la trace, la dernière valeur capturée et leur emplacement dans le code. Il s’exporte en HTML autonome. L’APK instrumenté et ses logs suffisent à reprendre l’analyse sur une autre machine ; les sondes d’AndroLog sont exclues des statistiques.

## Démonstration proposée au SSTIC

Une petite application reçoit une URL par lien profond, la normalise et choisit une politique de validation. Nous observerons la valeur normalisée, les branches acceptée et refusée et l’implémentation réellement appelée. Le scénario illustre comment vérifier une hypothèse d’audit, sans présenter cette application pédagogique comme une vulnérabilité découverte. Son code, ses APK, les traces et un rapport sont fournis pour reproduire la démonstration.

Le code affiché est une représentation intermédiaire, pas le code source d’origine. Un point non observé n’est pas nécessairement inaccessible ; une instruction atteinte peut ensuite échouer. La réécriture, la signature et les logs peuvent modifier le comportement de l’application. Le graphe reste limité à ce que l’instrumentation et la capture permettent d’observer.

## Disponibilité et contribution

AndroLog est libre sous **LGPL v2.1** : [github.com/JordanSamhi/AndroLog](https://github.com/JordanSamhi/AndroLog). Le dossier comprend un paquet macOS Apple Silicon avec Java, les sources et un parcours d’évaluation hors ligne par rapport HTML. Le SDK Android est nécessaire pour instrumenter ou réanalyser un APK ; aucun appareil n’est requis pour consulter le rapport fourni.

AndroLog a été présenté à FSE 2024 par Jordan Samhi et Andreas Zeller ([DOI : 10.1145/3663529.3663806](https://doi.org/10.1145/3663529.3663806)). Cette proposition porte sur le parcours d’audit interactif, les trois modes de journalisation personnalisée et l’analyse portable des captures. Codex a assisté le développement et la préparation du dossier ; le détail et la publication antérieure sont déclarés dans l’annexe.
