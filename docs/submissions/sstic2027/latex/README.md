# Source LaTeX : description d’AndroLog pour SSTIC 2027

Le contenu figure dans `JordanSamhi/master.tex`, la bibliographie dans `JordanSamhi/biblio.bib`.
Le document utilise les classes officielles inchangées `sstic.cls` et `llncs.cls` du [template SSTIC](https://gitlab.com/sstic/author-template), commit `011dda9f21001167b2380096fa12d71815dcb012`.

Pour compiler dans l’environnement officiel, copier le dossier `JordanSamhi` dans une copie du template et suivre son `README`. Depuis la racine de cette copie, avec Docker installé et démarré :

```sh
make from_docker SSTIC_TARGET=_build/JordanSamhi.pdf
```

Le PDF est créé dans `_build/JordanSamhi.pdf`. Les classes et le processus de compilation du template ne sont pas modifiés.

Pour compiler localement avec TeX Live 2025, placer `sstic.cls` et `llncs.cls` à côté de `01-description-outil.tex`, puis lancer :

```sh
mkdir -p _build
pdflatex -shell-escape -interaction=nonstopmode -halt-on-error -output-directory=_build 01-description-outil.tex
bibtex _build/01-description-outil
pdflatex -shell-escape -interaction=nonstopmode -halt-on-error -output-directory=_build 01-description-outil.tex
pdflatex -shell-escape -interaction=nonstopmode -halt-on-error -output-directory=_build 01-description-outil.tex
```

Le fichier autonome précharge `minted2`, fourni avec TeX Live 2025, pour la compatibilité avec la classe SSTIC et son option `outputdir`. Le mode `frozencache` convient ici car le document ne contient pas de listing `minted`. La taille du titre est ajustée pour tenir sur une ligne ; le corps conserve la taille prévue par le modèle.

Le script `build_pdfs.py` du dossier parent construit aussi la description à partir de cette source. Il cherche les classes dans `target/sstic2027/source-latex-sstic` par défaut ; la variable `SSTIC_TEMPLATE_DIR` peut indiquer une autre copie du template officiel.
