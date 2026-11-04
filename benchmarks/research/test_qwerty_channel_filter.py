import unittest
from qwerty_channel_filter import ChannelFilter, edits


class ChannelTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.channel = ChannelFilter()

    def test_supported_physical_edits_recover_original(self):
        for observed, original in (("ksigi", "kaigi"), ("kigi", "kaigi"), ("kaaigi", "kaigi"), ("kiagi", "kaigi")):
            self.assertIn(original, edits(observed))
        self.assertNotIn("kaigi", edits("kaigi"))

    def test_native_witness_and_unreachable_surface(self):
        row = {"observed_keys": "ksigi"}
        found = self.channel.witness(row, "会議")
        self.assertIsNotNone(found)
        self.assertEqual(self.channel.native.decode(found["keys"]), found["reading"])
        self.assertIsNone(self.channel.witness(row, "宇宙船"))

    def test_backspace_veto_and_unknown_raw_fallback(self):
        result = {"report": {"cases": [{"id":"a", "correction":"会議", "auto_apply":True, "crashed":False}]}}
        row = {"id":"a", "expected_action":"keep", "expected":"かい", "observed_keys":"kai", "input_state":"backspace"}
        filtered = self.channel.apply(result, [row])
        self.assertIsNone(filtered["report"]["cases"][0]["correction"])
        self.assertFalse(filtered["report"]["cases"][0]["auto_apply"])
        self.assertEqual(result["report"]["cases"][0]["correction"], "会議")
        row.update(observed_keys=None, input_state="unknown")
        self.assertEqual(self.channel.apply(result, [row])["report"]["cases"][0]["correction"], "会議")


if __name__ == "__main__":
    unittest.main()
