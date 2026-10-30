# Démonstration : mot de passe codé en dur

Cette app Android a été créée pour la présentation d’AndroLog au SSTIC. Elle contient un mot de passe **fictif**, `sstic2027`, et un secret de démonstration sans valeur réelle. Elle ne demande aucune permission Android, ne fait pas de requête réseau et n’accède pas à des fichiers.

L’utilisateur saisit une valeur dans le champ puis appuie sur **Vérifier**. `PasswordCheck.check` compare cette valeur avec `sstic2027`. Si elle correspond, l’app appelle `showSecret()` et affiche « Accès autorisé : secret de démonstration ». Sinon, elle appelle `showError()` et affiche « Accès refusé ».

## Fichiers fournis

Dans l’archive du dossier SSTIC, le répertoire `demo/` contient :

- `original.apk` et `instrumented.apk` ;
- `custom-plan.json`, `points.json`, `audit-code.json` et `variables.json`, qui documentent les points ajoutés et le code Jimple ;
- `build-evidence.json`, `validation.json`, `ui-validation.json` et `SHA256SUMS` ;
- `incorrect/` et `correct/`, chacun avec `runtime.log`, `capture.json`, `coverage.json`, `coverage.html` et une capture `screen.png`.

Les sources et scripts de cette app sont fournis dans `docs/submissions/sstic2027/password-demo/` de l’archive des sources. Le code affiché par AndroLog est du Jimple reconstruit à partir de l’APK.

## Points personnalisés

La capture utilise le tag `SSTIC_PASSWORD` et active la couverture des instructions, méthodes, classes, composants, ainsi que le graphe d’appels dynamique. Quatre points personnalisés sont ajoutés :

| Point | Option | Emplacement |
| --- | --- | --- |
| Mot de passe saisi | Variable value | Variable locale avant l’appel à `String.equals` |
| Vérification du mot de passe | Custom message | Avant le branchement qui suit la comparaison |
| Accès autorisé | Statement reached | Dans `showSecret()` |
| Accès refusé | Statement reached | Dans `showError()` |

Les sondes sont placées avant l’instruction sélectionnée. Un point rouge n’a pas été observé dans la capture ; cela ne prouve pas qu’il est inaccessible. Le mot de passe et les valeurs utilisées ici sont fictifs : ne pas utiliser des identifiants réels pour la démonstration.

## Résultats observés

Deux captures indépendantes ont été faites sur un émulateur Android 16, API 36. Chaque capture démarre un nouveau processus de l’app ; elles ne sont pas cumulées.

| Capture | Valeur | Instructions | Méthodes | Points observés | Branche observée | Appel confirmé |
| --- | --- | --- | --- | --- | --- | --- |
| `incorrect` | `incorrect` | 58/61 (95,1 %) | 7/8 | 3/4 | `showError` | `check` vers `showError` |
| `correct` | `sstic2027` | 59/61 (96,7 %) | 7/8 | 3/4 | `showSecret` | `check` vers `showSecret` |

Dans chaque capture, l’autre branche est rouge. L’analyse vérifie également la valeur enregistrée et l’appel confirmé dans le graphe. Les chiffres correspondent uniquement à ces deux tests ; ils ne mesurent pas la couverture de toutes les utilisations possibles de l’app.

Pour collecter les captures de façon reproductible, le script fournit la valeur par un extra Intent réservé à la démonstration. Il remplit le champ et déclenche **le même gestionnaire de bouton** que la saisie normale. En plus, `test_ui.py` a vérifié les deux cas sur l’APK instrumenté en saisissant réellement les valeurs dans le champ et en appuyant sur le bouton. Les résultats de ce test sont dans `ui-validation.json`. Les captures et les tests manuels sont distincts.

## Lire les résultats

Ouvrir `demo/incorrect/coverage.html` ou `demo/correct/coverage.html` dans un navigateur. Les rapports HTML sont autonomes. Dans l’interface graphique d’AndroLog, ouvrir Coverage, sélectionner `demo/instrumented.apk`, le `runtime.log` de la capture voulue et le tag `SSTIC_PASSWORD`, puis cliquer sur **Analyze run**. Cliquer sur un point personnalisé pour retrouver son instruction dans Explore et ouvrir le graphe d’appels dynamique de `PasswordCheck`.

## Reproduire depuis le dépôt

Prérequis : JDK 17+, Node.js compatible avec AndroLog, Python 3, SDK Android (plateforme 36, Build Tools 36.1.0 et Platform Tools), ainsi qu’un émulateur ou un appareil de test. La construction de l’app n’utilise pas Gradle et ne télécharge pas de dépendances.

Depuis la racine du dépôt AndroLog, après avoir construit son JAR avec `mvn package` :

```sh
export ANDROID_HOME="/chemin/vers/Android/sdk"
export JAVA_HOME="/chemin/vers/le/jdk"
python3 docs/submissions/sstic2027/password-demo/build.py
node docs/submissions/sstic2027/password-demo/prepare.cjs
python3 docs/submissions/sstic2027/password-demo/capture.py --device emulator-5554
node docs/submissions/sstic2027/password-demo/analyze.cjs
python3 docs/submissions/sstic2027/password-demo/test_ui.py --device emulator-5554
```

Remplacer `emulator-5554` par le numéro affiché par `adb devices`. `capture.py` installe l’APK instrumenté, lance chaque scénario dans un nouveau processus et garde les lignes Logcat originales correspondant au PID et au tag. Il ne vide pas les journaux globaux. `analyze.cjs` vérifie les quatre points, les valeurs, les branches et les appels confirmés ; il échoue si un résultat ne correspond pas.

`test_ui.py` utilise le champ et le bouton visibles, en démarrant un nouveau processus pour chaque valeur. Il vérifie le texte affiché à l’écran. Utiliser un appareil de test : ce script pilote l’interface du package `com.example.andrologpassword` et arrête son processus.

Les chemins SDK et Java sont propres à chaque machine. Le certificat de test et les fichiers intermédiaires sont créés dans `target/sstic2027/password-demo/build/`. Ils ne sont pas fournis dans l’archive et ne doivent pas servir à signer une app de production. Les journaux de compilation et d’instrumentation peuvent contenir des chemins locaux et ne sont pas distribués.
