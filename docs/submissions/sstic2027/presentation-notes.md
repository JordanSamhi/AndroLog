# Notes pour la présentation SSTIC

**AndroLog: Instrumentation d'Apps Android en Boîte Noire**

Jordan Samhi · Université du Luxembourg · jordan.samhi@uni.lu

Durée prévue : 12 minutes 20 pour les diapositives et la démonstration, environ 40 secondes de transitions, puis 2 minutes de questions. Les captures et résultats présentés dans les diapositives sont issus de tests réels. L’app de mot de passe est une app pédagogique créée pour la démonstration.

## 1. AndroLog: Instrumentation d'Apps Android en Boîte Noire — 0:30

Bonjour, je vais vous présenter AndroLog, un outil qui permet d’instrumenter une app Android à partir de son APK. Il permet de voir quelles parties du code ont été exécutées et d’ajouter des logs à des endroits précis, sans avoir accès au code source de l’app.

Je vais d’abord expliquer comment fonctionne l’outil, puis vous montrer la nouvelle interface graphique et les logs personnalisés à travers un exemple concret.

## 2. Que fait vraiment cette app ? — 1:00

Imaginons que nous ayons un APK à analyser. Nous pouvons parcourir son code, mais cela ne nous dit pas forcément ce qui se passe lorsqu’on lance l’app avec une entrée particulière.

Prenons une app qui demande un mot de passe. Le contrôle est-il atteint ? Quelle valeur est comparée ? Quelle branche et quelle méthode sont exécutées si le mot de passe est correct ou incorrect ?

Ces questions sont utiles pendant un audit : elles permettent de vérifier ce que fait l’app pour une entrée donnée. AndroLog aide à y répondre en ajoutant des logs directement dans l’APK, puis en les reliant au code dans un rapport.

## 3. La couverture à quatre niveaux — 0:50

La première fonctionnalité d’AndroLog est la couverture de code. On peut la mesurer à plusieurs niveaux : instructions, méthodes, classes et composants Android, comme les activités.

Par exemple, après un test, on peut voir combien de méthodes ont été observées et lesquelles n’ont pas été observées. On peut ensuite ouvrir une classe et retrouver les instructions couvertes dans le code.

Cela permet de savoir quelle partie de l’app a été exercée pendant le test. Une partie non couverte n’est pas forcément inaccessible : elle n’a simplement pas été observée pendant cette capture. Il faudra peut-être utiliser une autre entrée ou suivre un autre parcours.

## 4. D’un APK à un rapport — 0:50

Le fonctionnement suit quatre étapes. On part d’un APK original et on choisit ce qu’on souhaite enregistrer. AndroLog ajoute les logs et produit un APK instrumenté. On installe ensuite cet APK sur un appareil ou un émulateur, puis on teste l’app.

Pendant l’exécution, les logs sont enregistrés. AndroLog les analyse et construit le rapport de couverture, les résultats des logs personnalisés et, si on l’a activé, le graphe d’appels dynamique.

L’utilisateur choisit lui-même ce qu’il veut tester dans l’app. AndroLog lui permet ensuite de voir ce qui a été observé pendant ce test.

## 5. AndroLog : de la ligne de commande à l’interface graphique — 0:40

AndroLog a d’abord été développé dans un cadre académique sous la forme d’un outil en ligne de commande. Il a été présenté à FSE 2024, à Porto de Galinhas, au Brésil.

Depuis, son développement a continué avec de nouvelles fonctionnalités et une interface graphique. AndroLog est multiplateforme et conçu pour macOS, Windows et Linux. L’interface regroupe les étapes que nous venons de voir : instrumenter l’APK, tester l’app et explorer les résultats.

La ligne de commande reste disponible. Aujourd’hui, je vais surtout montrer l’interface graphique et les logs personnalisés.

## 6. Une app avec un mot de passe codé en dur — 0:50

Pour la suite, nous utilisons une petite app créée pour cette démonstration. Elle demande un mot de passe et le compare à une valeur codée en dur : `sstic2027`. Si la comparaison réussit, elle affiche un secret fictif ; sinon, elle affiche une erreur.

Ce mot de passe est fictif et choisi pour être visible. On peut aussi le retrouver en lisant le code : AndroLog nous sert ici à observer ce que fait l’app quand on essaie différentes valeurs.

Nous allons essayer un mot de passe incorrect, puis le bon. Pour chaque test, nous regarderons quelle branche a été exécutée et quelle valeur a été enregistrée.

## 7. Choisir où ajouter un log — 1:10

Dans l’interface, je parcours les packages, les classes et les méthodes jusqu’à l’instruction qui m’intéresse. Le code affiché est du Jimple, une représentation du bytecode ; les noms de variables peuvent donc être différents de ceux du code source.

Trois options sont disponibles. Avec Statement reached, je vérifie si une instruction a été atteinte, sans avoir à saisir de message. Avec Custom message, je choisis le texte à afficher. Avec Variable value, je sélectionne une variable locale initialisée et j’enregistre sa valeur.

Dans notre exemple, nous plaçons un point dans chaque branche, le message « Vérification du mot de passe » avant le choix de la branche et un log pour enregistrer la valeur du mot de passe avant sa comparaison. Les logs sont ajoutés avant l’instruction choisie : pour voir le résultat d’une instruction, il faut donc choisir l’instruction suivante.

Les captures de l’interface qui montrent une variable de score et la valeur 14 proviennent d’une autre app de démonstration. Elles servent à illustrer l’interface ; les résultats du scénario de mot de passe sont ceux que je vais montrer maintenant.

## 8. Tester sur un appareil — 2:30

Pour gagner du temps, j’ai instrumenté l’APK avant la présentation et laissé cette session d’AndroLog ouverte. Je choisis Test on device, je sélectionne l’émulateur et je lance le test. L’outil installe l’APK, démarre la capture puis lance l’app.

Je saisis d’abord un mot de passe incorrect et je lance la vérification. Je clique ensuite sur Stop & analyze. AndroLog ouvre le tableau de couverture avec l’APK et les logs de cette exécution. Je peux refaire le test dans une nouvelle capture avec le bon mot de passe.

Je regarde d’abord la couverture, puis les logs personnalisés. On peut ouvrir un résultat pour revenir directement à l’instruction correspondante dans le code.

[En cas de problème pendant la démonstration, ouvrir les rapports `demo/incorrect/coverage.html` et `demo/correct/coverage.html` fournis avec le dossier. Préciser au public qu’il s’agit de captures précédentes. Ces captures ont été produites avec une entrée de test qui utilise le même traitement que le bouton de l’app.]


## 9. Deux tests, deux résultats — 1:00

Nous avons réalisé deux captures séparées avant la présentation. Avec `incorrect`, AndroLog a observé 58 instructions sur 61. Avec `sstic2027`, il en a observé 59 sur 61. Dans les deux cas, 7 méthodes sur 8 et 3 points personnalisés sur 4 ont été observés. Les chiffres d’une démonstration en direct peuvent différer.

Pour l’essai incorrect, `showError()` est vert et `showSecret()` est rouge. Pour l’essai correct, les couleurs sont inversées. Le point rouge indique qu’il n’a pas été observé dans la capture, pas qu’il est inaccessible.

Les valeurs enregistrées sont respectivement `incorrect` et `sstic2027`. On peut cliquer sur un résultat pour retrouver l’instruction où la valeur a été enregistrée. Le mot de passe est une valeur fictive choisie pour la démonstration.

## 10. Quelles méthodes ont été appelées ? — 1:20

Ouvrons maintenant le graphe d’appels dynamique pour la méthode `PasswordCheck.check`. Il permet de voir les méthodes appelées pendant l’exécution.

Dans la capture avec `incorrect`, le graphe confirme l’appel de `check()` vers `showError()`. Dans la capture avec `sstic2027`, il confirme l’appel de `check()` vers `showSecret()`.

Le graphe distingue les cibles confirmées par les logs des cibles déclarées dont l’exécution n’a pas pu être confirmée. Il décrit les appels observés pendant ces tests, pas tous les appels possibles de l’app.

## 11. Partager et reprendre l’analyse — 0:50

Si je veux transmettre une analyse à quelqu’un d’autre, je peux exporter le rapport en HTML. Il s’ouvre dans un navigateur sans connexion et permet de parcourir les résultats et le code.

Je peux aussi transmettre l’APK instrumenté et son fichier de logs. Ces deux fichiers, avec le bon tag, permettent de refaire l’analyse sur une autre machine, sans disposer de l’APK original ni de l’historique local d’instrumentation.

Pour le comité, nous fournissons les APK de démonstration, les captures et les rapports correspondants, ainsi que les étapes pour les reproduire.

## 12. Ce qu’il faut retenir — 0:50

AndroLog permet de mesurer la couverture d’une app Android sans son code source, à plusieurs niveaux de granularité. La nouvelle interface permet aussi d’ajouter des logs personnalisés, de tester l’APK et de relier les résultats au code.

Dans notre exemple, nous avons pu voir quelle valeur était comparée, quelle branche avait été atteinte et quelle méthode avait été appelée. Ce sont des observations pour les exécutions effectuées : un résultat non observé ne prouve pas qu’un code est inaccessible.

L’outil est libre et disponible sur GitHub. Merci pour votre attention, je suis prêt à répondre à vos questions.
