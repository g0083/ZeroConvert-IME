"""Build the research token protocol from the production native source."""
import runpy
from pathlib import Path
import sys

if __name__ == "__main__":
    sys.argv.extend(["--name", "keyboard_prior_score_masked.exe"])
    runpy.run_path(str(Path(__file__).resolve().parents[2]/"windows/native/keyboard-prior/build.py"), run_name="__main__")
