path = r"d:\angra\ZeroConvert-IME\scratch\batao_src\crates\shared\src\lib.rs"
with open(path, "r", encoding="utf-8") as f:
    text = f.read()

t = """    #[serde(default)]
    pub experimental_ai_assist: bool,"""

r = """    #[serde(default = "default_ai_assist_enabled")]
    pub experimental_ai_assist: bool,"""

text = text.replace(t, r)

t2 = """            experimental_ai_assist: false,"""
r2 = """            experimental_ai_assist: true,"""
text = text.replace(t2, r2)

helper = """fn default_ai_assist_enabled() -> bool {
    true
}

"""

if "fn default_ai_assist_enabled()" not in text:
    target_pos = "fn default_predictive_model()"
    text = text.replace(target_pos, helper + target_pos)

with open(path, "w", encoding="utf-8") as f:
    f.write(text)
print("Updated shared lib.rs defaults")
