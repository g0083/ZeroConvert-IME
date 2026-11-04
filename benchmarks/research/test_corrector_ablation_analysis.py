import unittest

from analyze_corrector_ablation import compare, summarize


class AnalysisTests(unittest.TestCase):
    def row(self, **updates):
        return {'id':1,'input':'てそと','expected':'テスト','acceptable':['試験'],
                'expected_reading':'てすと','baseline':'手外','correction':None,
                'is_clean':False,'typo_type':'adjacent','calls':[],**updates}

    def test_exact_reading_is_not_counted_as_successful_final_conversion(self):
        row = self.row(calls=[{'stage':'corrector','prompt':'\uee00テソト\uee01','response':{'content':'てすと'}},
                              {'stage':'reading_conversion','response':{'content':'手すと'}}])
        s = summarize({'cases':[row],'summary':{},'reading_stage':True})
        self.assertEqual(s['raw_reading_exact'],1)
        self.assertEqual(s['exact_reading_without_final_correct'],1)
        self.assertEqual(s['conversion_calls'],1)

    def test_clean_inputs_do_not_inflate_reading_correction_accuracy(self):
        row = self.row(is_clean=True, calls=[{'stage':'corrector','response':{'content':'てすと'}}])
        s = summarize({'cases':[row],'summary':{},'reading_stage':True})
        self.assertEqual(s.get('raw_reading_exact',0),0)

    def test_romaji_reconstruction_is_not_counted_as_unchanged_original_input(self):
        row = self.row(input='おkせり', expected_reading='おかえり',
                       calls=[{'stage':'corrector','prompt':'\uee00オカエリ\uee01','response':{'content':'おかえり'}}])
        s = summarize({'cases':[row],'summary':{},'reading_stage':True})
        self.assertEqual(s.get('raw_reading_unchanged_typos',0),0)
        self.assertEqual(s['raw_reading_exact'],1)

    def test_paired_comparison_uses_existing_acceptable_and_rejects_mismatch(self):
        x,y = self.row(),self.row(correction='試験')
        self.assertEqual(compare({'cases':[x]},{'cases':[y]})['gains'],1)
        with self.assertRaises(ValueError):
            compare({'cases':[x]},{'cases':[]})
        with self.assertRaises(ValueError):
            compare({'cases':[x]},{'cases':[self.row(baseline='違う候補')]})


if __name__ == '__main__':
    unittest.main()
