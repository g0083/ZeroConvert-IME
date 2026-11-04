import unittest
from evaluate_v4_repair_comparison import ReadingVerifier
from native_qwerty import NativeQwerty
from qwerty_corrector_policy import DICTIONARY
from qwerty_local_eval import interpret,metrics


class EvalTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls): cls.native=NativeQwerty(); cls.verifier=ReadingVerifier(DICTIONARY)

    def test_full_conversion_of_unchanged_reading_is_not_a_typo_proposal(self):
        value=interpret("会議",{"native_observed":"かいぎ"},"full",self.native,self.verifier)
        self.assertEqual(value["action"],"K")
        self.assertIsNone(value["surface"])

    def test_both_modes_are_scored_by_intended_reading(self):
        row={"expected_action":"repair","target_reading":"かいぎ","observed_keys":"ksigi","native_observed":"kしぎ"}
        a=interpret("会議",row,"full",self.native,self.verifier)
        b=interpret("E:ksi:kai",row,"local",self.native,self.verifier)
        self.assertEqual(metrics([row],[a],self.verifier)["reading_correct"],1)
        self.assertEqual(metrics([row],[b],self.verifier)["reading_correct"],1)
        self.assertFalse(interpret("E:zzz:zza",row,"local",self.native,self.verifier)["valid"])

    def test_invalid_and_abstention_still_count_as_misses(self):
        row={"expected_action":"repair","target_reading":"かいぎ","observed_keys":"ksigi","native_observed":"kしぎ"}
        values=metrics([row,row],[interpret("garbage",row,"local",self.native,self.verifier),interpret("K",row,"local",self.native,self.verifier)],self.verifier)
        self.assertEqual(values["repair_missing"],2)
        self.assertEqual(values["invalid"],1)
        self.assertEqual(values["reading_f0_5"],0.)


if __name__=="__main__": unittest.main()
