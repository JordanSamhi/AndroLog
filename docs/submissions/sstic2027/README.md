# Dossier SSTIC 2027 : AndroLog

Ce répertoire contient les sources du dossier de présentation d’un outil (15 minutes) :

- `01-description-outil.md` : texte destiné au comité, une page en PDF.
- `build_presentation.mjs` et `presentation-notes.md` : présentation complète, avec notes et scénario de démonstration.
- `03-guide-evaluation.md` : évaluation, prérequis, limites et déclarations.
- `formulaire.txt` : éléments à copier dans le portail ; la note privée finale n’est pas destinée au comité.
- `password-demo/` : app pédagogique avec mot de passe fictif et protocole de reproduction.
- `demo/` : ancien exemple de validation d’URL, conservé dans les sources.
- `artifact/` : instructions et provenance du paquet de bureau.

Les PDF générés, la présentation PowerPoint, l’application emballée et les captures se trouvent sous `target/sstic2027/` ; ils ne sont pas suivis par Git. Pour régénérer la description et le guide sur macOS, utiliser un Python avec ReportLab :

```sh
python3 docs/submissions/sstic2027/build_pdfs.py target/sstic2027/submission
```

La présentation est construite avec `@oai/artifact-tool`. Exécuter `build_presentation.mjs` depuis la racine du dépôt avec ce module disponible ; le script lit les notes et les captures du dépôt et écrit un PowerPoint et ses aperçus dans `target/sstic2027/presentation-build/`.

Les fichiers sont proposés pour relecture, pas déposés automatiquement. La vidéo est reportée. Avant l’envoi, confirmer les informations d’auteur, vérifier les conditions de la publication FSE 2024 et vérifier les possibilités de dépôt du portail. Le code fonctionnel correspondant à l’artefact est disponible publiquement au commit `cfc8f9924c9368d59059262dee599ad0ccc963df` ; le paquet et les captures préparés localement doivent être joints ou placés à une URL stable si nécessaire. Voir [l’appel SSTIC](https://www.sstic.org/2027/cfp/).
