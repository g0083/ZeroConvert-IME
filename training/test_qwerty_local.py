import unittest
from native_qwerty import NativeQwerty
from qwerty_local_contract import make_target, parse_prediction, one_edit
from qwerty_local_eval import input_prompt


class LocalTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls): cls.native=NativeQwerty()

    def test_training_patch_roundtrip_for_all_edit_types(self):
        for before,after in (("ksigi","kaigi"),("kigi","kaigi"),("kaaigi","kaigi"),("kiagi","kaigi")):
            row={"expected_action":"repair","observed_keys":before,"canonical_keys":after}
            target=make_target(row)
            result=parse_prediction(target,row,self.native)
            self.assertTrue(result["valid"],(before,after,target,result))
            self.assertEqual(result["keys"],after)

    def test_ambiguous_or_large_edits_are_rejected(self):
        self.assertFalse(parse_prediction("E:ka:ki",{"observed_keys":"kakaka"},self.native)["valid"])
        self.assertFalse(parse_prediction("E:aaa:aba",{"observed_keys":"aaaa"},self.native)["valid"])
        self.assertFalse(parse_prediction("E:kai:uchuu",{"observed_keys":"kaigi"},self.native)["valid"])
        self.assertFalse(parse_prediction("E:kaigi:kaigi",{"observed_keys":"kaigi"},self.native)["valid"])
        self.assertFalse(parse_prediction("E:ka:ki",{},self.native)["valid"])
        self.assertFalse(one_edit("kaigi","uchuu"))

    def test_labels_are_not_runtime_inputs(self):
        row={"observed_keys":"ksigi","native_observed":"kしぎ","input_state":"append","cursor_key_offset":5,"context":"次の"}
        poison={**row,"canonical_keys":"secret","local_completion":"secret","expected_action":"keep","expected":"secret"}
        self.assertEqual(input_prompt(row),input_prompt(poison))
        self.assertEqual(parse_prediction("E:ksi:kai",row,self.native),parse_prediction("E:ksi:kai",poison,self.native))
        self.assertEqual(make_target({"expected_action":"keep"}),"K")
        self.assertEqual(make_target({"expected_action":"wait"}),"W")


if __name__=="__main__": unittest.main()
