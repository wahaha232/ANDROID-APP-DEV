# -*- coding: utf-8 -*-
"""核心純函式與解析流程的單元測試（不需要 Excel 或網路）。"""

import io
import sys
import tempfile
import unittest
import zipfile
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import pdf_parser  # noqa: E402
from pdf_parser import (  # noqa: E402
    TestReportData,
    _extract_report_date,
    _extract_report_nos,
    _format_expected_value,
    is_valid_cas_checksum,
    normalize_unit,
    parse_date_string_ex,
    parse_test_report_pdf,
)
from excel_checker import (  # noqa: E402
    CheckIssue,
    CheckSummary,
    UnifiedSheet,
    _match_test_report,
    _parse_weight_and_unit,
    _row_blocks,
    check_sheet,
    col_index_to_letter,
    detect_header_layout,
    results_match,
)
from excel_fixer import _coerce_fix_value, _excel_file_format, apply_fixes_via_excel_com  # noqa: E402
from fmd_matcher import (  # noqa: E402
    FMDEntry,
    FMDRecord,
    decode_samsung_mlcc_part_no,
    load_fmd_index,
    match_samsung_fmd,
    parse_samsung_fmd_file,
)
from o193d_checker import (  # noqa: E402
    DEFAULT_BACKUP_DIR,
    DEFAULT_OUTPUT_DIR,
    DEFAULT_REPORT_DIR,
    FileCheckResult,
    _aggregate_exit_code,
    _collect_excel_targets,
    format_batch_summary,
    main,
)
import embed_extractor  # noqa: E402
from embed_extractor import extract_embedded_pdfs  # noqa: E402
from excel_checker import load_workbook_sheets  # noqa: E402


def make_sheet(rows, merged_map=None):
    """以 0-based 的列清單建立 UnifiedSheet。"""
    nrows = len(rows)
    ncols = max((len(r) for r in rows), default=0)
    grid = [list(r) + [None] * (ncols - len(r)) for r in rows]
    return UnifiedSheet("T", nrows, ncols, grid, merged_map or {})


class TestUtils(unittest.TestCase):
    def test_col_index_to_letter(self):
        self.assertEqual(col_index_to_letter(1), "A")
        self.assertEqual(col_index_to_letter(26), "Z")
        self.assertEqual(col_index_to_letter(27), "AA")
        self.assertEqual(col_index_to_letter(30), "AD")

    def test_normalize_unit(self):
        self.assertEqual(normalize_unit("mg/kg"), "ppm")
        self.assertEqual(normalize_unit("mg/kg (ppm)"), "ppm")
        self.assertEqual(normalize_unit("㎍/cm2"), "μg/cm2")
        self.assertEqual(normalize_unit("ug/cm²"), "μg/cm2")
        self.assertEqual(normalize_unit("㎎/㎏"), "ppm")
        self.assertEqual(normalize_unit("%"), "%")

    def test_weight_parse_rejects_notes(self):
        self.assertEqual(_parse_weight_and_unit("0.197mg"), (0.197, "mg"))
        self.assertIsNone(_parse_weight_and_unit("GQS-ZZ09-13"))
        self.assertIsNone(_parse_weight_and_unit("★ 供應商應保證 GQS-ZZ09-13"))
        self.assertEqual(_parse_weight_and_unit("約 1.5 kg"), (1.5, "kg"))

    def test_cas_checksum(self):
        self.assertTrue(is_valid_cas_checksum("117-81-7"))
        self.assertTrue(is_valid_cas_checksum("7440-43-9"))
        self.assertFalse(is_valid_cas_checksum("117-81-8"))


class TestValueFormatting(unittest.TestCase):
    def test_expected_value(self):
        self.assertEqual(_format_expected_value("N.D.", "2", "ppm"), "<2")
        self.assertEqual(
            _format_expected_value("Negative", "0.10", "μg/cm2"), "<0.10μg/cm2"
        )
        self.assertEqual(_format_expected_value("< 0.10", "2", "ppm"), "<0.10")
        self.assertEqual(
            _format_expected_value("<0.10 ug/cm2", None, "ppm"), "<0.10μg/cm2"
        )
        self.assertEqual(_format_expected_value("38", "2", "ppm"), "38")
        self.assertEqual(_format_expected_value("1,250", None, "ppm"), "1250")
        self.assertEqual(_format_expected_value("-0.5", None, "ppm"), "-0.5")
        self.assertEqual(_format_expected_value(">1000", None, "ppm"), ">1000")
        self.assertIsNone(_format_expected_value("N.D.", None, "ppm"))

    def test_results_match(self):
        self.assertTrue(results_match("38.0", "38"))
        self.assertTrue(results_match("<0.10 ug/cm2", "<0.1μg/cm2"))
        self.assertTrue(results_match("≤2", "<2"))
        self.assertTrue(results_match("~1.0", "1"))
        self.assertTrue(results_match("0.5 %", "0.5%"))
        self.assertFalse(results_match(">5", "5"))
        self.assertFalse(results_match("N.D.", "<2"))
        self.assertFalse(results_match("<2", "2"))

    def test_dates(self):
        self.assertEqual(parse_date_string_ex("2024/8/1"), ("2024/08/01", False))
        self.assertEqual(parse_date_string_ex("Aug 15, 2024"), ("2024/08/15", False))
        self.assertEqual(parse_date_string_ex("15-Aug-2024"), ("2024/08/15", False))
        self.assertEqual(parse_date_string_ex("13/04/2024"), ("2024/04/13", False))
        self.assertEqual(parse_date_string_ex("03/04/2024"), ("2024/03/04", True))

    def test_report_date_label_ignores_receipt_date(self):
        en = "Sample Receipt Date: 2024/06/20\nReport Date: 2024/07/01"
        zh = "收樣日期：2024/06/20\n報告日期：2024/07/01"
        self.assertEqual(_extract_report_date(en)[0], "2024/07/01")
        self.assertEqual(_extract_report_date(zh)[0], "2024/07/01")


class TestSheetGrouping(unittest.TestCase):
    def test_row_blocks_repeated_text(self):
        sheet = make_sheet([
            [None, "P1"],
            [None, "P1"],
            [None, "P1"],
            [None, "P2"],
            [None, "P2"],
        ])
        blocks = _row_blocks(sheet, [1, 2, 3, 4, 5], 2)
        self.assertEqual(blocks, [[1, 2, 3], [4, 5]])

    def test_row_blocks_merged_anchor_row(self):
        sheet = make_sheet(
            [
                [None, "Housing"],
                [None, None],
                [None, "Other"],
            ],
            {(2, 2): (1, 2)},
        )
        self.assertEqual(_row_blocks(sheet, [1, 2, 3], 2), [[1, 2], [3]])

    def test_header_layout(self):
        sheet = make_sheet([
            [None, "部件名稱", "部件重量(g)", "重量單位", "含量", "測試報告編號", "Cd", "Pb"],
            [None, "P1", 1.0, "g", 60, "R-1", "N.D.", "N.D."],
        ])
        layout = detect_header_layout(sheet)
        self.assertIsNotNone(layout)
        assert layout is not None
        self.assertEqual(layout.meta_cols["part_name"], 2)
        self.assertEqual(layout.meta_cols["part_weight"], 3)
        self.assertEqual(layout.meta_cols["weight_unit"], 4)
        self.assertEqual(layout.meta_cols["content_pct"], 5)
        self.assertEqual(layout.meta_cols["report_no"], 6)
        self.assertEqual(layout.substance_cols["Cd"], 7)
        self.assertEqual(layout.substance_cols["Pb"], 8)
        self.assertEqual(layout.weight_header_unit, "g")

    def test_weight_percentage_not_weight(self):
        sheet = make_sheet([
            [None, "部件名稱", "重量百分比", "測試報告編號"],
            [None, "P1", 100, "R-1"],
        ])
        layout = detect_header_layout(sheet)
        assert layout is not None
        self.assertNotIn("part_weight", layout.meta_cols)
        self.assertEqual(layout.meta_cols["content_pct"], 3)

    def test_substance_columns_survive_limit_header_row(self):
        sheet = make_sheet([
            [None, "部件名稱", "Cd", "Pb"],
            [None, None, "限值(ppm)", "限值(ppm)"],
            [None, "P1", "N.D.", "N.D."],
        ])
        layout = detect_header_layout(sheet)
        assert layout is not None
        self.assertEqual(layout.substance_cols["Cd"], 3)
        self.assertEqual(layout.substance_cols["Pb"], 4)
        self.assertEqual(layout.data_start_row, 3)


class TestCheckSheetGrouping(unittest.TestCase):
    def test_repeated_part_and_material_no_false_positive(self):
        sheet = make_sheet([
            ["總重量: 2 g"],
            [None, "部件名稱", "部件重量(g)", "均質材料", "含量(%)"],
            [None, "P1", 1, "M1", 60],
            [None, "P1", 1, "M1", 40],
            [None, "P2", 1, "M2", 30],
            [None, "P2", 1, "M2", 70],
        ])
        issues, notes, checked = check_sheet(sheet, [], [])
        self.assertTrue(checked)
        names = {i.item_name for i in issues}
        self.assertNotIn("重量驗算", names)
        self.assertNotIn("成分含量總和", names)

    def test_total_weight_with_thousands_separator(self):
        sheet = make_sheet([
            ["總重量: 12,500 g"],
            [None, "部件名稱", "部件重量(g)"],
            [None, "P1", 10000],
            [None, "P2", 2500],
        ])
        issues, notes, checked = check_sheet(sheet, [], [])
        self.assertTrue(checked)
        self.assertNotIn("重量驗算", {i.item_name for i in issues})

    def test_weight_unit_kilogram(self):
        sheet = make_sheet([
            ["總重量: 12,500 g"],
            [None, "部件名稱", "部件重量"],
            [None, "P1", "10 公斤"],
            [None, "P2", "2.5 公斤"],
        ])
        issues, notes, checked = check_sheet(sheet, [], [])
        self.assertTrue(checked)
        self.assertNotIn("重量驗算", {i.item_name for i in issues})

    def test_total_weight_unit_inside_label(self):
        sheet = make_sheet([
            ["總重量(kg): 1.5"],
            [None, "部件名稱", "部件重量(g)"],
            [None, "P1", 1000],
            [None, "P2", 500],
        ])
        issues, notes, checked = check_sheet(sheet, [], [])
        self.assertTrue(checked)
        self.assertNotIn("重量驗算", {i.item_name for i in issues})

    def test_weight_header_unit_chinese(self):
        sheet = make_sheet([
            ["總重量: 12,500 g"],
            [None, "部件名稱", "部件重量(公斤)"],
            [None, "P1", 10],
            [None, "P2", 2.5],
        ])
        issues, notes, checked = check_sheet(sheet, [], [])
        self.assertTrue(checked)
        self.assertNotIn("重量驗算", {i.item_name for i in issues})

    def test_composition_balance_row_without_cas(self):
        sheet = make_sheet([
            [None, "部件名稱", "均質材料", "成分名稱", "含量(%)"],
            [None, "P1", "M1", "Lead", 60],
            [None, None, None, "Balance/Other", 40],
        ])
        issues, notes, checked = check_sheet(sheet, [], [])
        self.assertTrue(checked)
        self.assertNotIn("成分含量總和", {i.item_name for i in issues})

    def test_repeated_weight_without_pct_counts_once(self):
        sheet = make_sheet([
            ["總重量: 0.1 g"],
            [None, "部件名稱", "部件重量(g)"],
            [None, "Screw", 0.1],
            [None, "Screw", 0.1],
            [None, "Screw", 0.1],
        ])
        issues, notes, checked = check_sheet(sheet, [], [])
        self.assertTrue(checked)
        self.assertNotIn("重量驗算", {i.item_name for i in issues})

    def test_same_name_100pct_counts_as_independent_parts(self):
        sheet = make_sheet([
            ["總重量: 0.3 g"],
            [None, "部件名稱", "部件重量(g)", "含量(%)"],
            [None, "Screw", 0.1, 100],
            [None, "Screw", 0.1, 100],
            [None, "Screw", 0.1, 100],
        ])
        issues, notes, checked = check_sheet(sheet, [], [])
        self.assertTrue(checked)
        self.assertNotIn("重量驗算", {i.item_name for i in issues})


class TestFixerHelpers(unittest.TestCase):
    def test_coerce_fix_value(self):
        self.assertEqual(_coerce_fix_value("38"), (38, "number"))
        self.assertEqual(_coerce_fix_value("38.5"), (38.5, "number"))
        self.assertEqual(_coerce_fix_value("<2"), ("<2", "text"))
        self.assertEqual(_coerce_fix_value("0.10μg/cm2"), ("0.10μg/cm2", "text"))
        value, kind = _coerce_fix_value("2024/07/01")
        self.assertEqual(kind, "date")
        self.assertEqual((value.year, value.month, value.day), (2024, 7, 1))

    def test_excel_file_format(self):
        self.assertEqual(_excel_file_format("a.xls"), 56)
        self.assertEqual(_excel_file_format("a.xlsm"), 52)
        self.assertEqual(_excel_file_format("a.xlsx"), 51)


class TestReportZeroResultsWarning(unittest.TestCase):
    def test_matched_report_without_results_warns(self):
        sheet = make_sheet([
            [None, "部件名稱", "測試報告編號"],
            [None, "P1", "SHAEC2401234501"],
        ])
        report = TestReportData(
            filename="r.pdf",
            source_entry="r.pdf",
            report_no="SHAEC2401234501",
            all_report_nos=["SHAEC2401234501"],
        )
        issues, notes, checked = check_sheet(sheet, [report], [])
        self.assertTrue(checked)
        self.assertTrue(
            any(i.item_name == "報告結果解析" and i.severity == "WARNING" for i in issues)
        )


class TestReportMatching(unittest.TestCase):
    def _report(self, fname, no):
        return TestReportData(filename=fname, source_entry=fname, report_no=no, all_report_nos=[no])

    def test_unique_suffix_match(self):
        reports = [self._report("a.pdf", "SHAEC2401234501-1")]
        matched, warning = _match_test_report("SHAEC2401234501", reports)
        self.assertIs(matched, reports[0])
        self.assertIsNone(warning)

    def test_ambiguous_match(self):
        reports = [
            self._report("a.pdf", "SHAEC2401234501-1"),
            self._report("b.pdf", "SHAEC2401234501-2"),
        ]
        matched, warning = _match_test_report("SHAEC2401234501", reports)
        self.assertIsNone(matched)
        self.assertIsNotNone(warning)


def _make_pdf(rows):
    import pymupdf as fitz

    doc = fitz.open()
    page = doc.new_page(width=620, height=800)
    for y, cells in rows:
        for x, text in cells:
            page.insert_text((x, y), text, fontsize=9)
    data = doc.tobytes()
    doc.close()
    return data


class TestPdfColumnOrder(unittest.TestCase):
    def test_result_after_mdl_and_limit(self):
        pdf = _make_pdf([
            (72, [(40, "Test Item"), (200, "Limit"), (300, "Unit"), (360, "MDL"), (430, "Result")]),
            (100, [(40, "Cadmium (Cd)"), (200, "1000"), (300, "mg/kg"), (360, "2"), (430, "38")]),
            (120, [(40, "Lead (Pb)"), (200, "1000"), (300, "mg/kg"), (360, "2"), (430, "N.D.")]),
        ])
        rep = parse_test_report_pdf(pdf, "sgs.pdf", "sgs.pdf")
        self.assertEqual(rep.results["Cd"].expected_value, "38")
        self.assertEqual(rep.results["Pb"].expected_value, "<2")

    def test_result_before_mdl_intertek_order(self):
        pdf = _make_pdf([
            (72, [(40, "Test Item"), (200, "Result"), (300, "MDL"), (400, "Limit")]),
            (100, [(40, "Cadmium (Cd)"), (200, "38"), (300, "2"), (400, "1000")]),
        ])
        rep = parse_test_report_pdf(pdf, "intertek.pdf", "intertek.pdf")
        self.assertEqual(rep.results["Cd"].expected_value, "38")

    def test_unparseable_result_does_not_fall_back_to_numbers(self):
        pdf = _make_pdf([
            (72, [(40, "Test Item"), (200, "Limit"), (300, "Unit"), (360, "MDL"), (430, "Result")]),
            (100, [(40, "Cadmium (Cd)"), (200, "1000"), (300, "mg/kg"), (360, "2"), (430, "Pass")]),
        ])
        rep = parse_test_report_pdf(pdf, "pass.pdf", "pass.pdf")
        self.assertNotIn("Cd", rep.results)

    def test_blank_result_does_not_use_limit(self):
        pdf = _make_pdf([
            (72, [(40, "Test Item"), (200, "Limit"), (300, "Unit"), (360, "MDL"), (430, "Result")]),
            (100, [(40, "Cadmium (Cd)"), (200, "1000"), (300, "mg/kg"), (360, "2"), (430, "-")]),
        ])
        rep = parse_test_report_pdf(pdf, "blank.pdf", "blank.pdf")
        self.assertNotIn("Cd", rep.results)

    def test_cr6_non_ppm_unit(self):
        pdf = _make_pdf([
            (60, [(40, "IEC 62321-7-1 test")]),
            (72, [(40, "Test Item"), (200, "Limit"), (300, "Unit"), (360, "MDL"), (430, "Result")]),
            (100, [(40, "Chromium (VI)"), (200, "0.10"), (300, "ug/cm2"), (360, "0.10"), (430, "Negative")]),
        ])
        rep = parse_test_report_pdf(pdf, "cr6.pdf", "cr6.pdf")
        self.assertEqual(rep.results["Cr6+"].expected_value, "<0.10μg/cm2")

    def test_multiline_table_bands(self):
        pdf = _make_pdf([
            (72, [
                (40, "TEST ITEM"), (250, "UNIT"), (330, "TEST METHOD"),
                (450, "MDL"), (510, "RESULT"),
            ]),
            (110, [(40, "Chlorine (Cl)"), (250, "mg/kg")]),
            (122, [(320, "EN 14582, IC")]),
            (134, [(450, "30"), (510, "386")]),
            (170, [(40, "Fluorine (F)"), (250, "mg/kg")]),
            (182, [(320, "EN 14582, IC")]),
            (194, [(450, "30"), (510, "N.D.")]),
        ])
        rep = parse_test_report_pdf(pdf, "multiline.pdf", "multiline.pdf")
        self.assertEqual(rep.results["Cl"].expected_value, "386")
        self.assertEqual(rep.results["F"].expected_value, "<30")

    def test_adjacent_multi_table_same_page(self):
        pdf = _make_pdf([
            (72, [
                (40, "Test Item"), (200, "Limit"), (300, "Unit"),
                (360, "MDL"), (430, "Result"),
            ]),
            (100, [(40, "Cadmium (Cd)"), (200, "1000"), (300, "mg/kg"), (360, "2"), (430, "38")]),
            (120, [(40, "Lead (Pb)"), (200, "1000"), (300, "mg/kg"), (360, "2"), (430, "N.D.")]),
            (140, [
                (40, "Test Item"), (200, "Limit"), (300, "Unit"),
                (360, "MDL"), (430, "Result"),
            ]),
            (160, [(40, "Chlorine (Cl)"), (200, "1000"), (300, "mg/kg"), (360, "50"), (430, "120")]),
            (180, [(40, "Bromine (Br)"), (200, "1000"), (300, "mg/kg"), (360, "50"), (430, "N.D.")]),
        ])
        rep = parse_test_report_pdf(pdf, "two-tables.pdf", "two-tables.pdf")
        self.assertEqual(rep.results["Cd"].expected_value, "38")
        self.assertEqual(rep.results["Pb"].expected_value, "<2")
        self.assertEqual(rep.results["Cl"].expected_value, "120")
        self.assertEqual(rep.results["Br"].expected_value, "<50")

    def test_report_no_with_parenthesized_english_label(self):
        text = (
            "报告号(REPORT NO.) \n: RT26R-S6850-001-C \n"
            "样品编号(SAMPLE ID NO.)\n: RT26R-S6850-001"
        )
        self.assertIn("RT26R-S6850-001-C", _extract_report_nos(text))


class TestEndToEndEmbeddedPdf(unittest.TestCase):
    def test_extract_parse_and_check(self):
        import openpyxl

        pdf = _make_pdf([
            (30, [(40, "TEST REPORT")]),
            (45, [(40, "Report No.: SHAEC2401234501")]),
            (60, [(40, "Report Date: 2024/07/01"), (250, "SGS")]),
            (90, [(40, "Test Item"), (200, "Limit"), (300, "Unit"), (360, "MDL"), (430, "Result")]),
            (120, [(40, "Cadmium (Cd)"), (200, "1000"), (300, "mg/kg"), (360, "2"), (430, "38")]),
            (140, [(40, "Lead (Pb)"), (200, "1000"), (300, "mg/kg"), (360, "2"), (430, "N.D.")]),
        ])

        with tempfile.TemporaryDirectory() as td:
            xlsx = Path(td) / "sample.xlsx"
            wb = openpyxl.Workbook()
            ws = wb.active
            ws.title = "展開表"
            ws["A1"] = "總重量: 1 g"
            headers = [
                "", "部件名稱", "部件重量(g)", "均質材料", "含量(%)",
                "測試報告編號", "測試機構", "測試日期", "Cd", "Pb",
            ]
            for c, v in enumerate(headers, 1):
                ws.cell(2, c, v)
            data = [
                None, "P1", 1, "M1", 100,
                "SHAEC2401234501", "SGS", "2024/07/01", 38, "<2",
            ]
            for c, v in enumerate(data, 1):
                ws.cell(3, c, v)
            wb.save(xlsx)

            with zipfile.ZipFile(xlsx, "a") as zf:
                zf.writestr("xl/embeddings/embed1.pdf", pdf)

            pdfs = extract_embedded_pdfs(str(xlsx))
            self.assertEqual(len(pdfs), 1)
            self.assertEqual(pdfs[0].category, "test_report")

            rep = parse_test_report_pdf(
                pdfs[0].data, pdfs[0].filename, pdfs[0].source_entry
            )
            self.assertEqual(rep.report_no, "SHAEC2401234501")
            self.assertEqual(rep.agency, "SGS")
            self.assertEqual(rep.report_date, "2024/07/01")
            self.assertTrue(rep.date_is_labeled)
            self.assertEqual(rep.results["Cd"].expected_value, "38")
            self.assertEqual(rep.results["Pb"].expected_value, "<2")

            sheets = load_workbook_sheets(str(xlsx))
            issues, notes, checked = check_sheet(sheets[0], [rep], [])
            self.assertTrue(checked)
            self.assertEqual([i for i in issues if i.severity == "ERROR"], [])


class TestInputLimits(unittest.TestCase):
    def test_embedding_entry_size_cap(self):
        import openpyxl

        pdf = b"%PDF-1.4\n" + (b"A" * 100) + b"\n%%EOF"
        with tempfile.TemporaryDirectory() as td:
            xlsx = Path(td) / "cap.xlsx"
            wb = openpyxl.Workbook()
            wb.active["A1"] = "x"
            wb.save(xlsx)
            with zipfile.ZipFile(xlsx, "a") as zf:
                zf.writestr("xl/embeddings/x.pdf", pdf)

            with patch.object(embed_extractor, "MAX_EMBED_ENTRY_BYTES", 10):
                self.assertEqual(extract_embedded_pdfs(str(xlsx)), [])

    def test_max_column_capped(self):
        import openpyxl

        with tempfile.TemporaryDirectory() as td:
            xlsx = Path(td) / "wide.xlsx"
            wb = openpyxl.Workbook()
            ws = wb.active
            ws["A1"] = "x"
            ws["XFD1"] = "wide"
            wb.save(xlsx)

            sheets = load_workbook_sheets(str(xlsx))
            self.assertEqual(sheets[0].ncols, 35)


class TestBatchTargets(unittest.TestCase):
    def test_collect_files_and_directories(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root / "a.xlsx").write_bytes(b"x")
            (root / "b.XLS").write_bytes(b"x")
            (root / "~$lock.xlsx").write_bytes(b"x")
            (root / "note.txt").write_text("x")
            sub = root / "sub"
            sub.mkdir()
            (sub / "c.xlsx").write_bytes(b"x")

            targets, errors = _collect_excel_targets([str(root)], False)
            self.assertEqual(errors, [])
            self.assertEqual({Path(p).name for p in targets}, {"a.xlsx", "b.XLS"})

            targets, errors = _collect_excel_targets([str(root)], True)
            self.assertEqual({Path(p).name for p in targets}, {"a.xlsx", "b.XLS", "c.xlsx"})

            targets, errors = _collect_excel_targets([str(root / "missing.xlsx")], False)
            self.assertEqual(targets, [])
            self.assertEqual(len(errors), 1)

            targets, errors = _collect_excel_targets(
                [str(root / "a.xlsx"), str(root / "a.xlsx")], False
            )
            self.assertEqual(len(targets), 1)

    def test_aggregate_exit_code(self):
        passed = FileCheckResult("a", 0)
        fixable = FileCheckResult("b", 2)
        manual = FileCheckResult("c", 3)
        failed = FileCheckResult("d", 1, ok=False, error="boom")
        self.assertEqual(_aggregate_exit_code([passed], []), 0)
        self.assertEqual(_aggregate_exit_code([passed, fixable], []), 2)
        self.assertEqual(_aggregate_exit_code([passed, manual], []), 3)
        self.assertEqual(_aggregate_exit_code([passed, failed], []), 1)
        self.assertEqual(_aggregate_exit_code([passed], ["bad input"]), 1)

    def test_batch_summary(self):
        summary = CheckSummary(excel_path="C:\\x.xlsx")
        summary.issues.append(
            CheckIssue(
                sheet_name="S",
                cell_address="A1",
                item_name="測試",
                actual_value="1",
                expected_value="2",
                severity="ERROR",
                fixable=True,
                fix_value="2",
            )
        )
        result = FileCheckResult("C:\\x.xlsx", 2, summary=summary)
        text = format_batch_summary([result], [])
        self.assertIn("批次核對摘要", text)
        self.assertIn("可修正", text)
        self.assertIn("C:\\x.xlsx", text)

        failed = FileCheckResult("C:\\bad.xlsx", 1, ok=False, error="boom")
        text = format_batch_summary([failed], ["找不到檔案"])
        self.assertIn("失敗", text)
        self.assertIn("boom", text)
        self.assertIn("找不到檔案", text)


class TestExampleSheet(unittest.TestCase):
    def test_example_sheet_is_skipped_by_default(self):
        import openpyxl

        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "two_sheets.xlsx"
            wb = openpyxl.Workbook()
            ws = wb.active
            ws.title = "資料"
            for c, value in enumerate(["", "部件名稱", "均質材料", "含量(%)"], 1):
                ws.cell(2, c, value)
            for c, value in enumerate(["", "P1", "M1", 100], 1):
                ws.cell(3, c, value)

            ex = wb.create_sheet("填寫範例Example")
            for c, value in enumerate(["", "部件名稱", "均質材料", "含量(%)"], 1):
                ex.cell(2, c, value)
            for c, value in enumerate(["", "P2", "M2", 50], 1):
                ex.cell(3, c, value)
            wb.save(path)

            out = io.StringIO()
            with redirect_stdout(out), redirect_stderr(io.StringIO()):
                rc = main([str(path)])
            self.assertEqual(rc, 0)
            self.assertIn("已略過", out.getvalue())

            out = io.StringIO()
            with redirect_stdout(out), redirect_stderr(io.StringIO()):
                rc = main([str(path), "--include-example"])
            self.assertEqual(rc, 3)
            self.assertIn("成分含量總和", out.getvalue())


class TestBatchMain(unittest.TestCase):
    def test_main_batch_isolates_failures(self):
        import openpyxl

        with tempfile.TemporaryDirectory() as td:
            good = Path(td) / "good.xlsx"
            bad = Path(td) / "bad.xlsx"

            wb = openpyxl.Workbook()
            ws = wb.active
            ws.title = "展開表"
            for c, value in enumerate(["", "部件名稱", "均質材料", "含量(%)"], 1):
                ws.cell(2, c, value)
            for c, value in enumerate(["", "P1", "M1", 100], 1):
                ws.cell(3, c, value)
            wb.save(good)

            wb = openpyxl.Workbook()
            wb.active["A1"] = "no header"
            wb.save(bad)

            out = io.StringIO()
            err = io.StringIO()
            with redirect_stdout(out), redirect_stderr(err):
                rc = main([str(bad), str(good)])

            self.assertEqual(rc, 1)
            self.assertIn("批次核對摘要", out.getvalue())
            self.assertIn("通過", out.getvalue())
            self.assertIn("失敗", out.getvalue())
            self.assertIn("沒有核對任何工作表", err.getvalue())


class TestOutputDirectories(unittest.TestCase):
    def test_default_directories_under_script_dir(self):
        script_dir = Path(__file__).resolve().parents[1]
        self.assertEqual(Path(DEFAULT_OUTPUT_DIR), script_dir / "output")
        self.assertEqual(Path(DEFAULT_REPORT_DIR), script_dir / "reports")
        self.assertEqual(Path(DEFAULT_BACKUP_DIR), script_dir / "backup")

    def test_collect_targets_excludes_output_dirs(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root / "source.xlsx").write_bytes(b"x")
            for folder in ("output", "reports", "backup"):
                d = root / folder
                d.mkdir()
                (d / f"{folder}_file.xlsx").write_bytes(b"x")

            targets, errors = _collect_excel_targets(
                [str(root)],
                True,
                exclude_dirs=[str(root / "output"), str(root / "reports"), str(root / "backup")],
            )
            self.assertEqual(errors, [])
            self.assertEqual({Path(p).name for p in targets}, {"source.xlsx"})

    def test_save_report_writes_to_report_dir_and_avoids_name_collision(self):
        import openpyxl

        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            dir_a = root / "a"
            dir_b = root / "b"
            report_dir = root / "reports"
            dir_a.mkdir()
            dir_b.mkdir()

            for d in (dir_a, dir_b):
                wb = openpyxl.Workbook()
                ws = wb.active
                ws.title = "展開表"
                for c, value in enumerate(["", "部件名稱", "均質材料", "含量(%)"], 1):
                    ws.cell(2, c, value)
                for c, value in enumerate(["", "P1", "M1", 100], 1):
                    ws.cell(3, c, value)
                wb.save(d / "same.xlsx")

            with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
                rc = main([
                    str(dir_a / "same.xlsx"),
                    str(dir_b / "same.xlsx"),
                    "--save-report",
                    "--report-dir",
                    str(report_dir),
                    "--output-dir",
                    str(root / "output"),
                ])

            self.assertEqual(rc, 0)
            self.assertFalse((dir_a / "same_核對報告.txt").exists())
            self.assertFalse((dir_b / "same_核對報告.txt").exists())
            self.assertTrue((report_dir / "same_核對報告.txt").exists())
            self.assertTrue((report_dir / "same_核對報告_2.txt").exists())
            self.assertTrue((report_dir / "批次核對摘要.txt").exists())

    def test_apply_fixes_keeps_original_and_writes_backup_and_output(self):
        class _DummyFont:
            Color = None

        class _DummyCell:
            def __init__(self):
                self.NumberFormat = None
                self.Value = None
                self.Font = _DummyFont()

        class _DummySheet:
            def __init__(self):
                self.cells = {}

            def Range(self, addr):
                return self.cells.setdefault(addr, _DummyCell())

        class _DummyWorkbook:
            def __init__(self, filename):
                self.filename = filename
                self.sheets = {"展開表": _DummySheet()}
                self.saved = False
                self.closed = False

            def Worksheets(self, name):
                return self.sheets[name]

            def Save(self):
                self.saved = True

            def Close(self, SaveChanges=False):
                self.closed = True

        class _DummyWorkbooks:
            def __init__(self):
                self.opened = []

            def Open(self, Filename, **kwargs):
                wb = _DummyWorkbook(Filename)
                self.opened.append(wb)
                return wb

        class _DummyExcel:
            def __init__(self):
                self.Workbooks = _DummyWorkbooks()

        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            src = root / "part.xlsx"
            src.write_bytes(b"ORIGINAL_CONTENT")
            out_dir = root / "output"
            bak_dir = root / "backup"

            issue = CheckIssue(
                sheet_name="展開表",
                cell_address="C3",
                item_name="Cd",
                actual_value="N.D.",
                expected_value="<2",
                severity="ERROR",
                fixable=True,
                fix_value="<2",
            )

            excel = _DummyExcel()
            res1 = apply_fixes_via_excel_com(
                str(src), [issue], excel_app=excel, output_dir=str(out_dir), backup_dir=str(bak_dir)
            )
            res2 = apply_fixes_via_excel_com(
                str(src), [issue], excel_app=excel, output_dir=str(out_dir), backup_dir=str(bak_dir)
            )

            self.assertEqual(src.read_bytes(), b"ORIGINAL_CONTENT")
            self.assertEqual(Path(res1.backup_path), bak_dir / "part_修正前備份.xlsx")
            self.assertEqual(Path(res1.saved_path), out_dir / "part_已修正.xlsx")
            self.assertEqual(Path(res2.backup_path), bak_dir / "part_修正前備份.xlsx")
            self.assertEqual(Path(res2.saved_path), out_dir / "part_已修正_2.xlsx")
            self.assertTrue(Path(res1.backup_path).exists())
            self.assertTrue(Path(res1.saved_path).exists())
            self.assertEqual(res1.fixed_count, 1)
            self.assertEqual(excel.Workbooks.opened[0].filename, str(out_dir / "part_已修正.xlsx"))
            self.assertTrue(excel.Workbooks.opened[0].saved)
            self.assertTrue(excel.Workbooks.opened[0].closed)


class TestColumnsKMComparison(unittest.TestCase):
    def test_extract_date_with_bilingual_date_label(self):
        text = (
            "报告号(REPORT NO.) \n: RT26R-S6850-001-C \n"
            "日期(DATE) : 2026 年09 月29 日 \n"
            "样品收到日期 (SAMPLE RECEIVED) : 2026年 09月03日"
        )
        parsed, _raw, labeled = _extract_report_date(text)
        self.assertEqual(parsed, "2026/09/29")
        self.assertTrue(labeled)

    def test_columns_k_to_m_mismatch_blank_and_unreferenced(self):
        sheet = make_sheet([
            [None, "部件名稱", "均質材料", "含量(%)", "測試報告編號", "測試機構", "測試日期", "Cd"],
            # K 欄少 -C（模糊配對成功但編號不完全一致）、L 欄機構錯、M 欄日期錯
            [None, "P1", "M1", 100, "RT26R-S6850-001", "SGS", "2026/01/01", "<2"],
            # K 欄空白但有材料與數據
            [None, "P2", "M2", 100, "", "", "", "<2"],
        ])
        rep1 = TestReportData(
            filename="r1.pdf",
            source_entry="r1.pdf",
            report_no="RT26R-S6850-001-C",
            all_report_nos=["RT26R-S6850-001-C"],
            agency="Intertek",
            report_date="2026/09/29",
            date_is_labeled=True,
            results={
                "Cd": pdf_parser.SubstanceResult(
                    substance_key="Cd", expected_value="<2", raw_result="N.D.", mdl="2", page=2
                )
            },
        )
        rep_unused = TestReportData(
            filename="unused.pdf",
            source_entry="unused.pdf",
            report_no="RT26R-S6850-999-C",
            all_report_nos=["RT26R-S6850-999-C"],
            agency="Intertek",
            report_date="2026/09/29",
            date_is_labeled=True,
        )

        issues, notes, checked = check_sheet(sheet, [rep1, rep_unused], [])
        self.assertTrue(checked)

        by_item = {(i.cell_address, i.item_name): i for i in issues}
        self.assertIn(("E2", "測試報告編號"), by_item)
        self.assertTrue(by_item[("E2", "測試報告編號")].fixable)
        self.assertEqual(by_item[("E2", "測試報告編號")].fix_value, "RT26R-S6850-001-C")

        self.assertIn(("F2", "測試機構"), by_item)
        self.assertTrue(by_item[("F2", "測試機構")].fixable)
        self.assertEqual(by_item[("F2", "測試機構")].fix_value, "Intertek")

        self.assertIn(("G2", "測試日期"), by_item)
        self.assertTrue(by_item[("G2", "測試日期")].fixable)
        self.assertEqual(by_item[("G2", "測試日期")].fix_value, "2026/09/29")

        self.assertIn(("E3", "測試報告編號"), by_item)
        self.assertEqual(by_item[("E3", "測試報告編號")].severity, "WARNING")

        self.assertIn(("E欄", "未引用測試報告"), by_item)
        self.assertEqual(by_item[("E欄", "未引用測試報告")].expected_value, "RT26R-S6850-999-C")
        self.assertTrue(any("已比對內嵌 PDF" in n for n in notes))


class TestSamsungFMDComparison(unittest.TestCase):
    def test_decode_samsung_mlcc_part_no(self):
        self.assertEqual(decode_samsung_mlcc_part_no("CL03A105MQ3CSNH"), ("X5R", "0603"))
        self.assertEqual(decode_samsung_mlcc_part_no("CL10B104KB8NNNC"), ("X7R", "1608"))
        self.assertEqual(decode_samsung_mlcc_part_no("CL21X475KAFNNNE"), ("X6S", "2012"))
        self.assertIsNone(decode_samsung_mlcc_part_no("CIGT201208EH"))

    def test_match_fmd_three_tier_and_disambiguation(self):
        rec_2026 = FMDRecord(
            filepath="C:\\fmd\\2026\\X5R_0603_2026_V1.xlsx",
            filename="Material Declaration Data Sheet.(X5R 0603 Normal(Ni) TYPE)_2026_V1.xlsx",
            relpath="2026\\X5R_0603_2026_V1.xlsx",
            mtime=1.0,
            size=100,
            part_numbers=["CL03A**********"],
            entries=[FMDEntry("Ceramic body", "Ceramic BaTiO3", "Barium Titanate", "12047-27-7", 0.197)],
            total_weight_mg=0.32,
            year=2026,
            version=1,
            is_root=False,
        )
        rec_2027 = FMDRecord(
            filepath="C:\\fmd\\X5R_0603_2027_V1.xlsx",
            filename="Material Declaration Data Sheet.(X5R 0603 Normal(Ni) TYPE)_2027_V1.xlsx",
            relpath="X5R_0603_2027_V1.xlsx",
            mtime=2.0,
            size=100,
            part_numbers=["CL03A**********"],
            entries=[FMDEntry("Ceramic body", "Ceramic BaTiO3", "Barium Titanate", "12047-27-7", 0.197)],
            total_weight_mg=0.32,
            year=2027,
            version=1,
            is_root=True,
        )
        rec_slash = FMDRecord(
            filepath="C:\\fmd\\CL21B_IMC_2027_V1.xlsx",
            filename="CL21B_IMC_2027_V1.xlsx",
            relpath="CL21B_IMC_2027_V1.xlsx",
            mtime=2.0,
            size=100,
            part_numbers=["CL21B******6/P/R***"],
            entries=[FMDEntry("Termination (IMC Epoxy)", "IMC Epoxy", "Copper", "7440-50-8", 0.41)],
            total_weight_mg=26.3,
            year=2027,
            version=1,
            is_root=True,
        )

        matched, warn = match_samsung_fmd("CL03A105MQ3CSNH", [rec_2026, rec_2027])
        self.assertIs(matched, rec_2027)
        self.assertIsNone(warn)

        matched2, warn2 = match_samsung_fmd("CL21B475KLB6PJE", [rec_2027, rec_slash])
        self.assertIs(matched2, rec_slash)
        self.assertIsNone(warn2)

    def test_parse_fmd_xlsx_and_check_columns_b_to_j(self):
        import openpyxl

        with tempfile.TemporaryDirectory() as td:
            fmd_dir = Path(td) / "fmd"
            fmd_dir.mkdir()
            fmd_path = fmd_dir / "Material Declaration Data Sheet.(X5R 0603 Normal(Ni) TYPE)_2027_V1.xlsx"
            wb = openpyxl.Workbook()
            ws = wb.active
            ws.title = "X5R TYPE"
            headers = ["", "PRODUCT", "Part Number", "Part Name", "Material Name", "Content\n(mg)", "Substance Name", "Content\n(wt%, mg)", "CAS No."]
            for c, val in enumerate(headers, 1):
                ws.cell(9, c, val)
            rows = [
                ["", "Ceramic Chip Capacitors", "CL03A**********", "Ceramic body", "Ceramic BaTiO3", 0.197, "Barium Titanate", 61.5, "12047-27-7"],
                ["", None, None, "Inner electrode", "Ni", 0.103, "Nickel", 32.3, "7440-02-0"],
            ]
            for idx, rvals in enumerate(rows, 12):
                for c, val in enumerate(rvals, 1):
                    ws.cell(idx, c, val)
            wb.save(fmd_path)

            rec = parse_samsung_fmd_file(str(fmd_path), base_dir=str(fmd_dir))
            self.assertIsNotNone(rec)
            assert rec is not None
            self.assertEqual(rec.part_numbers, ["CL03A**********"])
            self.assertEqual(len(rec.entries), 2)

            cache_file = Path(td) / "cache.json"
            recs, notes = load_fmd_index(str(fmd_dir), cache_path=str(cache_file))
            self.assertEqual(len(recs), 1)
            self.assertEqual(notes, [])
            self.assertTrue(cache_file.exists())

            # 建立 O-193D 工作表：第 1 列完全相符，第 2 列故意填錯重量與 CAS
            sheet = make_sheet([
                [None, "供應商產品料號", "CL03A105MQ3CSNH"],
                [None, "總重量", "0.30mg"],
                [None, "部件名稱", "部件料號", "部件重量", "均質材料名稱", "製造商名稱", "均質材料重量", "物質名稱", "CAS No.", "含量 (%)"],
                [None, "Ceramic body", "Ceramic BaTiO3", "0.197mg", "Barium Titanate", "SAMSUNG", "0.197mg", "Barium Titanate", "12047-27-7", 100.0],
                [None, "Inner electrode", "Ni", "0.200mg", "Nickel", "WRONG_MFR", "0.200mg", "Nickel", "7440-50-8", 100.0],
            ])
            issues, check_notes, checked = check_sheet(sheet, [], [], fmd_records=recs)
            self.assertTrue(checked)
            self.assertTrue(any("欄位 B~J 已比對 SAMSUNG FMD" in n for n in check_notes))

            fmd_items = {i.item_name for i in issues if "(FMD)" in i.item_name}
            self.assertIn("部件重量(FMD)", fmd_items)
            self.assertIn("製造商名稱(FMD)", fmd_items)
            self.assertIn("均質材料重量(FMD)", fmd_items)
            self.assertIn("CAS No.(FMD)", fmd_items)


class TestOverwriteAndIterativeFix(unittest.TestCase):
    def _make_o193d_xlsx(self, path: Path, foxlink_pn: str, supplier_pn: str) -> None:
        import openpyxl

        wb = openpyxl.Workbook()
        ws = wb.active
        ws.title = "展開表"
        ws["B4"] = "Foxlink產品料號"
        ws["D4"] = foxlink_pn
        ws["B5"] = "供應商產品料號"
        ws["D5"] = supplier_pn
        for c, v in enumerate(["", "部件名稱", "均質材料", "含量(%)"], 1):
            ws.cell(9, c, v)
        for c, v in enumerate(["", "P1", "M1", 100], 1):
            ws.cell(10, c, v)
        wb.save(path)

    def test_output_overwrites_same_product_identity_and_numbers_different(self):
        from excel_fixer import _resolve_output_path

        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            out_dir = root / "output"
            group_same = out_dir / "07C6-105M-43AD_CL03A105MQ3CSNH"
            group_same.mkdir(parents=True)

            # 已存在於 output/<正崴料號_供應商料號>/ 的舊版檔案（同料號）
            existing = group_same / "sample_已修正.xlsx"
            self._make_o193d_xlsx(existing, "07C6-105M-43AD", "CL03A105MQ3CSNH")

            # 來源 1：同檔名且 Foxlink + 供應商料號皆相同 -> 歸類至同料號資料夾並直接覆蓋
            src_same = root / "sample.xlsx"
            self._make_o193d_xlsx(src_same, "07C6-105M-43AD", "CL03A105MQ3CSNH")
            resolved_same, overwrote_same = _resolve_output_path(str(src_same), str(out_dir))
            self.assertEqual(Path(resolved_same), existing)
            self.assertTrue(overwrote_same)

            # 來源 2：同檔名但料號不同 -> 自動歸類到另一個料號資料夾，不覆蓋舊版
            dir2 = root / "other"
            dir2.mkdir()
            src_diff = dir2 / "sample.xlsx"
            self._make_o193d_xlsx(src_diff, "07C6-999M-99ZZ", "CL10B104KB8NNNC")
            resolved_diff, overwrote_diff = _resolve_output_path(str(src_diff), str(out_dir))
            self.assertEqual(
                Path(resolved_diff),
                out_dir / "07C6-999M-99ZZ_CL10B104KB8NNNC" / "sample_已修正.xlsx",
            )
            self.assertFalse(overwrote_diff)

    def test_interactive_fix_loops_until_clean(self):
        import o193d_checker

        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            src = root / "part.xlsx"
            self._make_o193d_xlsx(src, "07C6-105M-43AD", "CL03A105MQ3CSNH")

            issue1 = CheckIssue("展開表", "Q14", "Cr6+", "<0.1", "<0.10μg/cm2", severity="ERROR", fixable=True, fix_value="<0.10μg/cm2")
            issue2 = CheckIssue("展開表", "Q15", "Cr6+", "<0.1", "<0.10μg/cm2", severity="ERROR", fixable=True, fix_value="<0.10μg/cm2")

            # 第 1 次初檢有 2 錯；第 1 輪修正後複驗剩 1 錯；第 2 輪修正後複驗 0 錯
            call_state = {"round": 0}

            def fake_process_file(path, args, **kwargs):
                s = CheckSummary(excel_path=path)
                s.issues.extend([issue1, issue2])
                return FileCheckResult(path=path, exit_code=2, summary=s, report_text="init")

            def fake_run_batch_fixes(results, args, batch_mode, fmd_records=None):
                call_state["round"] += 1
                res = results[0]
                v = CheckSummary(excel_path=res.path)
                if call_state["round"] == 1:
                    v.issues.append(issue2)
                    res.exit_code = 2
                else:
                    res.exit_code = 0
                res.verify_summary = v
                res.fixed_count += 1

            answers = iter(["y", "y"])
            out = io.StringIO()
            with (
                patch.object(o193d_checker, "process_file", side_effect=fake_process_file),
                patch.object(o193d_checker, "_run_batch_fixes", side_effect=fake_run_batch_fixes),
                patch("builtins.input", side_effect=lambda _prompt="": next(answers)),
                redirect_stdout(out),
                redirect_stderr(io.StringIO()),
            ):
                rc = main([str(src), "--interactive-fix", "--no-fmd"])

            self.assertEqual(rc, 0)
            self.assertEqual(call_state["round"], 2)

    def test_write_cell_fallback_number_format_for_localized_excel(self):
        from excel_fixer import EXCEL_COLOR_RED, _write_cell

        class _LocalizedCell:
            def __init__(self):
                self._fmt = "G/通用格式"
                self.Value = None
                self.Font = type("Font", (), {"Color": None})()

            @property
            def NumberFormat(self):
                return self._fmt

            @NumberFormat.setter
            def NumberFormat(self, val):
                if val == "General":
                    raise RuntimeError("無法設定種類 Range 的 NumberFormat 屬性")
                self._fmt = val

        cell = _LocalizedCell()
        _write_cell(cell, "386")
        self.assertEqual(cell.Value, 386)
        self.assertEqual(cell.NumberFormat, "G/通用格式")
        self.assertEqual(cell.Font.Color, EXCEL_COLOR_RED)

    def test_progress_bar_format_and_callback(self):
        from o193d_checker import _format_progress_bar

        bar0 = _format_progress_bar(0, 4, detail="開啟檔案...", width=10)
        bar50 = _format_progress_bar(2, 4, detail="Q14", width=10)
        bar100 = _format_progress_bar(4, 4, detail="完成", width=10)

        self.assertIn("[░░░░░░░░░░]", bar0)
        self.assertIn("0.0%", bar0)
        self.assertIn("[█████░░░░░]", bar50)
        self.assertIn("50.0%", bar50)
        self.assertIn("(2/4)", bar50)
        self.assertIn("[██████████]", bar100)
        self.assertIn("100.0%", bar100)


class TestO193DFilenameAndO202CrossCheck(unittest.TestCase):
    def test_extract_foxlink_pn_from_o193d_filename_and_header_mismatch(self):
        from excel_checker import extract_foxlink_pn_from_filename

        fname = r"D:\test\07C6-105M-43AD O-193D 環境管理物質部件展開表The Parts List of Enviromental Management Substances.xls"
        self.assertEqual(extract_foxlink_pn_from_filename(fname), "07C6-105M-43AD")

        sheet = make_sheet([
            [None, "Foxlink產品料號", "07C6-999M-WRONG"],
            [None, "部件名稱", "均質材料", "含量(%)"],
            [None, "P1", "M1", 100],
        ])
        issues, _notes, checked = check_sheet(sheet, [], [], excel_path=fname)
        self.assertTrue(checked)
        fox_issues = [i for i in issues if i.item_name == "正崴料號(檔名比對)"]
        self.assertEqual(len(fox_issues), 1)
        self.assertEqual(fox_issues[0].expected_value, "07C6-105M-43AD")
        self.assertTrue(fox_issues[0].fixable)

    def test_o202_cross_checks_o193d_and_detects_svhc_omission(self):
        import openpyxl

        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            o193d_path = root / "07C6-105M-43AD O-193D 環境管理物質部件展開表.xlsx"
            o202_path = root / "07C6-105M-43AD REACH法規SVHC物質調查表O-202 AH03.xlsx"

            # 建立 O-193D（含 1 筆 SVHC CAS: 7439-92-1 鉛）
            wb1 = openpyxl.Workbook()
            ws1 = wb1.active
            ws1.title = "環境管理物質部件展開表"
            ws1["A3"], ws1["D3"] = "產品名稱", "MLCC"
            ws1["A4"], ws1["D4"] = "Foxlink產品料號", "07C6-105M-43AD"
            ws1["A5"], ws1["D5"] = "供應商產品料號", "CL03A105MQ3CSNH"
            ws1["A7"], ws1["D7"] = "總重量", "0.32mg"
            for c, v in enumerate(["", "部件名稱", "部件重量", "均質材料", "CAS No.", "含量(%)"], 1):
                ws1.cell(9, c, v)
            for c, v in enumerate(["", "Ceramic body", "0.32mg", "Ceramic", "7439-92-1", 100], 1):
                ws1.cell(10, c, v)
            wb1.save(o193d_path)

            # 建立 O-202AH（工作表 1 範例、工作表 2 253 項 SVHC 清單、工作表 3 實際填寫頁）
            wb2 = openpyxl.Workbook()
            ws_ex = wb2.active
            ws_ex.title = "範例"
            o202_headers = [
                "供應商料號\nSupplier P/N", "正崴料號\nFoxlink P/N", "物料名稱Description",
                "含有SVHC的名稱\nThe name of SVHC", "SVHC登記號\nSVHC CAS No.",
                "物料重量 (grams)", "SVHC 重量 (grams)", "SVHC 占物料比重",
                "SVHC 使用部位/用途", "SVHC 削減日期",
            ]
            for c, v in enumerate(o202_headers, 1):
                ws_ex.cell(8, c, v)
            for c, v in enumerate(["8112-9000-XXX", "5811-XXXX-XXXX", "端子", "不含", "", "400g"], 1):
                ws_ex.cell(9, c, v)

            ws_cat = wb2.create_sheet("SVHC清單")
            ws_cat["A1"] = "REACH 要求通報的高關注物質（SVHC)一覽表"
            ws_cat["A3"], ws_cat["B3"], ws_cat["C3"] = "No", "CAS NO.", "化學物質名稱"
            ws_cat["A4"], ws_cat["B4"], ws_cat["C4"] = 189, "7439-92-1", "鉛\nLead"

            ws_real = wb2.create_sheet("調查表")
            ws_real["A1"] = "REACH法規SVHC物質調查表"
            for c, v in enumerate(o202_headers, 1):
                ws_real.cell(8, c, v)
            # 故意將供應商料號、正崴料號、物料重量填錯，且填「不含」但 O-193D 含 7439-92-1
            for c, v in enumerate(["CL03A_WRONG", "07C6-WRONG", "MLCC", "不含", "", "0.50mg"], 1):
                ws_real.cell(9, c, v)
            wb2.save(o202_path)

            out = io.StringIO()
            with redirect_stdout(out), redirect_stderr(io.StringIO()):
                rc = main([str(o202_path), "--no-fmd"])

            text = out.getvalue()
            self.assertEqual(rc, 2)
            self.assertIn("供應商料號", text)
            self.assertIn("正崴料號", text)
            self.assertIn("物料重量", text)
            self.assertIn("SVHC 漏報檢查", text)
            self.assertIn("7439-92-1", text)

    def test_batch_prints_full_detail_and_archives_clean_o202_into_group_folder(self):
        import openpyxl

        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            out_dir = root / "output"
            rep_dir = root / "reports"
            o193d_path = root / "07C6-105M-43AD O-193D 環境管理物質部件展開表.xlsx"
            o202_path = root / "07C6-105M-43AD REACH法規SVHC物質調查表O-202 AH03.xlsx"

            # O-193D：故意留 1 筆錯誤（含量 80%）以便測試批次明細表格輸出
            wb1 = openpyxl.Workbook()
            ws1 = wb1.active
            ws1.title = "環境管理物質部件展開表"
            ws1["A3"], ws1["D3"] = "產品名稱", "MLCC"
            ws1["A4"], ws1["D4"] = "Foxlink產品料號", "07C6-105M-43AD"
            ws1["A5"], ws1["D5"] = "供應商產品料號", "CL03A105MQ3CSNH"
            ws1["A7"], ws1["D7"] = "總重量", "0.32mg"
            for c, v in enumerate(["", "部件名稱", "部件重量", "均質材料", "CAS No.", "含量(%)"], 1):
                ws1.cell(9, c, v)
            for c, v in enumerate(["", "Ceramic body", "0.32mg", "Ceramic", "12047-27-7", 80], 1):
                ws1.cell(10, c, v)
            wb1.save(o193d_path)

            # O-202AH：完全正確，應自動歸檔至 output/07C6-105M-43AD_CL03A105MQ3CSNH/
            wb2 = openpyxl.Workbook()
            ws_cat = wb2.active
            ws_cat.title = "SVHC清單"
            ws_cat["A1"] = "REACH 要求通報的高關注物質（SVHC)一覽表"
            ws_cat["A3"], ws_cat["B3"], ws_cat["C3"] = "No", "CAS NO.", "化學物質名稱"
            ws_cat["A4"], ws_cat["B4"], ws_cat["C4"] = 189, "7439-92-1", "鉛"

            ws_real = wb2.create_sheet("調查表")
            o202_headers = [
                "供應商料號\nSupplier P/N", "正崴料號\nFoxlink P/N", "物料名稱Description",
                "含有SVHC的名稱\nThe name of SVHC", "SVHC登記號\nSVHC CAS No.",
                "物料重量 (grams)", "SVHC 重量 (grams)", "SVHC 占物料比重",
                "SVHC 使用部位/用途", "SVHC 削減日期",
            ]
            for c, v in enumerate(o202_headers, 1):
                ws_real.cell(8, c, v)
            for c, v in enumerate(["CL03A105MQ3CSNH", "07C6-105M-43AD", "MLCC", "不含", "", "0.32mg"], 1):
                ws_real.cell(9, c, v)
            wb2.save(o202_path)

            out = io.StringIO()
            with redirect_stdout(out), redirect_stderr(io.StringIO()):
                rc = main([
                    str(o193d_path),
                    str(o202_path),
                    "--save-report",
                    "--output-dir",
                    str(out_dir),
                    "--report-dir",
                    str(rep_dir),
                    "--no-fmd",
                ])

            text = out.getvalue()
            # 批次模式不需 --verbose 也會印出完整明細表頭與錯誤列
            self.assertIn("等級", text)
            self.assertIn("工作表!位址", text)
            self.assertIn("報告正確值", text)
            self.assertIn("成分含量總和", text)
            self.assertIn("批次核對摘要", text)
            # 無錯誤的 O-202AH 已自動搬移至 output/<正崴料號_供應商料號>/（原位置清空）
            expected_archived = out_dir / "07C6-105M-43AD_CL03A105MQ3CSNH" / o202_path.name
            self.assertTrue(expected_archived.exists())
            self.assertFalse(o202_path.exists())
            # 尚有錯誤未修正的 O-193D 保留於原位置不動
            self.assertTrue(o193d_path.exists())
            self.assertEqual(rc, 3)


if __name__ == "__main__":
    unittest.main()
