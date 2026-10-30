# Évaluer AndroLog

**Version de l’outil : 0.1 · Dossier SSTIC 2027 · 30 octobre 2026**

## Ouvrir le rapport

Décompresser `AndroLog-SSTIC2027-artefact.zip`, puis ouvrir `demo/incorrect/coverage.html` dans un navigateur. Ce rapport est autonome : il ne nécessite ni installation, ni serveur, ni connexion Internet. Il permet de voir les résultats avant d’installer AndroLog.

Le rapport présente la couverture des instructions, des méthodes, des classes et des composants Android. Il montre aussi les logs personnalisés et le graphe d’appels dynamique. Le dossier `demo/` contient l’APK original, l’APK instrumenté et deux captures : `incorrect` et `correct`. Chaque capture comprend `runtime.log`, `capture.json`, `coverage.json` et `coverage.html`. Les fichiers SHA-256 permettent de vérifier leur intégrité.

## Lancer l’interface

Décompresser `application/AndroLog-0.1-SSTIC2027-macos-arm64.zip`, puis ouvrir AndroLog. Cette version fonctionne sur **macOS avec Apple Silicon** et comprend Java. Elle n’est pas notariée par Apple ; si macOS bloque son ouverture, les instructions de l’archive expliquent comment autoriser AndroLog sans désactiver les protections du système.

Dans Settings, sélectionner les dossiers `platforms` et `build-tools/<version>` du SDK Android. Pour analyser une capture, ouvrir Coverage, choisir `demo/instrumented.apk` et `demo/incorrect/runtime.log`, entrer le tag `SSTIC_PASSWORD`, puis cliquer sur Analyze run. L’APK original et l’historique local d’instrumentation ne sont pas nécessaires. La couverture apparaît par niveau de granularité ; les points personnalisés indiquent ensuite si une instruction a été atteinte, quel message a été affiché ou quelle valeur a été enregistrée.

## Prérequis

| Utilisation | Prérequis |
| --- | --- |
| Ouvrir le rapport fourni | Un navigateur récent. Pas de SDK, d’appareil ou de Java à installer. |
| Analyser un APK instrumenté | L’interface fournie ou le JAR, Java 17+ s’il n’est pas intégré et Android SDK Platforms. |
| Instrumenter un APK | Les prérequis précédents et Android SDK Build Tools, avec zipalign et apksigner. |
| Collecter de nouveaux logs | Platform Tools / adb et un émulateur ou un téléphone Android autorisé pour le débogage USB. |
| Compiler les sources | JDK 17+, Maven, Node.js 22.12+ et npm ; une connexion pour télécharger les dépendances. |

Aucun abonnement, logiciel payant ou matériel spécialisé n’est nécessaire. Le SDK Android et les images d’émulateur sont soumis aux licences de leurs éditeurs et ne sont pas inclus. AndroLog est multiplateforme et peut aussi être construit pour Windows et Linux, mais ces versions n’ont pas été testées pour ce dossier. Le JAR et les sources sont fournis pour permettre leur utilisation sur ces systèmes.

<!-- pagebreak -->

# Reproduire la démonstration

## App et scénarios

L’app `com.example.andrologpassword` propose un champ de saisie et un bouton « Vérifier ». Elle compare la valeur saisie au mot de passe fictif `sstic2027`, codé en dur dans l’app, puis affiche si l’accès est autorisé ou refusé. L’app a été créée pour cette démonstration ; elle n’utilise pas le réseau ni des fichiers.

| Capture | Mot de passe saisi | Résultat attendu |
| --- | --- | --- |
| incorrect | `incorrect` | Accès refusé ; branche d’échec observée. |
| correct | `sstic2027` | Accès autorisé ; branche de succès observée. |

Les quatre points personnalisés utilisent les trois options : **Statement reached** vérifie le passage dans les branches, **Custom message** affiche un message et **Variable value** enregistre la valeur saisie. Le code de l’app et les commandes de reproduction sont fournis dans `docs/submissions/sstic2027/password-demo/`, dans l’archive des sources.

Dans la capture `incorrect`, 58 instructions sur 61 et 7 méthodes sur 8 sont observées. Le point `showError` est atteint, le point `showSecret` ne l’est pas et l’appel à `showError` est confirmé dans le graphe. Dans la capture `correct`, 59 instructions sur 61 et 7 méthodes sur 8 sont observées ; les points et l’appel confirmé correspondent à `showSecret`. Chaque capture a trois points personnalisés observés sur quatre. Ces chiffres décrivent uniquement ces tests.

Pour les captures fournies, un paramètre de test remplit le champ et déclenche le même traitement que le bouton. L’app permet aussi de saisir le mot de passe dans l’interface. Le mot de passe fictif est visible dans le code : AndroLog permet de vérifier le comportement pendant les tests, et non de revendiquer une vulnérabilité découverte.

Pour refaire la démonstration, ouvrir l’APK original dans Instrument, choisir les points de logs et activer les sondes de couverture et de graphe. Instrumenter l’APK et utiliser Test on device. Faire une capture par entrée et conserver l’APK utilisé avec ses logs et son tag.

## Comment lire les résultats

- **Couverture.** Les chiffres portent sur les éléments pour lesquels une sonde a été ajoutée. Ils indiquent le nombre d’éléments différents observés, pas leur fréquence d’exécution. Le code ajouté par AndroLog est exclu des statistiques.
- **Point atteint.** Le log est placé avant l’instruction. Il indique que l’instruction a été atteinte, mais pas qu’elle s’est terminée. Un point rouge signifie qu’il n’a pas été observé dans les logs fournis.
- **Valeur.** Le rapport affiche notamment la dernière valeur enregistrée. Il ne donne pas l’historique complet des valeurs. Pour un objet, `toString()` peut exécuter du code de l’app et avoir des effets.
- **Graphe.** Une cible confirmée par les logs et une cible seulement déclarée ne donnent pas la même information. Le graphe ne montre pas nécessairement tous les liens entre callbacks, appels natifs, réflexion ou code non instrumenté.
- **APK modifié.** La modification et la nouvelle signature peuvent échouer ou déclencher des contrôles de l’app. Les logs ajoutent aussi un coût d’exécution. Nous ne donnons pas de mesure générale de ce coût ou de la compatibilité.
- **Logs.** Il faut utiliser l’APK installé pour le test et le bon tag. Une capture tardive ou incomplète peut manquer des événements : l’absence d’un point dans les logs ne prouve pas qu’il est inaccessible.

<!-- pagebreak -->

# Sources et déclarations

## Versions et fichiers

Le code fonctionnel utilisé est public au commit `cfc8f9924c9368d59059262dee599ad0ccc963df` : [version du dépôt](https://github.com/JordanSamhi/AndroLog/tree/cfc8f9924c9368d59059262dee599ad0ccc963df). Le dossier comprend aussi une archive des sources avec la documentation et le code de la démonstration. Le manifeste indique les versions et les empreintes des fichiers fournis.

Le dossier `application/` contient l’interface graphique, le JAR, les notices et les éléments de licence du Java intégré. `demo/` contient les APK, les logs et les rapports. `documents/` contient la description, la présentation et ce guide. Les fichiers de validation indiquent les vérifications effectuées. AndroLog est disponible sous [LGPL v2.1](https://github.com/JordanSamhi/AndroLog/blob/cfc8f9924c9368d59059262dee599ad0ccc963df/LICENSE).

## Publication précédente

AndroLog a d’abord été développé dans un cadre académique comme outil en ligne de commande. Jordan Samhi et Andreas Zeller ont publié « AndroLog: Android Instrumentation and Code Coverage Analysis » dans FSE Companion 2024, pages 597–601, [DOI 10.1145/3663529.3663806](https://doi.org/10.1145/3663529.3663806). L’outil a été présenté le 17 juillet 2024 à FSE, à Porto de Galinhas, au Brésil. Une prépublication est disponible depuis le 17 avril 2024 ([arXiv:2404.11223](https://arxiv.org/abs/2404.11223)).

Depuis, son développement a continué avec de nouvelles fonctionnalités et une interface graphique. La proposition SSTIC présente la couverture de code, puis la nouvelle interface graphique et les trois types de logs personnalisés. Elle montre aussi le test sur appareil et la lecture des résultats dans le code, ainsi que la reprise de l’analyse sur une autre machine avec l’APK instrumenté et les logs.

L’instrumentation et la couverture faisaient déjà partie des travaux précédents. L’existence d’un graphe d’appels dynamique n’est pas présentée comme une nouveauté de cette soumission. Le dossier ne présente pas les anciens résultats de compatibilité comme des mesures de la version actuelle. Son texte est nouveau et ne reprend pas de figure de l’article ACM.

## Utilisation de l’IA

Codex a aidé au développement d’AndroLog, notamment pour l’interface graphique, l’instrumentation et les rapports. Il a aussi été utilisé pour préparer la démonstration, automatiser des vérifications et rédiger ce dossier en français. Les logs et les résultats fournis viennent d’exécutions réelles de l’app de démonstration.

## Liens

- [Dépôt AndroLog et installation](https://github.com/JordanSamhi/AndroLog)
- [Présentation à FSE 2024](https://2024.esec-fse.org/details/fse-2024-demonstrations/11/AndroLog-Android-Instrumentation-and-Code-Coverage-Analysis)
- [Appel à soumission SSTIC 2027](https://www.sstic.org/2027/cfp/)

Contact : **Jordan Samhi · Université du Luxembourg · jordan.samhi@uni.lu**
