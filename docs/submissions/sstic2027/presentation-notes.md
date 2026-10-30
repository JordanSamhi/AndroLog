# Notes de présentation — SSTIC 2027

Présentation prévue : 13 minutes, suivies de 2 minutes de questions. Les identifiants et l’app sont fictifs. Les captures de l’interface AndroLog explicitement marquées « autre app de démonstration » illustrent les fonctionnalités, mais ne montrent pas le rapport de cet exemple.

## 1. AndroLog : instrumentation d’apps Android en boîte noire — 0:30

Bonjour, je vais vous présenter AndroLog. L’outil permet d’instrumenter une app Android à partir de son APK, puis d’analyser ce qui s’est passé pendant son exécution. Je vais montrer un exemple de connexion pour illustrer les nouvelles fonctionnalités de logs personnalisés et l’interface graphique.

## 2. Que fait vraiment cette app ? — 1:00

On reçoit un APK sans son code source et on veut comprendre comment il se comporte. Une analyse statique permet de lire le code, mais on peut aussi vouloir savoir quelles instructions ont été exécutées, quelle valeur une variable prend à un moment précis, ou encore quelles méthodes ont été appelées pendant un test. Prenons une app avec un formulaire de connexion : comment retrouver le contrôle effectivement utilisé par l’app ?

## 3. La couverture à quatre niveaux — 1:00

AndroLog peut mesurer la couverture à plusieurs granularités : instructions, méthodes, classes et composants Android. Cela donne une vue de ce que le test a effectivement exécuté. Un élément non couvert n’est pas nécessairement inaccessible : il n’a simplement pas été observé lors de cette capture. La capture affichée sur cette diapositive concerne une autre app et sert uniquement à montrer l’interface.

## 4. D’un APK à un rapport — 1:00

On sélectionne un APK et les informations à enregistrer. AndroLog ajoute des instructions de log, puis produit et signe un APK instrumenté. On installe cet APK, on utilise l’app et on collecte les logs. On peut alors analyser les résultats dans l’interface ou dans un rapport HTML. L’APK instrumenté accompagne les logs lors de l’analyse : il permet notamment de retrouver les points de logs personnalisés attendus.

## 5. AndroLog : ligne de commande et interface — 1:00

AndroLog est à l’origine un outil en ligne de commande développé dans un cadre académique et présenté à FSE 2024 à Porto de Galinhas, au Brésil. Son développement a continué avec de nouvelles fonctionnalités et une interface graphique. L’outil est multiplateforme. L’interface montre les principales options d’instrumentation ; la capture affichée concerne une autre app.

## 6. Une piste dans MainActivity — 1:30

Dans l’app pédagogique, on trouve une classe MainActivity qui contient une véritable implémentation de l’écran et de la vérification, avec les chaînes « admin » et « admin123 ». On pourrait penser avoir trouvé les identifiants. On les essaie dans l’app lancée normalement : la connexion est refusée, comme sur la capture réelle à droite. Pourquoi ? L’extrait affiché est une simplification du code Java de l’app pédagogique ; AndroLog affiche une représentation du bytecode, le Jimple, et non le code source Java.

## 7. Quelle activité démarre vraiment ? — 1:00

Regardons le manifeste. MainActivity est bien déclarée, mais l’activité qui porte les catégories MAIN et LAUNCHER est MainActivity1. C’est donc elle qui s’ouvre lorsque l’utilisateur lance normalement l’app. Elle a sa propre logique de connexion. Le nom MainActivity était une piste trompeuse : il faut vérifier le point d’entrée déclaré dans le manifeste.

## 8. Observer les valeurs décodées — 1:30

Dans la méthode authenticate de MainActivity1, les deux valeurs attendues sont décodées juste avant les comparaisons avec les données saisies. Dans AndroLog, on choisit deux points « Variable value » : la variable locale $r5 après le premier décodage et $r6 après le second. Plus précisément, les points sont placés avant les instructions 24 et 25 dans le code initial affiché : $r5 est déjà définie au premier point et $r6 au second. Ce sont les valeurs attendues décodées, pas les valeurs saisies par l’utilisateur. On peut utiliser « Test on device », choisir l’appareil, lancer le test puis cliquer sur « Stop & analyze ». La capture de l’interface est celle d’une autre app : elle illustre le choix d’un point, pas la sélection exacte réalisée ici.

## 9. Les valeurs apparaissent dans les logs — 1:00

La capture de référence donne « demo » pour l’utilisateur et « androlog-demo » pour le mot de passe. Les deux points ont été observés, chacun deux fois pendant les essais. Les valeurs sont volontairement fictives. Ici, le décodage utilise un XOR et pourrait aussi être inversé par une analyse statique ; le but est de montrer ce que permet l’observation à l’exécution, pas de dire qu’elle est la seule solution.

## 10. Utiliser les valeurs retrouvées — 1:30

En essayant les valeurs retrouvées, l’app accepte la connexion. La capture à droite montre le résultat réel de l’app pédagogique. Dans le graphe d’appels dynamique issu de la capture instrumentée, on observe notamment submit vers authenticate, puis authenticate vers decode. La couverture de cette capture est de 179 instructions sur 360, 19 méthodes sur 51, 5 classes sur 18 et 1 activité sur 2. Ces chiffres correspondent à une capture cumulée comprenant un essai refusé puis un essai accepté : ce ne sont pas deux rapports indépendants. Les captures de l’app à droite et sur la diapositive 6 proviennent de tests séparés de l’app et illustrent les deux résultats.

## 11. Partager et reprendre l’analyse — 1:00

Le rapport HTML permet d’explorer le code, la couverture, les points personnalisés et le graphe d’appels dynamique dans un navigateur. On peut également reprendre une analyse avec l’APK instrumenté et les logs sur une autre machine. Le résultat présenté sur la diapositive est une capture de l’interface avec une autre app ; les résultats de notre exemple sont ceux donnés aux diapositives précédentes.

## 12. Ce qu’il faut retenir — 1:00

AndroLog part d’un APK et propose plusieurs niveaux de couverture. Les trois types de logs personnalisés permettent de vérifier qu’une instruction est atteinte, d’afficher un message choisi ou d’enregistrer la valeur d’une variable. On peut ensuite explorer les résultats et le graphe d’appels dynamique. Dans notre exemple, regarder le manifeste nous a conduits vers la bonne activité, puis les logs ont révélé les valeurs décodées. Cette obfuscation simple peut aussi être comprise statiquement. Le code et les instructions d’installation sont disponibles sur GitHub. Merci, je suis prêt à répondre à vos questions.

Sources de l’exemple : code de l’app AndroLogClassroomDemo, manifeste et capture instrumentée de référence réalisée le 30 octobre 2026. Les identifiants sont fictifs. Les captures des diapositives 6 et 10 montrent les résultats de tests de l’app ; les mesures et valeurs citées proviennent du rapport de couverture de la capture instrumentée cumulée.
