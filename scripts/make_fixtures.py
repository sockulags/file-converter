"""Skapar testfilerna i src/test/resources/fixtures. Kräver python-docx, Pillow och fonttools.

    python3 scripts/make_fixtures.py
"""
from pathlib import Path

from docx import Document
from docx.oxml.ns import qn
from docx.shared import Cm, Pt
from PIL import Image, ImageDraw

OUT = Path(__file__).resolve().parent.parent / "src/test/resources/fixtures"
OUT.mkdir(parents=True, exist_ok=True)

# PNG med alfakanal: förbjuden som den är i PDF/A-1 och måste plattas ut.
img = Image.new("RGBA", (300, 150), (0, 0, 0, 0))
ImageDraw.Draw(img).ellipse((10, 10, 290, 140), fill=(30, 90, 200, 160))
img.save(OUT / "alpha.png")


def beslut(name, font=None, alt=True):
    doc = Document()
    if font:
        doc.styles["Normal"].font.name = font
        doc.styles["Normal"].font.size = Pt(11)
    doc.core_properties.title = "Beslut om bygglov"
    doc.add_heading("Beslut om bygglov", 0)
    doc.add_heading("Bakgrund", 1)
    doc.add_paragraph("Sökanden har ansökt om bygglov för tillbyggnad. Åäö ÅÄÖ é ü – ”citat” €.")
    doc.add_paragraph("Punkt ett", style="List Bullet")
    doc.add_paragraph("Punkt två", style="List Bullet")
    table = doc.add_table(rows=3, cols=2)
    table.style = "Table Grid"
    for i, row in enumerate(table.rows):
        row.cells[0].text = f"Rad {i}"
        row.cells[1].text = f"Värde {i}"
    doc.add_heading("Bild", 1)
    doc.add_picture(str(OUT / "alpha.png"), width=Cm(6))
    if alt:
        for pr in doc.element.body.iter(qn("wp:docPr")):
            pr.set("descr", "Blå ellips")
    doc.save(OUT / name)


beslut("beslut.docx")
beslut("beslut-calibri.docx", font="Calibri")
beslut("bilaga.docx", alt=False)


# Logga med alfakanal i en färgad tabellcell: hörnen ska få cellens färg, inte vitt.
def logga_pa_farg(name):
    from docx.oxml import OxmlElement

    doc = Document()
    doc.core_properties.title = "Logga på färgad bakgrund"
    doc.add_heading("Logga på färgad bakgrund", 1)
    cell = doc.add_table(rows=1, cols=1).rows[0].cells[0]
    shading = OxmlElement("w:shd")
    shading.set(qn("w:val"), "clear")
    shading.set(qn("w:color"), "auto")
    shading.set(qn("w:fill"), "C8E6B4")
    cell._tc.get_or_add_tcPr().append(shading)
    cell.paragraphs[0].add_run().add_picture(str(OUT / "alpha.png"), width=Cm(8))
    for pr in doc.element.body.iter(qn("wp:docPr")):
        pr.set("descr", "Logga")
    doc.save(OUT / name)


logga_pa_farg("logga-pa-farg.docx")


# Testfont som inte finns i systemet: delmängd av DejaVu Sans under nytt namn
# (DejaVu-licensen kräver nytt namn vid ändring). Varianten "Spärrad" förbjuder inbäddning.
def testfonter():
    from fontTools import subset
    from fontTools.ttLib import TTFont

    source = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
    text = "".join(chr(c) for c in range(0x20, 0x7F)) + "ÅÄÖåäöéü–”€"
    for family, file, fs_type in [("Filkonverter Test", "FilkonverterTest-Regular.ttf", 0),
                                  ("Filkonverter Sparrad", "FilkonverterSparrad-Regular.ttf", 2)]:
        font = TTFont(source)
        options = subset.Options()
        options.name_IDs = ["*"]
        subsetter = subset.Subsetter(options)
        subsetter.populate(text=text)
        subsetter.subset(font)
        postscript = family.replace(" ", "") + "-Regular"
        for record in font["name"].names:
            if record.nameID in (1, 4, 16):
                record.string = family if record.nameID != 4 else family + " Regular"
            elif record.nameID == 6:
                record.string = postscript
            elif record.nameID == 3:
                record.string = postscript + ";test"
        font["OS/2"].fsType = fs_type
        font.save(OUT / file)


def dokument_med_testfont(name):
    doc = Document()
    doc.core_properties.title = "Dokument med egen font"
    doc.styles["Normal"].font.name = "Filkonverter Test"
    doc.add_heading("Dokument med egen font", 1)
    doc.add_paragraph("Den här texten ska sättas med Filkonverter Test. Åäö ÅÄÖ.")
    doc.save(OUT / name)


testfonter()
dokument_med_testfont("egen-font.docx")


# Stående sida, liggande bilaga, stående igen: sidorienteringen ska följa med per avsnitt.
def staende_liggande(name):
    from docx.enum.section import WD_ORIENT, WD_SECTION

    doc = Document()
    doc.core_properties.title = "Stående och liggande"
    doc.add_heading("Beslut (stående)", 1)
    doc.add_paragraph("Första sidan är stående.")
    landscape = doc.add_section(WD_SECTION.NEW_PAGE)
    landscape.orientation = WD_ORIENT.LANDSCAPE
    landscape.page_width, landscape.page_height = landscape.page_height, landscape.page_width
    doc.add_heading("Bilaga (liggande)", 1)
    table = doc.add_table(rows=2, cols=8)
    table.style = "Table Grid"
    for i, cell in enumerate(table.rows[0].cells):
        cell.text = f"Kolumn {i + 1}"
    portrait = doc.add_section(WD_SECTION.NEW_PAGE)
    portrait.orientation = WD_ORIENT.PORTRAIT
    portrait.page_width, portrait.page_height = portrait.page_height, portrait.page_width
    doc.add_heading("Underskrift (stående)", 1)
    doc.save(OUT / name)


staende_liggande("staende-liggande.docx")


# Presentationer: en vanlig, och en med halvgenomskinlig form (vanligt i bildspel).
def presentationer():
    from pptx import Presentation
    from pptx.util import Cm as PCm, Pt as PPt
    from pptx.dml.color import RGBColor

    prs = Presentation()
    title = prs.slides.add_slide(prs.slide_layouts[0])
    title.shapes.title.text = "Information om bygglov"
    title.placeholders[1].text = "Byggnadsnämnden 2026"
    bullets = prs.slides.add_slide(prs.slide_layouts[1])
    bullets.shapes.title.text = "Så går det till"
    body = bullets.placeholders[1].text_frame
    body.text = "Ansökan lämnas digitalt"
    for line in ["Handläggning inom tio veckor", "Beslut skickas som PDF/A – åäö"]:
        body.add_paragraph().text = line
    bullets.shapes.add_picture(str(OUT / "alpha.png"), PCm(17), PCm(12), width=PCm(6))
    prs.core_properties.title = "Information om bygglov"
    prs.save(OUT / "presentation.pptx")

    prs = Presentation()
    slide = prs.slides.add_slide(prs.slide_layouts[5])
    slide.shapes.title.text = "Bild med halvgenomskinlig form"
    shape = slide.shapes.add_shape(1, PCm(3), PCm(6), PCm(12), PCm(6))
    shape.fill.solid()
    shape.fill.fore_color.rgb = RGBColor(0x1E, 0x5A, 0xC8)
    srgb = shape.fill._xPr.find(qn("a:solidFill")).find(qn("a:srgbClr"))
    alpha = srgb.makeelement(qn("a:alpha"), {"val": "50000"})
    srgb.append(alpha)
    shape.text_frame.text = "50 % genomskinlig"
    prs.core_properties.title = "Transparens"
    prs.save(OUT / "presentation-transparens.pptx")


def kalkyl():
    from openpyxl import Workbook

    wb = Workbook()
    ws = wb.active
    ws.title = "Avgifter"
    ws.append(["Åtgärd", "Avgift (kr)"])
    for row in [("Bygglov, enbostadshus", 24000), ("Marklov", 8000), ("Rivningslov", 4500)]:
        ws.append(row)
    wb.properties.title = "Avgifter"
    wb.save(OUT / "avgifter.xlsx")


# .doc och .odt: samma beslut som beslut.docx, sparat av LibreOffice.
def word_format():
    import shutil
    import subprocess
    import tempfile

    if not shutil.which("soffice"):
        print("soffice saknas: hoppar över .doc/.odt")
        return
    with tempfile.TemporaryDirectory() as tmp:
        for fmt in ["odt", "doc"]:
            subprocess.run(["soffice", "--headless", "--convert-to", fmt, "--outdir", tmp, str(OUT / "beslut.docx")],
                           check=True, capture_output=True)
            shutil.copy(Path(tmp) / f"beslut.{fmt}", OUT / f"beslut.{fmt}")


presentationer()
kalkyl()
word_format()
