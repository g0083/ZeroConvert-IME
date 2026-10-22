# -*- coding: utf-8 -*-
path = r'android-ime\app\src\main\cpp\llama.cpp\conversion\gpt2.py'
with open(path, 'r', encoding='utf-8') as f:
    c = f.read()

target = 'yield from super().modify_tensors(data_torch, new_name, bid)'
addition = '\n        if new_name == "token_embd.weight":\n            yield from super().modify_tensors(data_torch, "output.weight", bid)'

if target in c:
    c = c.replace(target, target + addition, 1)
    with open(path, 'w', encoding='utf-8') as f:
        f.write(c)
    print('Successfully patched gpt2.py')
else:
    print('Target not found in gpt2.py')
