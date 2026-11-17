# AndroLog : prérequis pour son utilisation

**Jordan Samhi · Université du Luxembourg · jordan.samhi@uni.lu**

AndroLog ne nécessite aucun logiciel payant ni matériel spécialisé.

## Instrumenter et analyser une app

Pour instrumenter ou analyser un APK, AndroLog nécessite le **SDK Android**, avec une plateforme Android. L’instrumentation nécessite également les *Build Tools*, notamment `zipalign` et `apksigner`. Ces outils sont disponibles gratuitement ; leur utilisation est soumise aux conditions de licence du SDK Android.

AndroLog nécessite aussi **Java 17 ou une version plus récente**.

## Exécuter l’app et collecter les logs

Pour exécuter une app instrumentée et collecter de nouveaux logs, il faut un **émulateur Android ou un téléphone Android**, ainsi que l’outil `adb` fourni dans les *Platform Tools* du SDK. Sur un téléphone physique, le débogage USB doit être activé et l’ordinateur autorisé. Les images d’émulateur sont soumises à leurs propres conditions de licence.

## Plateformes et consultation d’un rapport

AndroLog est multiplateforme. Les sources permettent de construire l’outil pour macOS, Windows et Linux ; la compilation nécessite notamment un JDK, Maven, Node.js et npm.

Un rapport HTML déjà généré s’ouvre dans un navigateur web, sans SDK Android, appareil Android ou environnement Java.
