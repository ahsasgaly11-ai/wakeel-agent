"""Opens the Office files the app's generators wrote, with independent libraries, and checks what is in them."""
import sys, glob, os, zipfile
from xml.dom import minidom
import docx, openpyxl
from pptx import Presentation

folder = sys.argv[1]
bad = 0
def check(ok, what, detail=''):
    global bad
    print(('PASS  ' if ok else 'FAIL  ') + what + ('' if ok or not detail else '  — ' + str(detail)))
    if not ok: bad += 1

files = sorted(glob.glob(os.path.join(folder, '*')))
office = [f for f in files if f.endswith(('.docx', '.pptx', '.xlsx'))]
check(len(office) >= 3, 'office files present', [os.path.basename(f) for f in files])
for f in office:
    name = os.path.basename(f)
    try:
        with zipfile.ZipFile(f) as z:
            broken = z.testzip()
            for part in z.namelist():
                if part.endswith('.xml') or part.endswith('.rels'): minidom.parseString(z.read(part))
        check(broken is None, f'{name}: archive and XML are well formed', broken)
    except Exception as e:
        check(False, f'{name}: archive and XML are well formed', e)
        continue
    try:
        if name.endswith('.docx'):
            d = docx.Document(f)
            check(True, f'{name}: opens as a Word document ({len(d.paragraphs)} paragraphs, {len(d.tables)} tables)')
            if name == 'sample.docx':
                styles = [p.style.name for p in d.paragraphs]
                check(styles[:3] == ['Title', 'Subtitle', 'Heading 1'] and len(d.tables) == 1 and d.tables[0].cell(1, 0).text == 'التبخر', 'sample.docx: title, headings and table are where they should be', styles[:4])
                check(any('&' in p.text and '<دائماً>' in p.text for p in d.paragraphs), 'sample.docx: special characters survive')
        elif name.endswith('.pptx'):
            pr = Presentation(f)
            check(len(pr.slides) >= 1, f'{name}: opens as a presentation ({len(pr.slides)} slides)')
            if name.startswith('sample-'):
                texts = [' '.join(sh.text_frame.text for sh in s.shapes if sh.has_text_frame) for s in pr.slides]
                tables = sum(1 for s in pr.slides for sh in s.shapes if sh.has_table)
                check(len(pr.slides) == 7 and 'دورة الماء في الطبيعة' in texts[0] and tables == 1 and 'Evaporation' in texts[6], f'{name}: seven slides with their text and one table', (len(pr.slides), tables))
        else:
            wb = openpyxl.load_workbook(f)
            check(len(wb.sheetnames) >= 1, f'{name}: opens as a workbook {wb.sheetnames}')
            if name == 'sample.xlsx':
                ws = wb.worksheets[0]
                check(ws['D2'].value == '=AVERAGE(B2:C2)' and ws['B2'].value == 18 and abs(ws['E2'].value - 0.88) < 1e-9 and ws.sheet_view.rightToLeft and wb.worksheets[1]['B3'].value == '007',
                      'sample.xlsx: formulas, numbers, percentages, text codes and right-to-left', (ws['D2'].value, ws['B2'].value, ws['E2'].value, wb.worksheets[1]['B3'].value))
    except Exception as e:
        check(False, f'{name}: opens with an independent library', repr(e))
print('FILES: OK' if bad == 0 else f'FILES: FAILED ({bad})')
sys.exit(1 if bad else 0)
