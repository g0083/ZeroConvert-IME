import unittest
from copy import deepcopy
from evaluate_qwerty_reading_surface import combine


class SurfaceTests(unittest.TestCase):
    def setUp(self):
        self.rows=[{"id":x,"expected_action":"repair","expected":"会議","acceptable":[]} for x in ("a","b")]
        self.base={"report":{"cases":[{"id":"a","correction":"既存","auto_apply":True,"crashed":False},
            {"id":"b","correction":None,"auto_apply":False,"crashed":False}]}}
        cs=[{"unchanged":True,"dictionary":-20.,"reading":"かいき"},
            {"unchanged":False,"dictionary":-10.,"reading":"かいぎ","keys":"kaigi"}]
        self.scores=[{"id":x,"candidates":cs} for x in ("a","b")]

    def test_existing_result_is_preserved_and_added_result_is_manual(self):
        result=combine(self.base,self.rows,self.scores,0,5,{("b","かいぎ"):"会議"})
        self.assertEqual(result["report"]["cases"][0],self.base["report"]["cases"][0])
        self.assertEqual(result["report"]["cases"][1]["correction"],"会議")
        self.assertFalse(result["report"]["cases"][1]["auto_apply"])
        self.assertIsNone(self.base["report"]["cases"][1]["correction"])

    def test_labels_do_not_change_decisions_and_unverified_surface_is_absent(self):
        wrong=deepcopy(self.rows)
        for row in wrong: row.update(expected="secret",expected_action="keep")
        a=combine(self.base,self.rows,self.scores,0,5,{("b","かいぎ"):"会議"})
        b=combine(self.base,wrong,self.scores,0,5,{("b","かいぎ"):"会議"})
        self.assertEqual(a["report"]["cases"],b["report"]["cases"])
        self.assertIsNone(combine(self.base,self.rows,self.scores,0,5,{})["report"]["cases"][1]["correction"])

    def test_mismatched_case_order_is_rejected(self):
        with self.assertRaises(ValueError): combine(self.base,self.rows,self.scores[::-1],0,5,{})


if __name__=="__main__": unittest.main()
