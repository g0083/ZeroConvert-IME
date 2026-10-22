import win32file
import time

pipe_name = r'\\.\pipe\LOCAL\azookey_window_s2'

# Let's check available pipes
import glob
print("Looking for named pipes...")
# We can use powershell to list pipes
