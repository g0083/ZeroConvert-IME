"""Replay ordered edits with the real composing DLL, without contacting IME RPC."""
import ctypes

from native_qwerty import NativeQwerty


class TraceReplay:
    def __init__(self):
        self.native=NativeQwerty()
        self.native.dll.MoveCursor.restype=ctypes.c_void_p
        self.native.dll.MoveCursor.argtypes=[ctypes.c_int32,ctypes.POINTER(ctypes.c_int)]

    def replay(self,events):
        if not 1<=len(events)<=256: raise ValueError("Expected 1..256 ordered events")
        n=self.native
        n.take(n.dll.MoveCursor(256,ctypes.byref(n.cursor)))
        n.clear(); snapshots=[]
        previous=-1.
        for event in events:
            if "ms" in event:
                if event["ms"]<previous: raise ValueError("Non-monotonic event timestamps")
                previous=event["ms"]
            kind=event["kind"]
            if kind=="key":
                key=event["key"]
                if len(key)!=1 or not key.isascii() or not key.isprintable(): raise ValueError("Expected one printable ASCII key")
                pointer=n.dll.AppendText(key.encode(),ctypes.byref(n.cursor))
                state="append"
            elif kind=="backspace":
                pointer=n.dll.RemoveText(ctypes.byref(n.cursor)); state="backspace"
            elif kind=="move" and event["offset"] in (-1,1):
                pointer=n.dll.MoveCursor(event["offset"],ctypes.byref(n.cursor)); state="unknown"
            else: raise ValueError("Unsupported event")
            reading=n.take(pointer); raw=n.take(n.dll.GetRawInput())
            snapshots.append({"observed_keys":raw,"native_observed":reading,"input_state":state,
                "cursor_key_offset":len(raw) if n.cursor.value==len(reading) else None,
                "cursor_at_end":n.cursor.value==len(reading),"native_cursor":n.cursor.value})
        # Leave the shared DLL globals at the end so another isolated decoder
        # can clear them; the returned snapshots retain the original cursor.
        n.take(n.dll.MoveCursor(256,ctypes.byref(n.cursor)))
        return snapshots


def generate_candidates(generator, observation):
    # Unsupported spelling/position remains unchanged, never a guessed repair.
    keys=observation.get("observed_keys")
    if not keys or len(keys)>96 or not keys.isascii(): return []
    if observation.get("cursor_at_end") is False or any(c not in "abcdefghijklmnopqrstuvwxyz" for c in keys):
        reading=generator.native.decode(keys)
        return [{"keys":keys,"reading":reading,"dictionary":generator.dictionary_score(reading),"unchanged":True}]
    return generator.generate({k:observation.get(k) for k in ("observed_keys","input_state")})
