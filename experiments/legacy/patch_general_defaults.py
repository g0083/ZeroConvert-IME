path = r"d:\angra\ZeroConvert-IME\scratch\batao_src\frontend\src\pages\general.tsx"
with open(path, "r", encoding="utf-8") as f:
    text = f.read()

t = "experimental_ai_assist: false,"
r = "experimental_ai_assist: true,"
text = text.replace(t, r)

with open(path, "w", encoding="utf-8") as f:
    f.write(text)
print("Updated general.tsx defaults")
