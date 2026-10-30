# AndroLog : analyser l’exécution d’un APK sans son code source

**Jordan Samhi · Université du Luxembourg · Présentation d’un outil, 15 minutes**

## Objectif

Lorsqu’on analyse une application Android, on ne dispose pas toujours de son code source. AndroLog permet d’ajouter des logs directement dans l’APK pour voir quelles parties du code sont exécutées, quelles valeurs prennent certaines variables et quelles méthodes sont appelées. Il génère ensuite un rapport qui relie ces informations au code de l’application.

AndroLog utilise Soot et AndroSpecter pour modifier l’APK et ajouter les logs choisis. Il signe ensuite l’APK pour l’installer sur un appareil de test ou un émulateur. L’utilisateur teste lui-même l’application.

## Fonctionnement

L’interface permet de parcourir les paquets, les classes et les méthodes, puis de choisir une instruction à laquelle ajouter un log. Trois options sont disponibles : **Statement reached** indique si l’instruction a été atteinte ; **Custom message** affiche un message choisi par l’utilisateur ; **Variable value** enregistre la valeur d’une variable locale initialisée. Cette dernière option prend en charge les nombres, les booléens, les chaînes et les objets. Pour les objets, AndroLog utilise `toString()`. Les logs sont ajoutés juste avant l’instruction choisie.

AndroLog peut aussi enregistrer la couverture des instructions, des méthodes, des classes et des composants Android, ainsi que les appels entre méthodes. Le rapport affiche les éléments observés, les logs personnalisés et la dernière valeur enregistrée pour chaque variable choisie. Il permet de revenir au code et d’explorer le graphe d’appels dynamique. Dans ce graphe, les cibles confirmées sont distinguées des cibles déclarées dont l’exécution n’a pas pu être confirmée.

L’interface permet de tester l’APK sur un appareil connecté et d’analyser les logs. L’APK instrumenté et ses logs suffisent pour refaire l’analyse sur une autre machine. Le rapport peut être exporté en HTML et ouvert sans connexion.

## Démonstration

La démonstration utilise une petite application qui reçoit une URL par lien profond, la normalise et choisit une politique de validation. Nous verrons quelle URL est transmise au contrôle, quelle implémentation est appelée et si l’URL est acceptée ou refusée. L’application, les APK, les logs et les rapports sont fournis pour reproduire la démonstration.

Le code affiché est du Jimple, une représentation du bytecode, et non le code source d’origine. Un point rouge signifie qu’il n’a pas été observé dans la capture ; cela ne prouve pas qu’il est inaccessible. Un log ajouté avant une instruction indique qu’elle a été atteinte, pas qu’elle s’est terminée. La modification et la signature de l’APK peuvent aussi changer le comportement de certaines applications.

## Disponibilité

AndroLog est libre et disponible sous **LGPL v2.1** : [github.com/JordanSamhi/AndroLog](https://github.com/JordanSamhi/AndroLog). Le dossier comprend les sources, un rapport HTML et une application macOS Apple Silicon avec Java. Le SDK Android est nécessaire pour instrumenter ou analyser un APK, mais pas pour ouvrir le rapport.

AndroLog a été présenté à FSE 2024 par Jordan Samhi et Andreas Zeller ([DOI : 10.1145/3663529.3663806](https://doi.org/10.1145/3663529.3663806)). La présentation proposée au SSTIC porte sur l’utilisation actuelle de l’outil pour l’audit, notamment l’interface, les logs personnalisés et l’analyse des captures. Codex a assisté le développement et la préparation du dossier ; les détails figurent dans le guide.
