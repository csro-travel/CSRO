from pathlib import Path
from typing import BinaryIO

from docx import Document
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Inches, Pt, RGBColor

from .agent import CaseDocument


def _text(value) -> str:
    return "미확인" if value is None or value == "" else str(value)


def _money(value) -> str:
    return "미확인" if value is None else f"{value:,}원"


def _table(document, headings, rows, widths) -> None:
    table = document.add_table(rows=1, cols=len(headings))
    table.style = "Table Grid"
    table.autofit = False
    for column, width in zip(table.columns, widths):
        column.width = Inches(width)
    for index, values in enumerate([headings, *rows]):
        row = table.rows[0] if index == 0 else table.add_row()
        properties = row._tr.get_or_add_trPr()
        properties.append(OxmlElement("w:cantSplit"))
        if index == 0:
            properties.append(OxmlElement("w:tblHeader"))
        for cell, value, width in zip(row.cells, values, widths):
            cell.width = Inches(width)
            paragraph = cell.paragraphs[0]
            paragraph.paragraph_format.space_before = Pt(3)
            paragraph.paragraph_format.space_after = Pt(3)
            paragraph.add_run(value).bold = index == 0


def export_docx(report: CaseDocument, case_input: dict, output: Path | BinaryIO) -> None:
    document = Document()
    page = document.sections[0]
    page.top_margin = page.bottom_margin = Inches(0.65)
    page.left_margin = page.right_margin = Inches(0.75)
    grid = page._sectPr.find(qn("w:docGrid"))
    if grid is not None:
        page._sectPr.remove(grid)
    for name, size in (("Normal", 10.5), ("Title", 21), ("Heading 1", 13)):
        style = document.styles[name]
        style.font.name = "Malgun Gothic"
        style.font.size = Pt(size)
        style.font.color.rgb = RGBColor(0, 0, 0)
        style.element.get_or_add_rPr().rFonts.set(qn("w:eastAsia"), "Malgun Gothic")
        style.paragraph_format.line_spacing = Pt(size + 4)
        for border in style.element.findall(".//" + qn("w:pBdr")):
            border.getparent().remove(border)

    document.add_paragraph(report.title, "Title")
    document.add_paragraph(f"사건 {_text(case_input.get('case_id'))} · 기준 시각 {_text(case_input.get('as_of'))}")

    document.add_heading("상담사 고객 및 원예약", level=1)
    counselor = case_input.get("counselor") or {}
    customer = case_input.get("customer") or {}
    booking = case_input.get("booking") or {}
    paid = booking.get("paid_krw")
    _table(document, ("항목", "기록"), [
        ("담당 상담사", " · ".join(_text(counselor.get(key)) for key in ("name", "id", "team"))),
        ("고객과 요청", " · ".join(_text(customer.get(key)) for key in ("name", "id", "channel", "request")) + f" · 투숙 인원 {_text(customer.get('guests'))}"),
        ("원예약", " · ".join(_text(booking.get(key)) for key in ("id", "property", "room", "check_in", "check_out")) + f" · {_money(paid)}"),
    ], (1.5, 5.5))

    criteria = case_input.get("search_criteria") or {}
    offered = case_input.get("offered_stays") or []
    replacement = case_input.get("replacement") or {}
    selected = replacement.get("selected_id")
    document.add_heading("대체 숙소 검색과 고객 제안", level=1)
    document.add_paragraph(
        f"필수 {_text(criteria.get('required'))} · 반경 {_text(criteria.get('radius_km'))} km "
        f"· 기준가 {_money(criteria.get('price_ceiling_krw'))} · 비교 {_text(criteria.get('comparison'))}"
    )
    if offered:
        rows = []
        for stay in offered:
            total = stay.get("total_krw")
            baseline = stay.get("comparable_original_krw")
            difference = f"{total - baseline:+,}원" if total is not None and baseline is not None else "미확인"
            choice = " · 고객 선택" if selected and stay.get("id") == selected else ""
            rows.append((
                _text(stay.get("name")),
                f"{_money(total)} / {difference}",
                f"{_text(stay.get('distance_km'))} km / {_text(stay.get('drive_minutes'))}분",
                _text(stay.get("conditions")) + choice,
            ))
        _table(document, ("전달한 후보", "총액 / 차액", "직선거리 / 이동", "조건 및 선택"), rows, (1.5, 1.45, 1.5, 2.55))
    else:
        document.add_paragraph("고객에게 전달한 후보 기록 미확인")
    document.add_paragraph(
        f"{'시연 선택 기록' if replacement.get('simulation') else '고객 동의'} {_text(replacement.get('consent_at'))} · 요청번호 {_text(replacement.get('request_id'))} "
        f"· 확정번호 {_text(replacement.get('confirmation_id'))} · 예약 {_text(replacement.get('booking_status'))} "
        f"· 실제 입실 {_text(replacement.get('checkin_status'))}"
    )

    for section in report.sections:
        document.add_heading(section.heading, level=1)
        for paragraph in section.paragraphs:
            document.add_paragraph(paragraph)

    document.add_heading("상담사가 선택한 후속 업무", level=1)
    if report.selected_follow_ups:
        _table(document, ("업무", "담당 / 기한", "사건 근거 / 규정"), [
            (task.description, f"{_text(task.owner)} / {_text(task.due_at)}", ", ".join(task.evidence_refs + task.policy_refs))
            for task in report.selected_follow_ups
        ], (2.9, 1.6, 2.5))
    else:
        document.add_paragraph("선택한 업무 없음")
    document.save(output)
