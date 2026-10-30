# Application fournie pour SSTIC 2027

Ce dossier contient AndroLog 0.1, son moteur Java et les éléments de provenance. La démonstration et ses traces sont dans le dossier `demo` de l’artefact complet.

## Démarrage sur Mac Apple Silicon

1. Vérifier les fichiers depuis ce dossier : `shasum -a 256 -c SHA256SUMS`.
2. Décompresser `AndroLog-0.1-SSTIC2027-macos-arm64.zip` dans un dossier local, puis déplacer `AndroLog.app` dans Applications et l’ouvrir. Java est inclus ; Java, Maven et Node.js ne sont pas à installer pour utiliser cette application.
3. Installer les **Android SDK Platforms** avec le SDK Manager d’Android Studio, puis choisir le dossier `sdk/platforms` dans **Settings → Android SDK platforms**. Pour la démo, installer la plateforme Android 36. Pour instrumenter, installer aussi les **Android SDK Build-Tools** et sélectionner leur dossier de version dans **Android Build Tools**. Pour tester sur un appareil, installer les **Android SDK Platform-Tools** (`adb`). Le SDK Android n’est pas inclus.
4. Laisser **Java executable** vide pour utiliser le runtime fourni. Les chemins SDK sont enregistrés sur votre ordinateur ; le JAR distribué ne contient aucun chemin de signature local.
5. Pour une première analyse sans téléphone : ouvrir **Coverage**, choisir `demo/instrumented.apk` puis `demo/incorrect/runtime.log`, saisir le tag `SSTIC_PASSWORD` et cliquer sur **Analyze run**. Résultat attendu : 82/85 instructions, 8/9 méthodes et trois points personnalisés observés sur quatre. La valeur décodée enregistrée est `sstic2027` ; la branche de refus est observée et celle de succès ne l’est pas.
6. Ouvrir **Explore report** pour parcourir le code et le graphe dynamique, ou exporter le rapport HTML. Les rapports HTML déjà fournis peuvent être consultés dans un navigateur sans installer le SDK.

L’archive a une signature **ad hoc**, sans notarisation Apple ni Developer ID. macOS peut bloquer son ouverture après téléchargement. Après vérification de la provenance et des empreintes, utiliser l’autorisation proposée dans **Réglages Système → Confidentialité et sécurité → Ouvrir quand même**. Ne pas désactiver globalement Gatekeeper. La validation locale ne reproduit pas le contrôle de notarisation d’un téléchargement sur un Mac vierge.

Éviter d’extraire l’application dans un dossier iCloud : ce service peut ajouter des métadonnées Finder aux sous-applications et faire échouer une vérification stricte de signature. Le ZIP livré a été extrait et vérifié dans un dossier local hors iCloud.

## Linux, Windows et autres architectures

Le fichier `androlog-0.1.jar` contient le moteur CLI et ses dépendances. Il nécessite Java 17 ou plus récent et le SDK Android. L’archive graphique fournie est exclusivement macOS ARM64. Aucune archive Windows, Linux ou Mac Intel n’a été validée pour ce dossier.

Exemple d’analyse en Bash/Zsh depuis la racine de l’artefact complet, en remplaçant le chemin SDK :

```bash
java -jar application/androlog-0.1.jar \
  -a demo/instrumented.apk -p /chemin/vers/sdk/platforms \
  -pam demo/incorrect/runtime.log -l SSTIC_PASSWORD \
  --instrumented-input -o resultat \
  -html resultat/coverage.html -j resultat/coverage.json
```

Pour l’interface graphique sur un autre système, les sources du dépôt permettent une construction native avec JDK 17+, Maven et Node.js 22.12+ : `mvn package`, puis `cd electron-gui`, `npm ci`, `npm start`. Pour produire une archive avec Java inclus, exécuter `npm run dist` sur le système et l’architecture cibles avec un JDK complet. Ce chemin de construction est documenté mais n’a pas été exécuté sur Windows ou Linux lors de cette préparation.

## Sources et licences

- AndroLog : licence LGPL 2.1 dans `LICENSE-AndroLog.txt`. Les sources AndroLog sont fournies séparément dans l’artefact complet ; `PROVENANCE.json` indique le commit fonctionnel public de référence.
- Le runtime réduit est issu d’**Eclipse Temurin 21.0.13+9**. Ses notices restent dans `AndroLog.app/Contents/Resources/runtime/legal`. Les sources correspondantes sont fournies dans `OpenJDK21U-jdk-sources_21.0.13_9.tar.gz` ; les notices principales sont également dans `licenses/`.
- `third-party-sources-and-notices.zip` contient les sources disponibles des dépendances Maven résolues, leurs POM et leurs notices embarquées, ainsi que les notices Electron/Chromium et anime.js. Son inventaire précise les trois dépendances sans archive de sources : deux JAR de métadonnées sans classes, et XMLPull, dont le POM indique le domaine public.
- `PROVENANCE.json` décrit les versions, les liens amont et les empreintes. `VALIDATION.json` décrit les contrôles effectués et leurs limites. Les binaires ne sont pas présentés comme reproductibles bit à bit : les outils de construction peuvent ajouter des horodatages et signatures.

Le SDK Android et l’émulateur restent des installations séparées. Aucun conteneur Docker n’est fourni ou annoncé comme testé ; Docker n’était pas installé sur la machine de validation.
