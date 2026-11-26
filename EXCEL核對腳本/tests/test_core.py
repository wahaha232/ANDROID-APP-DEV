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
from excel_fixer import _coerce_fix_value, _excel_file_format  # noqa: E402
from o193d_checker import (  # noqa: E402
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


if __name__ == "__main__":
    unittest.main()
