# Démonstration reproductible : suivre une URL jusqu’à sa décision

Cette petite application pédagogique reçoit une URL par lien profond, retire les espaces aux extrémités et normalise le schéma. Elle sélectionne ensuite une implémentation de `UrlPolicy` et affiche « ACCEPTÉE » ou « REFUSÉE ». Elle ne visite jamais l’URL, n’ouvre aucun fichier et ne demande aucune permission Android. Il ne s’agit ni d’une vulnérabilité réelle ni d’une bibliothèque de validation d’URL à réutiliser en production.

Le scénario représente une question d’audit : **quelle valeur atteint le contrôle, quelle implémentation est appelée et quelle branche est effectivement parcourue ?** L’analyste peut répondre à partir de l’APK et de ses journaux. Les sources sont fournies pour reproduire et vérifier la démonstration ; elles ne sont pas nécessaires à son analyse avec AndroLog.

## Ouvrir les résultats, sans appareil

Les fichiers générés se trouvent dans `target/sstic2027/demo/` dans le dépôt, ou dans le dossier de démonstration de l’archive de soumission.

1. Ouvrir `accepted/coverage.html` dans un navigateur : le rapport est autonome.
2. Dans l’interface graphique d’AndroLog, choisir **Coverage**, charger `instrumented.apk` et `accepted/runtime.log`, puis utiliser le tag `SSTIC_DEMO` et lancer l’analyse.
3. Examiner les six points personnalisés. Le point de `rejected` reste rouge ; l’URL normalisée et le booléen sont affichés avec leur valeur réellement capturée.
4. Cliquer sur un point pour rejoindre son instruction dans **Explore**. Sélectionner `UrlAudit` dans l’arbre, puis ouvrir le graphe dynamique. L’appel à l’interface `UrlPolicy.allow` mène à la cible confirmée `HostPolicy.allow`.
5. Refaire l’analyse avec `rejected/runtime.log`, puis `deny-policy/runtime.log`, en conservant la même APK. Dans le dernier cas, la cible confirmée devient `DenyPolicy.allow`.

Le plan externe n’est pas nécessaire à la lecture : les points attendus sont extraits de l’APK instrumentée. Il est livré pour expliquer et reproduire leur sélection.

## Les trois modes personnalisés

Six points sont ajoutés à `UrlAudit`. Les indices désignent les instructions Jimple présentées par le navigateur de code de cette APK, avant instrumentation.

| Point | Méthode, instruction | Mode | Observation recherchée |
|---|---|---|---|
| Entrée | `inspect`, 4 | Statement reached | Entrée dans le traitement |
| Message | `inspect`, 5 | Custom message | `Vérification de l’URL normalisée` |
| URL normalisée | `inspect`, 6 | Variable value | Chaîne locale `$r1`, avant l’appel à `allow` |
| Décision | `inspect`, 7 | Variable value | Booléen local `$z0`, après l’appel à `allow` |
| Acceptation | `accepted`, 3 | Statement reached | Branche d’acceptation |
| Refus | `rejected`, 3 | Statement reached | Branche de refus |

Les noms de variables peuvent changer après recompilation ; `prepare.cjs` retrouve les instructions et vérifie la disponibilité du local au lieu de supposer ces noms.

## Entrées et résultats réellement observés

Captures réalisées le 30 octobre 2026 sur l’émulateur Android 16, API 36. Chaque scénario démarre un nouveau processus de l’application. Les journaux ne sont pas cumulés.

| Scénario | Paramètres du lien profond | Valeur capturée | Décision | Cible confirmée | Point rouge |
|---|---|---|---|---|---|
| `accepted` | `url= https://trusted.example/account `, espaces inclus | `https://trusted.example/account` | `true` | `HostPolicy.allow` | `rejected` |
| `rejected` | `url=https://untrusted.example/account` | `https://untrusted.example/account` | `false` | `HostPolicy.allow` | `accepted` |
| `deny-policy` | `url=https://trusted.example/account`, `policy=deny` | `https://trusted.example/account` | `false` | `DenyPolicy.allow` | `accepted` |

| Scénario | Instructions observées | Méthodes | Classes | Activités | Points personnalisés | Lignes brutes |
|---|---:|---:|---:|---:|---:|---:|
| `accepted` | 62/77, 80,5 % | 9/12, 75 % | 3/4, 75 % | 1/1 | 5/6 | 131 |
| `rejected` | 62/77, 80,5 % | 9/12, 75 % | 3/4, 75 % | 1/1 | 5/6 | 131 |
| `deny-policy` | 55/77, 71,4 % | 9/12, 75 % | 3/4, 75 % | 1/1 | 5/6 | 119 |

Chaque rapport compte cinq arêtes confirmées. Les appels de framework et les constructeurs peuvent rester des cibles déclarées, distinctes des cibles confirmées. Les compteurs du graphe ne sont pas des fréquences d’invocation. Un point rouge signifie **non observé dans ce journal**, pas « inaccessible ». Les instructions éligibles, les méthodes concrètes et les classes instrumentées constituent les dénominateurs ; l’interface abstraite n’est pas une classe observée supplémentaire. Aucun service, récepteur ou fournisseur de contenu n’est défini dans cette application.

## Reproduire depuis le dépôt

Prérequis : JDK 17 ou supérieur, Node.js compatible avec le dépôt, Python 3, Android SDK avec plateforme 36, Build Tools 36.1.0 et un appareil ou émulateur. La construction de l’APK n’utilise ni Gradle ni téléchargement de dépendances. Les commandes ci-dessous s’exécutent depuis la racine d’AndroLog, après avoir construit son JAR (`mvn package`). Elles ont été vérifiées sur macOS arm64 avec JDK 21.

```bash
export ANDROID_HOME="/chemin/vers/Android/sdk"
export JAVA_HOME="/chemin/vers/le/jdk"
python3 docs/submissions/sstic2027/demo/build.py
node docs/submissions/sstic2027/demo/prepare.cjs
python3 docs/submissions/sstic2027/demo/capture.py --device emulator-5554
node docs/submissions/sstic2027/demo/analyze.cjs
```

Remplacer `emulator-5554` par le numéro retourné par `adb devices`. Le script de capture installe et arrête uniquement le package `com.example.andrologsstic`. Il filtre les lignes originales par PID et tag sans vider les journaux globaux. Le niveau `SSTIC_DEMO:V` est nécessaire pour conserver les sondes automatiques de niveau DEBUG ainsi que les événements personnalisés et de graphe de niveau INFO.

`analyze.cjs` vérifie les valeurs, les branches verte/rouge, la correspondance APK/journal, les six points attendus et la confirmation de l’appel polymorphe. Il échoue si une observation ne correspond pas. Les résultats sont écrits dans `validation.json` ; aucun événement n’est simulé. Pour utiliser un autre JAR, définir `ANDROLOG_JAR`. Pour changer la destination des scripts Node, définir `DEMO_OUTPUT` et passer le même chemin via `--output` aux scripts Python. Pour une autre version de Build Tools, définir `BUILD_TOOLS` pour la préparation et utiliser `--build-tools` pour la construction.

Une reconstruction génère de nouveaux identifiants de sondes et de nouveaux fichiers signés : conserver ensemble l’APK et les journaux d’une même préparation. Le certificat de test est créé dans `build/` ; il ne doit servir qu’à cette démonstration et n’est pas distribué.

## Rejouer un lien manuellement

Après installation de `instrumented.apk`, démarrer la capture dans un terminal :

```bash
adb -s emulator-5554 logcat -v threadtime -s 'SSTIC_DEMO:V' '*:S' > runtime.log
```

Dans un autre terminal, arrêter le processus de démonstration puis lancer l’un des trois liens. Les guillemets transmis au shell Android préservent notamment le `&policy=deny`.

```bash
adb -s emulator-5554 shell am force-stop com.example.andrologsstic
adb -s emulator-5554 shell 'am start -W -a android.intent.action.VIEW -n com.example.andrologsstic/.MainActivity -d "andrologdemo://open?url=%20https%3A%2F%2Ftrusted.example%2Faccount%20"'

adb -s emulator-5554 shell am force-stop com.example.andrologsstic
adb -s emulator-5554 shell 'am start -W -a android.intent.action.VIEW -n com.example.andrologsstic/.MainActivity -d "andrologdemo://open?url=https%3A%2F%2Funtrusted.example%2Faccount"'

adb -s emulator-5554 shell am force-stop com.example.andrologsstic
adb -s emulator-5554 shell 'am start -W -a android.intent.action.VIEW -n com.example.andrologsstic/.MainActivity -d "andrologdemo://open?url=https%3A%2F%2Ftrusted.example%2Faccount&policy=deny"'
```

Arrêter la capture avec Ctrl+C. Pour obtenir des journaux séparés et écarter les anciens événements du tampon Logcat, préférer `capture.py`, qui effectue la sélection exacte du processus. Ne pas comparer les pourcentages du tableau à un journal manuel cumulant plusieurs exécutions.

## Contenu à conserver dans l’archive

- Sources de ce dossier : manifeste, cinq fichiers Java, `build.py`, `prepare.cjs`, `capture.py`, `analyze.cjs` et ce guide.
- `original.apk`, `instrumented.apk`, `custom-plan.json`, `points.json`, `build-evidence.json`, `validation.json`, `SHA256SUMS`.
- Pour chacun des dossiers `accepted`, `rejected`, `deny-policy` : `runtime.log`, `capture.json`, `coverage.json`, `coverage.html`.
- Les fichiers `audit-code.json` et `variables.json` documentent les instructions et les variables extraites ; ils peuvent accompagner les sources.

Ne pas distribuer `build/` (intermédiaires et clé de test), les caches, ni `prepare.log` qui contient des chemins propres à la machine de préparation. La licence du dépôt AndroLog s’applique aux sources de cette démonstration.
