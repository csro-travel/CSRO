import unittest

from llm.postprocessing.agent import DocumentPlan, DocumentSectionPlan, split_sections

BLOCKS = {"p1": "a", "p2": "b", "p3": "c", "p4": "d"}


def plan(*starts):
    return DocumentPlan(title="t", sections=[DocumentSectionPlan(heading=h, start_block_id=b) for h, b in starts])


class SplitSectionsTest(unittest.TestCase):
    def paragraphs(self, sections):
        return [(section.heading, section.paragraphs) for section in sections]

    def test_keeps_every_paragraph_once_in_the_reviewed_order(self):
        sections = split_sections(plan(("경과", "p1"), ("조치", "p3")), BLOCKS)
        self.assertEqual(self.paragraphs(sections), [("경과", ["a", "b"]), ("조치", ["c", "d"])])

    def test_out_of_order_starts_cannot_reorder_the_text(self):
        sections = split_sections(plan(("조치", "p3"), ("경과", "p1")), BLOCKS)
        self.assertEqual([p for _, ps in self.paragraphs(sections) for p in ps], ["a", "b", "c", "d"])

    def test_first_section_reaches_back_to_the_first_block_and_a_duplicate_start_is_dropped(self):
        sections = split_sections(plan(("경과", "p2"), ("중복", "p2"), ("조치", "p4")), BLOCKS)
        self.assertEqual(self.paragraphs(sections), [("경과", ["a", "b", "c"]), ("조치", ["d"])])

    def test_rejects_a_block_that_is_not_in_the_text(self):
        with self.assertRaises(ValueError):
            split_sections(plan(("경과", "p9")), BLOCKS)


if __name__ == "__main__":
    unittest.main()
