# AndroLog: Instrumentation d'Apps Android en Boîte Noire

**Jordan Samhi · Université du Luxembourg · jordan.samhi@uni.lu**

## Couverture de code

AndroLog permet de mesurer la couverture de code d’une app Android à partir de son APK, sans avoir accès à son code source. Il propose plusieurs niveaux de granularité : instructions, méthodes, classes et composants Android. Il ajoute des logs dans l’APK pour savoir lesquels ont été exécutés. Après le test de l’app, il analyse les logs et génère un rapport qui permet de retrouver les parties couvertes et non couvertes dans le code.

AndroLog a été développé à l’origine dans un cadre académique, sous forme d’outil en ligne de commande. Il a été présenté à FSE 2024 à Porto de Galinhas, au Brésil ([DOI : 10.1145/3663529.3663806](https://doi.org/10.1145/3663529.3663806)). Depuis, son développement a continué avec de nouvelles fonctionnalités et une interface graphique.

## Nouvelle interface et logs personnalisés

AndroLog est multiplateforme et conçu pour macOS, Windows et Linux. Son interface graphique permet de choisir les informations à enregistrer, d’instrumenter un APK, de le tester sur un appareil connecté ou un émulateur, puis de consulter les résultats. L’APK instrumenté et les logs suffisent pour refaire l’analyse sur une autre machine. Le rapport peut aussi être exporté en HTML et ouvert sans connexion.

En plus de la couverture, il est maintenant possible d’ajouter des logs personnalisés à une instruction précise. L’utilisateur parcourt les packages, les classes et les méthodes, choisit une instruction, puis sélectionne l’une des trois options : **Statement reached** indique si l’instruction a été atteinte ; **Custom message** affiche un message choisi par l’utilisateur ; **Variable value** enregistre la valeur d’une variable locale initialisée. Les nombres, les booléens et les objets de tout type, y compris les chaînes, sont pris en charge.

Le rapport montre si chaque point a été observé et affiche la dernière valeur enregistrée. Il permet de revenir à l’instruction correspondante et d’explorer un graphe d’appels dynamique. Dans ce graphe, les cibles confirmées sont distinguées des cibles déclarées dont l’exécution n’a pas pu être confirmée.

## Présentation et démonstration

Cette présentation se concentre sur la nouvelle interface graphique et les fonctionnalités de logs personnalisés. Nous utiliserons une petite app qui compare un mot de passe saisi par l’utilisateur à un mot de passe fictif codé en dur. Nous verrons la valeur saisie, les branches exécutées et les méthodes appelées, avec un mot de passe incorrect puis correct. L’app, les APK, les logs et les rapports sont fournis pour reproduire la démonstration.

Le code affiché est du Jimple, une représentation du bytecode. Un point rouge signifie qu’il n’a pas été observé dans la capture, sans prouver qu’il est inaccessible. Un log est ajouté avant l’instruction : il indique qu’elle a été atteinte, pas qu’elle s’est terminée.

## Disponibilité

AndroLog est libre sous **LGPL v2.1** : [github.com/JordanSamhi/AndroLog](https://github.com/JordanSamhi/AndroLog). Le dossier comprend les sources, un rapport HTML et une version macOS Apple Silicon avec Java. Le SDK Android est nécessaire pour instrumenter ou analyser un APK, mais pas pour ouvrir le rapport.

Un outil d’IA a été utilisé pour améliorer le texte.
