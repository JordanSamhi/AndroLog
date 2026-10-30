# Évaluer AndroLog

**Version de l’outil : 0.1 · Dossier SSTIC 2027 · 30 octobre 2026**

## Commencer par le rapport fourni

Décompresser `AndroLog-SSTIC2027-artefact.zip`, puis ouvrir `demo/accepted/coverage.html` dans un navigateur. Le rapport est autonome et se consulte sans installation, sans serveur et sans connexion réseau. Cette première étape montre le résultat de l’analyse ; elle ne remplace pas l’essai de l’outil.

Le répertoire `demo/` contient l’APK original, l’APK instrumenté, les points personnalisés et trois captures distinctes : `accepted`, `rejected` et `deny-policy`. Chaque capture comprend `runtime.log`, `capture.json`, `coverage.json` et `coverage.html`. Les empreintes SHA-256 de l’artefact permettent de contrôler les fichiers reçus.

## Lancer l’interface

Décompresser `application/AndroLog-0.1-SSTIC2027-macos-arm64.zip`, puis ouvrir l’application AndroLog. Ce paquet cible **macOS sur Apple Silicon** et comprend Electron et un runtime Java. Il n’est pas notarié par Apple ; si macOS bloque son ouverture, consulter les instructions du paquet pour autoriser cette application localement. Il n’est pas nécessaire de désactiver globalement les protections du système.

Dans Settings, sélectionner les répertoires `platforms` et `build-tools/<version>` de votre SDK Android. Pour réanalyser une capture, ouvrir Coverage, sélectionner `demo/instrumented.apk` et `demo/accepted/runtime.log`, saisir le tag `SSTIC_DEMO`, puis cliquer Analyze run. Aucun APK original ni historique local d’instrumentation n’est nécessaire pour cette analyse.

## Prérequis selon l’usage

| Usage | Prérequis |
| --- | --- |
| Consulter les résultats fournis | Navigateur récent. Aucun SDK, appareil ou runtime Java à installer. |
| Réanalyser un APK instrumenté | Interface fournie ou JAR, Java 17+ si non embarqué, SDK Android Platforms. |
| Instrumenter un autre APK | Prérequis précédents et SDK Build Tools, avec zipalign et apksigner. |
| Capturer une nouvelle exécution | Platform Tools / adb et un émulateur ou téléphone Android autorisé pour le débogage USB. Aucun matériel spécialisé. |
| Construire depuis les sources | JDK 17+, Maven, Node.js 22.12+ et npm ; accès réseau pour les dépendances. |

Aucun abonnement ou logiciel payant n’est requis. Le SDK Android et les images d’émulateur restent soumis aux licences de leurs éditeurs ; ils ne sont pas redistribués dans le paquet. L’interface cible aussi Windows et Linux, mais les paquets de ces systèmes ne font pas partie de la validation de ce dossier. Le JAR et les sources sont fournis pour ces environnements.

<!-- pagebreak -->

# Reproduire les observations

## Trois entrées, trois captures séparées

L’application pédagogique `com.example.andrologsstic` transmet une URL à `UrlAudit.inspect`. Une implémentation de `UrlPolicy` décide ensuite si elle est acceptée. `HostPolicy` vérifie le schéma HTTPS et l’hôte `trusted.example` ; `DenyPolicy` refuse systématiquement. L’application affiche une décision, sans requête réseau ni accès aux fichiers.

| Capture | Entrée et politique | Observation recherchée |
| --- | --- | --- |
| accepted | URL HTTPS de trusted.example, entourée d’espaces ; politique HostPolicy | URL normalisée sans espaces ; décision vraie ; branche acceptée observée, branche refusée absente. |
| rejected | https://untrusted.example/account ; politique HostPolicy | Décision fausse ; branche refusée observée, branche acceptée absente. |
| deny-policy | URL HTTPS de trusted.example ; politique DenyPolicy | Décision fausse malgré l’hôte de confiance ; autre implémentation appelée. |

Les six points couvrent les trois modes de l’interface : passage à l’entrée et dans les branches ; message personnalisé ; valeur de l’URL normalisée et du booléen de décision. Les scripts et le protocole exact se trouvent dans les sources jointes, sous `docs/submissions/sstic2027/demo/`. Les chemins de SDK sont fournis par l’utilisateur et ne sont pas figés dans les sources distribuées.

Sur la capture fournie `accepted`, cinq points sur six sont observés : la branche de refus est absente. La valeur capturée est `https://trusted.example/account`, la décision vaut `true` et l’appel à `HostPolicy.allow` est confirmé. La couverture est de 62 instructions sur 77 et de 9 méthodes sur 12. Avec `deny-policy`, la décision vaut `false` et l’appel à `DenyPolicy.allow` est confirmé. Ces chiffres décrivent uniquement les captures fournies.

Pour refaire l’essai dans l’interface, ouvrir l’APK original dans Instrument, choisir les sondes et les points, puis utiliser Test on device. Démarrer une nouvelle capture pour chaque entrée afin de conserver la distinction entre les parcours. Le protocole joint fournit également les commandes de déclenchement des liens profonds et la procédure automatisée complète.

## Lire les preuves, sans surinterpréter

- **Couverture.** Les totaux portent sur les sondes du périmètre instrumenté. Ils comptent des éléments distincts observés, pas leur fréquence d’exécution. Les sondes et classes auxiliaires ajoutées par AndroLog sont exclues.
- **Point atteint.** La sonde s’exécute avant l’instruction. Elle prouve son passage, pas que l’instruction a terminé avec succès. Un point rouge signifie qu’aucune observation correspondante ne figure dans la capture.
- **Valeur.** Le rapport montre notamment la dernière valeur capturée. Il ne constitue pas un historique exhaustif de toutes les valeurs. Une conversion d’objet par toString() peut exécuter du code de l’application et avoir des effets.
- **Graphe.** Une arête confirmée et une arête vers une cible déclarée n’ont pas la même force probante. La trace ne reconstruit pas tous les liens causaux des callbacks, du code natif, de la réflexion ou des appels non instrumentés.
- **Application réécrite.** Le reconditionnement et la nouvelle signature peuvent échouer ou déclencher des contrôles de l’application. Les logs ajoutent un coût d’exécution ; aucune mesure générale de surcoût ou de compatibilité n’est revendiquée ici.
- **Capture.** Utiliser l’APK exact et le tag exact de l’essai. Une capture tardive ou incomplète peut manquer des observations ; l’absence dans le log n’est pas une preuve d’inaccessibilité.

<!-- pagebreak -->

# Provenance, disponibilité et déclarations

## Versions et fichiers

Le code fonctionnel de référence est public au commit `cfc8f9924c9368d59059262dee599ad0ccc963df` : [version du dépôt](https://github.com/JordanSamhi/AndroLog/tree/cfc8f9924c9368d59059262dee599ad0ccc963df). Le dossier inclut une archive des sources avec ses ajouts de documentation et de démonstration. Le manifeste de construction identifie précisément les versions et les empreintes du paquet effectivement fourni.

Le répertoire `application/` contient le paquet de bureau, le JAR, les notices et les éléments de licence du Java embarqué. `demo/` contient les résultats de la démonstration. `documents/` contient cette annexe, la description d’une page et le plan de présentation. Les fichiers de validation décrivent les contrôles réellement exécutés. Le dépôt principal reste disponible sous [LGPL v2.1](https://github.com/JordanSamhi/AndroLog/blob/cfc8f9924c9368d59059262dee599ad0ccc963df/LICENSE).

## Publication antérieure et apport de cette proposition

Jordan Samhi et Andreas Zeller ont publié « AndroLog: Android Instrumentation and Code Coverage Analysis » dans FSE Companion 2024, pages 597–601, [DOI 10.1145/3663529.3663806](https://doi.org/10.1145/3663529.3663806). La présentation a eu lieu le 17 juillet 2024 dans la session Demonstrations ; une prépublication est disponible depuis le 17 avril 2024 ([arXiv:2404.11223](https://arxiv.org/abs/2404.11223)).

Cette proposition SSTIC présente le même logiciel dans son usage actuel pour l’audit : interface de bureau, sélection interactive des trois types de points, capture intégrée sur appareil, puis lecture des valeurs et de la couverture dans un rapport lié au code. La reprise d’analyse avec l’APK instrumenté et ses logs permet de transmettre une capture à un autre analyste.

Le socle d’instrumentation et de couverture est un travail antérieur. L’existence d’un graphe dynamique n’est pas revendiquée comme une nouveauté de cette soumission. Aucun taux historique de succès d’instrumentation n’est présenté comme une mesure de la version actuelle. Le texte du dossier est nouveau et ne reproduit ni texte ni figure de l’article ACM.

## Assistance par IA

Codex a assisté le développement d’AndroLog, notamment l’interface et les évolutions d’instrumentation et de rapport. Il a également servi à préparer le scénario de démonstration, automatiser des vérifications et rédiger et mettre en forme ce dossier en français. Les logs et résultats fournis proviennent d’exécutions effectives de l’application pédagogique ; ils ne sont pas des traces fabriquées pour illustrer un résultat.

## Références pratiques

- [Dépôt AndroLog et installation](https://github.com/JordanSamhi/AndroLog)
- [Présentation originale à FSE 2024](https://2024.esec-fse.org/details/fse-2024-demonstrations/11/AndroLog-Android-Instrumentation-and-Code-Coverage-Analysis)
- [Appel à soumission SSTIC 2027](https://www.sstic.org/2027/cfp/)

Contact : **Jordan Samhi · Université du Luxembourg · j.samhi@me.com**
