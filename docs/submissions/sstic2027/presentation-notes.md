# Notes de présentation — SSTIC 2027

Présentation prévue : 13 minutes, suivies de 2 minutes de questions. Les identifiants et l’app sont fictifs. Les captures de l’interface AndroLog explicitement marquées « autre app de démonstration » illustrent les fonctionnalités, mais ne montrent pas le rapport de cet exemple.

## 1. AndroLog : instrumentation d’apps Android en boîte noire — 0:30

Bonjour, je vais vous présenter AndroLog. L’outil permet d’instrumenter une app Android à partir de son APK, puis d’analyser ce qui s’est passé pendant son exécution. Je vais montrer comment observer une valeur pendant l’exécution, à travers une app de connexion, pour illustrer les nouvelles fonctionnalités de logs personnalisés et l’interface graphique.

## 2. Que fait vraiment cette app ? — 1:00

On reçoit un APK sans son code source et on veut comprendre comment il se comporte. Une analyse statique permet de lire le code, mais on peut aussi vouloir savoir quelles instructions ont été exécutées, quelle valeur une variable prend à un moment précis, ou encore quelles méthodes ont été appelées pendant un test. Prenons une app avec un formulaire de connexion : comment observer les valeurs qu’elle utilise pour vérifier les identifiants ?

## 3. La couverture à quatre niveaux — 1:00

AndroLog peut mesurer la couverture à plusieurs granularités : instructions, méthodes, classes et composants Android. Cela donne une vue de ce que le test a effectivement exécuté. Un élément non couvert n’est pas nécessairement inaccessible : il n’a simplement pas été observé lors de cette capture. La capture affichée sur cette diapositive concerne une autre app et sert uniquement à montrer l’interface.

## 4. D’un APK à un rapport — 1:00

On sélectionne un APK et les informations à enregistrer. AndroLog ajoute des instructions de log, puis produit et signe un APK instrumenté. On installe cet APK, on utilise l’app et on collecte les logs. On peut alors analyser les résultats dans l’interface ou dans un rapport HTML. L’APK instrumenté accompagne les logs lors de l’analyse : il permet notamment de retrouver les points de logs personnalisés attendus.

## 5. AndroLog : ligne de commande et interface — 1:00

AndroLog est à l’origine un outil en ligne de commande développé dans un cadre académique et présenté à FSE 2024 à Porto de Galinhas, au Brésil. Son développement a continué avec de nouvelles fonctionnalités et une interface graphique. L’outil est multiplateforme. L’interface montre les principales options d’instrumentation ; la capture affichée concerne une autre app.

## 6. Une app de connexion à analyser — 1:30

Pour la démonstration, nous avons une app de connexion et son APK. Les valeurs attendues sont encodées dans l’app puis décodées avant la vérification. Nous voulons voir les valeurs effectivement utilisées pendant l’exécution. Nous pourrions analyser le décodage statiquement ; ici, nous allons observer son résultat directement avec AndroLog. Les valeurs de cet exemple sont fictives.

## 7. Choisir le bon point dans le code — 1:00

Dans le code de MainActivity1, la méthode authenticate décode d’abord les deux valeurs, puis les compare aux données saisies. AndroLog affiche le Jimple, une représentation du bytecode. L’extrait présenté est simplifié : après l’instruction 23, $r5 contient l’utilisateur décodé ; après l’instruction 24, $r6 contient le mot de passe décodé. Nous plaçons un point avant l’instruction 24 pour observer $r5, puis un autre avant l’instruction 25 pour observer $r6. Ainsi, chaque variable est définie au moment où elle est enregistrée.

## 8. Observer les valeurs décodées — 1:30

Dans la méthode authenticate de MainActivity1, les deux valeurs attendues sont décodées juste avant les comparaisons avec les données saisies. Dans AndroLog, on choisit deux points « Variable value » : la variable locale $r5 après le premier décodage et $r6 après le second. Plus précisément, les points sont placés avant les instructions 24 et 25 dans le code initial affiché : $r5 est déjà définie au premier point et $r6 au second. Ce sont les valeurs attendues décodées, pas les valeurs saisies par l’utilisateur. On peut utiliser « Test on device », choisir l’appareil, lancer le test puis cliquer sur « Stop & analyze ». La capture de l’interface est celle d’une autre app : elle illustre le choix d’un point, pas la sélection exacte réalisée ici.

## 9. Les valeurs apparaissent dans les logs — 1:00

La capture de référence donne « demo » pour l’utilisateur et « androlog-demo » pour le mot de passe. Les deux points ont été observés, chacun deux fois pendant les essais. Les valeurs sont volontairement fictives. Ici, le décodage utilise un XOR et pourrait aussi être inversé par une analyse statique ; le but est de montrer ce que permet l’observation à l’exécution, pas de dire qu’elle est la seule solution.

## 10. Utiliser les valeurs retrouvées — 1:30

En essayant les valeurs retrouvées, l’app accepte la connexion. La capture à droite montre le résultat réel de l’app pédagogique. Dans le graphe d’appels dynamique issu de la capture instrumentée, on observe notamment submit vers authenticate, puis authenticate vers decode. La couverture de cette capture est de 179 instructions sur 360, 19 méthodes sur 51, 5 classes sur 18 et 1 activité sur 2. Ces chiffres correspondent à une capture cumulée comprenant un essai refusé puis un essai accepté : ce ne sont pas deux rapports indépendants. La capture à droite provient d’un test séparé de l’app et illustre la connexion réussie.

## 11. Au-delà de cet exemple — 1:00

La même approche peut servir à observer d’autres valeurs pendant un test : par exemple une clé d’API, un jeton, un mot de passe ou une autre donnée sensible. Ces valeurs peuvent être décodées, calculées ou reçues par l’app. On choisit le point où la valeur est disponible et on observe ce qui est effectivement présent pendant cette exécution. Selon l’app, certaines informations peuvent aussi être retrouvées par analyse statique ; d’autres ne sont disponibles qu’après réception ou calcul pendant le test. Les résultats dépendent du scénario exécuté.

## 12. Ce qu’il faut retenir — 1:00

AndroLog part d’un APK et propose plusieurs niveaux de couverture. Les trois types de logs personnalisés permettent de vérifier qu’une instruction est atteinte, d’afficher un message choisi ou d’enregistrer la valeur d’une variable. On peut ensuite explorer les résultats et le graphe d’appels dynamique. Dans notre exemple, les logs ont révélé les valeurs décodées avant leur comparaison. La même approche peut aider à examiner d’autres données sensibles pendant un test. Cette obfuscation simple peut aussi être comprise statiquement. Le code et les instructions d’installation sont disponibles sur GitHub. Merci, je suis prêt à répondre à vos questions.

Sources de l’exemple : code de l’app AndroLogClassroomDemo, manifeste et capture instrumentée de référence réalisée le 30 octobre 2026. Les identifiants sont fictifs. La capture de la diapositive 10 montre le résultat d’un test de l’app ; les mesures et valeurs citées proviennent du rapport de couverture de la capture instrumentée cumulée.
