# -*- coding: utf-8 -*-
path = r'android-ime\app\src\main\cpp\llama.cpp\conversion\base.py'
with open(path, 'r', encoding='utf-8') as f:
    c = f.read()
target = 'res = "k2-horizon"'
addition = '\n        if chkhsh == "acded0362f57dcca1fbdada8d4c87dd782c798cb4c510a70f12a9de6aea57857":\n            res = "default"'
if target in c:
    c = c.replace(target, target + addition, 1)
    with open(path, 'w', encoding='utf-8') as f:
        f.write(c)
    print('Successfully patched base.py')
else:
    print('Target not found')
