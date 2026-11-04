"""Native event ordering and observation-only candidate guards."""
import unittest
from qwerty_reading_search import ReadingSearch
from qwerty_trace import TraceReplay, generate_candidates


class TraceTests(unittest.TestCase):
    def test_native_backspace_and_retype(self):
        replay=TraceReplay()
        events=[{"kind":"key","key":c} for c in "kaigi"]
        clean=replay.replay(events)[-1]
        edited=replay.replay(events+[{"kind":"backspace"}])[-1]
        self.assertEqual(clean["native_observed"],"かいぎ")
        self.assertEqual(edited["native_observed"],"かい")
        self.assertEqual(edited["observed_keys"],"kai")
        restored=replay.replay(events+[{"kind":"backspace"},{"kind":"key","key":"g"},{"kind":"key","key":"i"}])[-1]
        self.assertEqual(restored["native_observed"],clean["native_observed"])

    def test_cursor_insertion_and_decoder_reset(self):
        replay=TraceReplay()
        events=[{"kind":"key","key":c} for c in "kaigi"]
        middle=replay.replay(events+[{"kind":"move","offset":-1}])[-1]
        self.assertFalse(middle["cursor_at_end"])
        self.assertIsNone(middle["cursor_key_offset"])
        self.assertEqual(replay.native.decode("sushi"),"すし")
        search=ReadingSearch(4)
        self.assertEqual(len(generate_candidates(search,middle)),1)
        deleted=replay.replay(events+[{"kind":"backspace"}])[-1]
        self.assertEqual(len(generate_candidates(search,deleted)),1)

    def test_timestamp_and_unsupported_events(self):
        replay=TraceReplay()
        with self.assertRaises(ValueError): replay.replay([{"kind":"key","key":"a","ms":2},{"kind":"key","key":"i","ms":1}])
        with self.assertRaises(ValueError): replay.replay([{"kind":"move","offset":100}])


if __name__=="__main__": unittest.main()
