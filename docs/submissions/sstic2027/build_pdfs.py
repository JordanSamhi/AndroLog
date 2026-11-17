#!/usr/bin/env python3
"""Build the SSTIC description with LaTeX and the other documents with ReportLab."""
from pathlib import Path
import os, re, sys, html, shutil, subprocess, tempfile
from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_JUSTIFY
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import Image, SimpleDocTemplate, Paragraph, Spacer, PageBreak, Table, TableStyle, KeepTogether

HERE = Path(__file__).resolve().parent
OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else HERE.parents[2] / 'target/sstic2027/submission'
OUT.mkdir(parents=True, exist_ok=True)
pdfmetrics.registerFont(TTFont('Arial', '/System/Library/Fonts/Supplemental/Arial.ttf'))
pdfmetrics.registerFont(TTFont('ArialBold', '/System/Library/Fonts/Supplemental/Arial Bold.ttf'))
pdfmetrics.registerFontFamily('Arial', normal='Arial', bold='ArialBold', italic='Arial', boldItalic='ArialBold')
GREEN = colors.HexColor('#087b64'); INK = colors.HexColor('#1b3030'); MUTED = colors.HexColor('#637a74')

def render_sstic_description():
    """Keep the original Markdown for reference; the LaTeX source is authoritative."""
    classes = Path(os.environ.get('SSTIC_TEMPLATE_DIR', HERE.parents[2] / 'target/sstic2027/source-latex-sstic'))
    for name in ('sstic.cls', 'llncs.cls'):
        if not (classes / name).is_file():
            raise SystemExit(f'Missing {name} in {classes}. Set SSTIC_TEMPLATE_DIR to the official SSTIC template.')
    with tempfile.TemporaryDirectory(prefix='androlog-sstic-') as temp:
        work = Path(temp)
        shutil.copytree(HERE / 'latex', work, dirs_exist_ok=True)
        for name in ('sstic.cls', 'llncs.cls'):
            shutil.copy2(classes / name, work / name)
        (work / '_build').mkdir()
        command = ['pdflatex', '-shell-escape', '-interaction=nonstopmode', '-halt-on-error',
                   '-output-directory=_build', '01-description-outil.tex']
        for _ in range(2):
            subprocess.run(command, cwd=work, check=True, stdout=subprocess.DEVNULL)
        shutil.copy2(work / '_build/01-description-outil.pdf', OUT / '01-description-outil.pdf')

def inline(s):
    s = html.escape(s)
    s = re.sub(r'\[([^\]]+)\]\((https?://[^)]+)\)', r'<link href="\2" color="#087b64">\1</link>', s)
    s = re.sub(r'\*\*(.+?)\*\*', r'<b>\1</b>', s)
    return re.sub(r'`([^`]+)`', r'<font color="#087b64">\1</font>', s)

def render(source):
    compact = source.name.startswith('01-')
    if source.name == '01-description-outil.md':
        render_sstic_description()
        return
    font = 10.1 if compact else 10.4
    leading = 13.7 if compact else 14.4
    styles = {
      'title': ParagraphStyle('title', fontName='ArialBold', fontSize=17 if compact else 19, leading=20 if compact else 23, textColor=INK, spaceAfter=7, alignment=TA_CENTER),
      'heading': ParagraphStyle('heading', fontName='ArialBold', fontSize=10.3, leading=12.5, textColor=GREEN, spaceBefore=7, spaceAfter=3, alignment=TA_CENTER),
      'body': ParagraphStyle('body', fontName='Arial', fontSize=font, leading=leading, textColor=INK, spaceAfter=5, alignment=TA_JUSTIFY),
      'byline': ParagraphStyle('byline', fontName='Arial', fontSize=font, leading=leading, textColor=INK, spaceAfter=5, alignment=TA_CENTER),
      'bullet': ParagraphStyle('bullet', fontName='Arial', fontSize=font, leading=leading, textColor=INK, leftIndent=10, firstLineIndent=-8, spaceAfter=3, alignment=TA_JUSTIFY),
      'table': ParagraphStyle('table', fontName='Arial', fontSize=9, leading=12, textColor=INK),
      'tablehead': ParagraphStyle('tablehead', fontName='ArialBold', fontSize=9, leading=12, textColor=GREEN),
    }
    lines = source.read_text(encoding='utf8').splitlines(); story=[]; i=0
    if compact:
        logo = Image(str(HERE.parents[2] / 'data/androlog_logo_v2.png'), width=52*mm, height=52*mm/3)
        logo.hAlign = 'CENTER'
        story.extend([logo, Spacer(1, 3*mm)])
    while i < len(lines):
        line=lines[i].strip()
        if not line: i+=1; continue
        if line == '<!-- pagebreak -->': story.append(PageBreak()); i+=1; continue
        if line.startswith('# '): story.append(Paragraph(inline(line[2:]), styles['title'])); i+=1; continue
        if line.startswith('## '): story.append(Paragraph(inline(line[3:]), styles['heading'])); i+=1; continue
        if line.startswith('|'):
            rows=[]
            while i<len(lines) and lines[i].strip().startswith('|'):
                cells=[x.strip() for x in lines[i].strip().strip('|').split('|')]
                if not all(re.fullmatch(r'[-: ]+', x) for x in cells): rows.append(cells)
                i+=1
            width=171*mm
            widths=([24*mm, 39*mm, width-63*mm] if source.name.startswith('02-') else [28*mm, 62*mm, width-90*mm]) if len(rows[0])==3 else [45*mm, width-45*mm]
            data=[[Paragraph(inline(c), styles['tablehead'] if n==0 else styles['table']) for c in row] for n,row in enumerate(rows)]
            t=Table(data,colWidths=widths,repeatRows=1,hAlign='LEFT'); t.setStyle(TableStyle([('VALIGN',(0,0),(-1,-1),'TOP'),('BACKGROUND',(0,0),(-1,0),colors.HexColor('#e9f5f0')),('ROWBACKGROUNDS',(0,1),(-1,-1),[colors.white,colors.HexColor('#f6f9f7')]),('BOTTOMPADDING',(0,0),(-1,-1),5),('TOPPADDING',(0,0),(-1,-1),5),('LEFTPADDING',(0,0),(-1,-1),5),('RIGHTPADDING',(0,0),(-1,-1),5)])); story.append(t); story.append(Spacer(1,5)); continue
        if re.match(r'^(- |\d+\. )',line):
            line=re.sub(r'^- ', '• ', line); story.append(Paragraph(inline(line), styles['bullet'])); i+=1; continue
        para=[line]; i+=1
        while i<len(lines) and lines[i].strip() and not re.match(r'^(#|\||<!--|\d+\. |- )',lines[i].strip()): para.append(lines[i].strip()); i+=1
        text = ' '.join(para)
        style = styles['byline'] if re.fullmatch(r'\*\*[^*]+\*\*', text) else styles['body']
        story.append(Paragraph(inline(text), style))
    def header(canvas,doc):
        canvas.saveState(); w,h=doc.pagesize
        canvas.setFillColor(GREEN); canvas.rect(0,h-8*mm,w,8*mm,fill=1,stroke=0)
        canvas.setFont('ArialBold',7.5); canvas.setFillColor(colors.white); canvas.drawString(19*mm,h-5.4*mm,'ANDROLOG  /  SSTIC 2027')
        canvas.setFont('Arial',7); canvas.setFillColor(MUTED); canvas.drawString(19*mm,12*mm,'PRÉSENTATION D’UN OUTIL  ·  JORDAN SAMHI'); canvas.drawRightString(w-19*mm,12*mm,str(doc.page)); canvas.restoreState()
    doc=SimpleDocTemplate(str(OUT/(source.stem+'.pdf')),pagesize=(210*mm,297*mm),leftMargin=19*mm,rightMargin=20*mm,topMargin=15*mm,bottomMargin=20*mm,title=source.stem,author='Jordan Samhi')
    doc.build(story,onFirstPage=header,onLaterPages=header)

for name in (sys.argv[2:] or ('01-description-outil.md','03-guide-evaluation.md')):
    render(HERE/name)
