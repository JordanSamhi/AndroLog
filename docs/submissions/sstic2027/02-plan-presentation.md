# Plan de présentation

**AndroLog : observer l’exécution d’un APK sans son code source**

Présentation d’un outil · Jordan Samhi · 15 minutes, dont 2 minutes de questions

## Objectif pour le public

À la fin de la présentation, une personne qui audite un APK doit pouvoir choisir un point d’observation, collecter une trace et relier le résultat au code. Le fil conducteur est une question précise : « Que devient une URL reçue par l’application, et quel contrôle est réellement exécuté ? »

## Déroulé chronométré

| Temps | Contenu | Ce que le public voit |
| --- | --- | --- |
| 0:00–1:30 | Poser l’hypothèse d’audit | Une URL reçue, une normalisation, deux politiques de validation. Trois inconnues : passage, valeur, cible d’appel. |
| 1:30–3:00 | Expliquer le mécanisme | APK → sondes → APK de test → logs → rapport. Soot/Jimple, réécriture et signature ; aucune source de l’application n’est nécessaire à AndroLog. |
| 3:00–5:00 | Choisir les observations | Arbre des paquets et méthodes ; sélection d’une instruction ; les trois modes personnalisés ; activation du graphe dynamique. |
| 5:00–7:00 | Exécuter le scénario | APK déjà instrumenté pour tenir le temps ; Test on device ; un parcours d’acceptation ; Stop & analyze. |
| 7:00–10:30 | Lire les résultats | Compteurs de couverture ; points verts/rouges ; URL capturée ; saut au code ; cible confirmée de l’appel à la politique. |
| 10:30–12:00 | Expliquer les limites | Non observé ≠ inaccessible ; avant instruction ≠ instruction terminée ; cible déclarée ≠ cible confirmée ; effets de la réécriture et des objets. |
| 12:00–13:00 | Reproduire et partager | APK instrumenté + logs sur une autre machine ; HTML autonome ; lien vers sources et artefact. |
| 13:00–15:00 | Questions | Retour sur une observation ou sur le mécanisme de collecte. |

## Supports prévus

Six planches très courtes : la question d’audit ; le cheminement d’un APK ; les trois points d’observation ; le résultat du scénario ; les limites de l’observation ; les ressources pour reproduire. La démonstration occupe le centre de la présentation. Ce document constitue le plan soumis au comité ; les planches finales pourront être adaptées après acceptation.

<!-- pagebreak -->

# Scénario de démonstration

## Une application pédagogique, un résultat vérifiable

L’application `com.example.andrologsstic` traite une URL reçue dans un lien profond. Elle la normalise, sélectionne une implémentation de politique de validation, puis affiche une décision. Elle ne télécharge pas l’URL et ne réalise aucune opération sur des fichiers. Les valeurs et les décisions servent uniquement à rendre les observations lisibles.

1. **Ouvrir l’APK original.** Parcourir la classe qui traite le lien, puis sa méthode. Expliquer brièvement que les noms de variables viennent du bytecode et peuvent différer des noms du projet d’origine.
2. **Placer les trois types de points.** Marquer les branches avec Statement reached ; placer un message avant le choix de la politique ; sélectionner la variable qui contient l’URL normalisée avec Variable value. Pour lire le résultat d’une affectation, choisir l’instruction suivante.
3. **Collecter une exécution.** Activer les sondes de couverture et de graphe, instrumenter, choisir l’émulateur et démarrer Test on device. Déclencher le parcours d’acceptation indiqué dans le protocole joint. Arrêter avec Stop & analyze.
4. **Répondre à l’hypothèse.** Vérifier le statut des points et la valeur normalisée. Cliquer un résultat personnalisé pour revenir à son instruction. Le point de la branche refusée, non exercée dans cette capture, est rouge. Ouvrir ensuite le rapport fourni pour une entrée refusée : le statut de ces branches est inversé.
5. **Suivre l’appel polymorphe.** Ouvrir la classe concernée dans le graphe, sélectionner le nœud de la politique et lire ses relations. Expliquer qu’une cible confirmée repose sur une correspondance de trace, tandis qu’une cible déclarée conserve une incertitude sur l’exécution.
6. **Partager la preuve.** Ouvrir le rapport HTML exporté et montrer les deux entrées nécessaires pour reprendre l’analyse : l’APK effectivement exécuté et son fichier de logs.

## Préparation et solution de secours

Préparer l’émulateur, le SDK, les fichiers et les fenêtres avant la session. Conserver un APK instrumenté pour éviter de consacrer les quinze minutes à la compilation. Ne pas mettre à jour les dépendances juste avant la présentation.

En cas d’échec de connexion ou d’installation, ouvrir les traces réellement enregistrées et le rapport fournis dans l’artefact. Annoncer explicitement ce passage à une capture antérieure. Le protocole joint documente les entrées et les résultats attendus ; les fichiers de validation distinguent les résultats obtenus de ces attentes.

## Ce que la démonstration ne revendique pas

Il ne s’agit ni d’une détection automatique de vulnérabilités, ni d’un parcours exhaustif de l’application, ni d’une mesure de performance. Les pourcentages décrivent cette exécution et ce périmètre instrumenté. L’intérêt pour l’audit est de transformer une hypothèse sur le code en observations consultables et reproductibles.
