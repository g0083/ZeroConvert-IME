# -*- coding: utf-8 -*-
with open('scratch/batao_src/crates/shared/src/lib.rs', 'r', encoding='utf-8') as f:
    content = f.read()

target1 = """fn default_punctuation_commit_target_enabled() -> bool {
    true
}"""

replacement1 = """fn default_punctuation_commit_target_enabled() -> bool {
    true
}

fn default_predictive_model() -> String {
    "230m_q4km".to_string()
}"""

target2 = """    #[serde(default)]
    pub experimental_typo_correction: bool,
    #[serde(default = "default_auto_commit_live_conversion")]
    pub auto_commit_live_conversion: bool,"""

replacement2 = """    #[serde(default)]
    pub experimental_typo_correction: bool,
    #[serde(default)]
    pub experimental_ai_assist: bool,
    #[serde(default = "default_predictive_model")]
    pub experimental_predictive_model: String,
    #[serde(default = "default_auto_commit_live_conversion")]
    pub auto_commit_live_conversion: bool,"""

target3 = """            experimental_typo_correction: false,
            auto_commit_live_conversion: true,"""

replacement3 = """            experimental_typo_correction: false,
            experimental_ai_assist: false,
            experimental_predictive_model: default_predictive_model(),
            auto_commit_live_conversion: true,"""

if target1 not in content:
    print("Warning: target1 not found")
if target2 not in content:
    print("Warning: target2 not found")
if target3 not in content:
    print("Warning: target3 not found")

content = content.replace(target1, replacement1).replace(target2, replacement2).replace(target3, replacement3)

with open('scratch/batao_src/crates/shared/src/lib.rs', 'w', encoding='utf-8', newline='\r\n') as f:
    f.write(content)

print("Updated shared/src/lib.rs successfully!")
