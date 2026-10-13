"""Skapar testfilerna i src/test/resources/fixtures. Kräver python-docx och Pillow.

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
