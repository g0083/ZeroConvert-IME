import unittest
from qwerty_reading_search import ReadingSearch, choose


class SearchTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls): cls.search=ReadingSearch(8)

    def test_candidates_depend_only_on_observation_and_are_native(self):
        row={"observed_keys":"ksigi","input_state":"append"}
        candidates=self.search.generate(row)
        self.assertEqual(candidates,self.search.generate({**row,"canonical_keys":"secret","expected":"secret","expected_action":"keep"}))
        self.assertTrue(candidates[0]["unchanged"])
        self.assertEqual(candidates[0]["keys"],"ksigi")
        self.assertLessEqual(len(candidates),8)
        for c in candidates: self.assertEqual(c["reading"],self.search.native.decode(c["keys"]))

    def test_pending_backspace_and_unknown_raw(self):
        self.assertEqual(len(self.search.generate({"observed_keys":"kaigin"})),1)
        self.assertEqual(len(self.search.generate({"observed_keys":"kaigi","input_state":"backspace"})),1)
        self.assertEqual(self.search.generate({}),[])

    def test_original_competes_and_margin_is_strict(self):
        candidates=[{"unchanged":True,"dictionary":-10.,"lm_sum":-5.},
                    {"unchanged":False,"dictionary":-9.,"lm_sum":-3.}]
        self.assertIsNone(choose(candidates,1,3))
        self.assertEqual(choose(candidates,1,2),candidates[1])
        self.assertIsNone(choose(candidates[:1],1,0))


if __name__=="__main__": unittest.main()
