import fs from 'node:fs/promises';
import path from 'node:path';
import { Presentation, PresentationFile } from '@oai/artifact-tool';

// Run from the AndroLog root, with this file copied into a directory linked to the bundled node_modules.
const root = process.cwd();
const out = path.join(root, 'target/sstic2027/presentation-build');
await fs.mkdir(out, { recursive: true });
const C = { ink: '#17343a', green: '#087b64', dark: '#0a293a', mid: '#526c69', faint: '#edf6f1', white: '#ffffff', red: '#b34242' };
const deck = Presentation.create({ slideSize: { width: 1280, height: 720 } });
const notesRaw = await fs.readFile(path.join(root, 'docs/submissions/sstic2027/presentation-notes.md'), 'utf8');
const noteSections = [...notesRaw.matchAll(/^## (\d+)\. [^\n]+\n([\s\S]*?)(?=^## \d+\.|$(?![\s\S]))/gm)];
if (noteSections.length !== 12) throw new Error(`Expected 12 speaker-note sections, got ${noteSections.length}`);
const noteMap = new Map(noteSections.map(m => [Number(m[1]),m[2].trim()]));

function text(s, content, x, y, w, h, size=29, color=C.ink, bold=false, extra={}) {
 const sh=s.shapes.add({geometry:'textbox',position:{left:x,top:y,width:w,height:h},fill:'none',line:{fill:'none',width:0}});
 sh.text=content; sh.text.style={typeface:'Arial',fontSize:size,color,bold,wrap:'square',autoFit:'none',insets:0,...extra}; return sh;
}
function base(title, n) {
 const s=deck.slides.add(); s.background.fill=C.white;
 text(s,title,66,48,1148,80,43,C.ink,true);
 text(s,'ANDROLOG  /  SSTIC 2027',66,678,550,22,15,C.mid,true);
 text(s,String(n).padStart(2,'0'),1180,678,34,22,15,C.mid,false,{alignment:'right'});
 s.speakerNotes.text=noteMap.get(n); return s;
}
function label(s, content, x,y,w,size=24) { return text(s,content,x,y,w,42,size,C.green,true); }
async function image(s, filename,x,y,w,h,alt) {
 const bytes=await fs.readFile(path.join(root,filename));
 s.images.add({blob:bytes.buffer.slice(bytes.byteOffset,bytes.byteOffset+bytes.byteLength),contentType:'image/png',alt,fit:'contain',position:{left:x,top:y,width:w,height:h}});
}
function caption(s, y=640) {text(s,'Capture de l’interface : autre app de démonstration',66,y,1100,22,17,C.mid);}

// 1 - couverture
{
 const s=deck.slides.add(); s.background.fill=C.white;
 await image(s,'data/androlog_logo_v2.png',70,72,350,117,'Logo AndroLog');
 text(s,"AndroLog: Instrumentation d'Apps\nAndroid en Boîte Noire",70,238,1120,165,53,C.dark,true);
 text(s,'Jordan Samhi',72,470,600,45,32,C.green,true);
 text(s,'Université du Luxembourg  ·  jordan.samhi@uni.lu',72,525,1000,38,25,C.mid);
 text(s,'SSTIC 2027',72,676,450,24,16,C.mid,true);
 s.speakerNotes.text=noteMap.get(1);
}
// 2 - questions
{
 const s=base('Que fait vraiment cette app ?',2);
 text(s,'Vous avez un APK à analyser.',70,172,1000,58,39,C.dark,true);
 text(s,'Le contrôle est-il atteint ?',72,285,1050,50,32,C.ink);
 text(s,'Quelle valeur arrive au contrôle ?',72,356,1050,50,32,C.ink);
 text(s,'Quelle méthode est réellement appelée ?',72,427,1050,50,32,C.ink);
 text(s,'Exemple : une URL reçue par l’app',72,552,1000,38,25,C.green,true);
}
// 3 - couverture
{
 const s=base('La couverture à quatre niveaux',3);
 text(s,'Instructions',70,175,450,46,35,C.dark,true);
 text(s,'Méthodes',70,252,450,46,35,C.dark,true);
 text(s,'Classes',70,329,450,46,35,C.dark,true);
 text(s,'Composants Android',70,406,480,46,35,C.dark,true);
 text(s,'Ce que le test a effectivement exécuté',70,532,480,72,25,C.green);
 await image(s,'data/screenshots/gui-coverage.png',550,155,670,360,'Tableau de couverture dans AndroLog'); caption(s);
}
// 4 - workflow
{
 const s=base('D’un APK à un rapport',4);
 const items=[['01','Choisir','APK et informations à enregistrer'],['02','Instrumenter','Ajouter les logs et signer l’APK'],['03','Tester','Exécuter l’app et collecter les logs'],['04','Analyser','Explorer la couverture et le code']];
 for(let i=0;i<items.length;i++) { const y=156+i*110; text(s,items[i][0],72,y,60,42,31,C.green,true); text(s,items[i][1],148,y,270,42,32,C.dark,true); text(s,items[i][2],450,y+4,710,48,27,C.ink); }
}
// 5 - historique et UI
{
 const s=base('AndroLog : ligne de commande et interface',5);
 text(s,'2024',70,169,360,58,43,C.green,true);
 text(s,'Développé dans un cadre académique\nPrésenté à FSE, au Brésil',70,245,440,98,27,C.ink);
 text(s,'Aujourd’hui',70,388,380,48,34,C.dark,true);
 text(s,'Nouvelles fonctionnalités\net interface graphique',70,450,430,90,27,C.ink);
 await image(s,'data/screenshots/gui-instrument.png',525,145,695,490,'Écran d’instrumentation AndroLog'); caption(s);
 s.speakerNotes.append('\n\nSource : https://doi.org/10.1145/3663529.3663806');
}
// 6 - cas d'audit
{
 const s=base('Une URL à vérifier',6);
 label(s,'URL reçue avec des espaces autour',70,155,1100,27);
 text(s,'  https://trusted.example/account  ',70,228,1100,54,35,C.dark,true);
 text(s,'Quelle valeur arrive au contrôle ?',70,347,1080,49,31,C.ink);
 text(s,'Quelle politique est appelée ?',70,418,1080,49,31,C.ink);
 text(s,'Quelle branche est exécutée ?',70,489,1080,49,31,C.ink);
 text(s,'L’app affiche une décision. Elle ne visite pas l’URL.',70,592,1100,40,23,C.mid);
}
// 7 - trois modes
{
 const s=base('Choisir où ajouter un log',7);
 label(s,'Statement reached',70,155,460,27); text(s,'L’instruction est-elle atteinte ?',70,196,455,43,25,C.ink);
 label(s,'Custom message',70,283,460,27); text(s,'Afficher le message choisi',70,324,455,43,25,C.ink);
 label(s,'Variable value',70,411,460,27); text(s,'Enregistrer une valeur locale',70,452,455,43,25,C.ink);
 text(s,'Le log est placé avant l’instruction.',70,560,460,48,23,C.mid);
 await image(s,'data/screenshots/gui-custom-logs.png',550,145,670,475,'Sélection de logs personnalisés'); caption(s);
}
// 8 - exécution
{
 const s=base('Tester sur un appareil',8);
 text(s,'Test on device',70,180,460,53,36,C.green,true);
 text(s,'Choisir un appareil\nou un émulateur',70,250,450,82,28,C.ink);
 text(s,'Exercer le scénario',70,377,450,50,29,C.ink);
 text(s,'Stop & analyze',70,463,460,52,34,C.dark,true);
 await image(s,'data/screenshots/gui-device-test.png',550,140,670,490,'Capture sur un appareil'); caption(s);
}
// 9 - evidence
{
 const s=base('Ce que montre la capture',9);
 text(s,'62 / 77',70,167,330,65,50,C.green,true);
 text(s,'instructions',70,239,330,40,27,C.ink);
 text(s,'9 / 12',470,167,330,65,50,C.green,true);
 text(s,'méthodes',470,239,330,40,27,C.ink);
 text(s,'5 / 6',865,167,330,65,50,C.green,true);
 text(s,'points observés',865,239,330,40,27,C.ink);
 label(s,'Valeur enregistrée',70,332,1100,25);
 text(s,'https://trusted.example/account',70,375,1100,52,34,C.dark,true);
 text(s,'Décision : true',70,465,430,43,29,C.green,true);
 text(s,'Branche de refus : non observée',515,465,650,43,27,C.red,true);
 text(s,'Capture de référence : une exécution de l’app de démonstration',70,585,1050,34,22,C.mid);
 s.speakerNotes.append('\n\nSource : demo/accepted/coverage.json et demo/validation.json, capture du 30 octobre 2026.');
}
// 10 - dynamic dispatch
{
 const s=base('Quelle implémentation a été appelée ?',10);
 label(s,'Cible déclarée',70,170,1100,27);
 text(s,'UrlPolicy.allow',70,211,1000,55,39,C.dark,true);
 text(s,'Capture accepted',70,337,470,42,25,C.mid);
 text(s,'HostPolicy.allow',70,383,500,52,35,C.green,true);
 text(s,'URL de confiance  /  true',70,437,500,38,24,C.ink);
 text(s,'Capture deny-policy',680,337,470,42,25,C.mid);
 text(s,'DenyPolicy.allow',680,383,500,52,35,C.green,true);
 text(s,'Même URL  /  false',680,437,500,38,24,C.ink);
 text(s,'Une cible déclarée n’est pas toujours confirmée par la trace.',70,581,1100,46,24,C.mid);
 s.speakerNotes.append('\n\nSource : demo/validation.json, captures accepted et deny-policy.');
}
// 11 - sharing
{
 const s=base('Partager et reprendre l’analyse',11);
 label(s,'Rapport HTML',70,162,465,28); text(s,'Un fichier à ouvrir\ndans un navigateur',70,207,455,88,28,C.ink);
 label(s,'APK instrumenté et logs',70,352,465,28); text(s,'Reprendre l’analyse\nsur une autre machine',70,398,455,88,28,C.ink);
 await image(s,'data/screenshots/gui-custom-results.png',550,170,670,390,'Résultats de logs personnalisés'); caption(s);
}
// 12 - conclusion
{
 const s=base('Ce qu’il faut retenir',12);
 text(s,'Couverture de code',70,164,1090,53,36,C.dark,true);
 text(s,'Plusieurs niveaux de granularité, à partir d’un APK',70,216,1090,43,26,C.ink);
 text(s,'Logs personnalisés',70,312,1090,53,36,C.dark,true);
 text(s,'Observer un point, un message ou une valeur',70,364,1090,43,26,C.ink);
 text(s,'Analyse liée au code',70,460,1090,53,36,C.dark,true);
 text(s,'Rapport HTML et graphe d’appels dynamique',70,512,1090,43,26,C.ink);
 text(s,'github.com/JordanSamhi/AndroLog',70,615,1090,36,24,C.green,true);
}

if (deck.slides.items.length !== 12) throw new Error('Unexpected slide count');
for (let i=0;i<deck.slides.items.length;i++) {
 const blob=await deck.export({slide:deck.slides.items[i],format:'png',scale:1});
 await fs.writeFile(path.join(out,`slide-${String(i+1).padStart(2,'0')}.png`),new Uint8Array(await blob.arrayBuffer()));
}
await (await PresentationFile.exportPptx(deck)).save(path.join(out,'candidate.pptx'));
console.log('Created 12 editable slides and previews');
