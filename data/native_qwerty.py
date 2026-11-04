"""Replay keys through the installed Swift ComposingText DLL in this process only.

No server/IME RPC, initialization, candidate generation, learning or registration.
Returned strings are released by the DLL that allocated them.
"""
import ctypes
import os
from pathlib import Path

from dataset_isolation import sha256


class NativeQwerty:
    def __init__(self, runtime=Path("ZeroConvertIME-windows")):
        if os.name != "nt":
            raise OSError("Native QWERTY replay requires Windows")
        runtime = runtime.resolve()
        self.library_path = runtime / "azookey-server.dll"
        self.handles = [os.add_dll_directory(str(p)) for p in (runtime, runtime / "llama_cpu")]
        self.dll = ctypes.CDLL(str(self.library_path))
        for name in ("AppendText", "RemoveText", "GetRawInput"):
            getattr(self.dll, name).restype = ctypes.c_void_p
        self.dll.AppendText.argtypes = [ctypes.c_char_p, ctypes.POINTER(ctypes.c_int)]
        self.dll.RemoveText.argtypes = [ctypes.POINTER(ctypes.c_int)]
        self.dll.GetRawInput.argtypes = []
        self.dll.FreeCString.argtypes = [ctypes.c_void_p]
        self.dll.FreeCString.restype = None
        self.cursor = ctypes.c_int()
        self.cache = {}

    @property
    def identity(self):
        return {"library": str(self.library_path), "sha256": sha256(self.library_path),
                "method": "one ASCII key per AppendText; isolated process globals; default roman2kana",
                "reset": "RemoveText until GetRawInput is empty; no converter initialization"}

    def take(self, pointer):
        try:
            return ctypes.string_at(pointer).decode("utf-8") if pointer else ""
        finally:
            if pointer:
                self.dll.FreeCString(pointer)

    def clear(self):
        for _ in range(256):
            if not self.take(self.dll.GetRawInput()):
                return
            self.take(self.dll.RemoveText(ctypes.byref(self.cursor)))
        raise ValueError("Native composition did not clear within the bound")

    def trace(self, keys):
        if not keys.isascii() or not 1 <= len(keys) <= 128:
            raise ValueError("Expected 1..128 ASCII keys")
        if keys in self.cache:
            return self.cache[keys]
        self.clear()
        values = []
        for key in keys:
            values.append(self.take(self.dll.AppendText(key.encode("ascii"), ctypes.byref(self.cursor))))
        if self.take(self.dll.GetRawInput()) != keys:
            raise ValueError("Native raw input changed during replay")
        self.cache[keys] = values
        return values

    def decode(self, keys):
        return self.trace(keys)[-1]
