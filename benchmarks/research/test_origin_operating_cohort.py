# SPDX-License-Identifier: Apache-2.0
import copy
import unittest

from origin_operating_cohort import PARTITIONS, build_cohort, select_cohort_rows


class OperatingCohortTests(unittest.TestCase):
    def rows(self):
        return [{'id': f'{doc}-{action}', 'document_id': f'document-{doc}', 'task': 'repair_qwerty',
                 'target': {'action': action, 'text': 'かいぎ' if action == 'emit' else ''}}
                for doc in range(30) for action in ('keep', 'emit', 'abstain')]

    def test_document_groups_are_disjoint_exhaustive_and_reproducible(self):
        rows = self.rows()
        cohort = build_cohort(rows, 'validation-hash', 42)
        self.assertEqual(cohort, build_cohort(rows, 'validation-hash', 42))
        subsets = [select_cohort_rows(rows, cohort, name, 'validation-hash') for name in PARTITIONS]
        docs = [{row['document_id'] for row in subset} for subset in subsets]
        self.assertTrue(all(docs))
        self.assertFalse(docs[0] & docs[1])
        self.assertEqual(sum(len(s) for s in subsets), len(rows))
        self.assertEqual(set.union(*docs), {row['document_id'] for row in rows})

    def test_changed_ids_source_or_duplicate_cases_are_rejected(self):
        rows = self.rows()
        cohort = build_cohort(rows, 'validation-hash', 42)
        altered = copy.deepcopy(cohort)
        altered['partitions']['calibration']['case_ids'].pop()
        for candidate, source in ((altered, 'validation-hash'), (cohort, 'another-source')):
            with self.assertRaises(ValueError):
                select_cohort_rows(rows, candidate, 'calibration', source)
        with self.assertRaises(ValueError):
            build_cohort(rows + [rows[0]], 'validation-hash', 42)


if __name__ == '__main__':
    unittest.main()
