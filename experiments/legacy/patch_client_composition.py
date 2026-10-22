# -*- coding: utf-8 -*-
with open('scratch/batao_src/crates/client/src/engine/composition.rs', 'r', encoding='utf-8') as f:
    content = f.read()

target = """        Some(CandidateSelection {
            index: index as i32,
            text: candidates.texts.get(index).cloned().unwrap_or_default(),
            sub_text: candidates.sub_texts.get(index).cloned().unwrap_or_default(),"""

replacement = """        let raw_text = candidates.texts.get(index).cloned().unwrap_or_default();
        let clean_text = if let Some(stripped) = raw_text.strip_suffix(" [💡もしかして]") {
            stripped.to_string()
        } else if let Some(stripped) = raw_text.strip_suffix(" [🔮先読み]") {
            stripped.to_string()
        } else {
            raw_text
        };

        Some(CandidateSelection {
            index: index as i32,
            text: clean_text,
            sub_text: candidates.sub_texts.get(index).cloned().unwrap_or_default(),"""

assert target in content, "target not found"

content = content.replace(target, replacement)

with open('scratch/batao_src/crates/client/src/engine/composition.rs', 'w', encoding='utf-8', newline='\r\n') as f:
    f.write(content)

print("Updated client composition.rs successfully!")
