# -*- coding: utf-8 -*-
with open('scratch/batao_src/frontend/src/pages/general.tsx', 'r', encoding='utf-8') as f:
    content = f.read()

target1 = """    experimental_typo_correction: boolean;
    auto_commit_live_conversion: boolean;"""

replacement1 = """    experimental_typo_correction: boolean;
    experimental_ai_assist: boolean;
    experimental_predictive_model: string;
    auto_commit_live_conversion: boolean;"""

target2 = """    experimental_typo_correction: false,
    auto_commit_live_conversion: true,"""

replacement2 = """    experimental_typo_correction: false,
    experimental_ai_assist: false,
    experimental_predictive_model: "230m_q4km",
    auto_commit_live_conversion: true,"""

target3 = """                <SettingsSection title="実験的機能">
                    <SettingsRow label="誤入力の補正" description="PCキーボードのタイポや入力間違いを補正した候補を表示します。" htmlFor="typo-correction">
                        <Switch id="typo-correction" checked={generalValue.experimental_typo_correction} onCheckedChange={(value) => void updateGeneralConfig("experimental_typo_correction", value)} />
                    </SettingsRow>
                </SettingsSection>"""

replacement3 = """                <SettingsSection title="実験的機能">
                    <SettingsRow label="辞書ベース誤入力補正" description="PCキーボードのタイポや入力間違いを補正した候補を表示します。" htmlFor="typo-correction">
                        <Switch id="typo-correction" checked={generalValue.experimental_typo_correction} onCheckedChange={(value) => void updateGeneralConfig("experimental_typo_correction", value)} />
                    </SettingsRow>
                    <SettingsRow label="AIアシスト（💡もしかして / 🔮先読み）" description="基本変換は入力通りを100%維持しつつ、AIによる誤字補正案や続きの先読み予測をアシスト候補として提示します。" htmlFor="ai-assist">
                        <Switch id="ai-assist" checked={generalValue.experimental_ai_assist} onCheckedChange={(value) => void updateGeneralConfig("experimental_ai_assist", value)} />
                    </SettingsRow>
                    {generalValue.experimental_ai_assist && (
                        <SettingsRow label="先読み推論モデル" description="LM Studio経由で動作する先読みモデルを選択します。">
                            <Select value={generalValue.experimental_predictive_model || "230m_q4km"} onValueChange={(value) => void updateGeneralConfig("experimental_predictive_model", value)}>
                                <SelectTrigger className="w-64"><SelectValue /></SelectTrigger>
                                <SelectContent>
                                    <SelectItem value="230m_q4km">LFM 2.5 230M (Q4_K_M) - 超軽量・低遅延</SelectItem>
                                    <SelectItem value="350m_q4km">LFM 2.5 350M (Q4_K_M) - 高精度文脈予測</SelectItem>
                                </SelectContent>
                            </Select>
                        </SettingsRow>
                    )}
                </SettingsSection>"""

assert target1 in content, "target1 not found"
assert target2 in content, "target2 not found"
assert target3 in content, "target3 not found"

content = content.replace(target1, replacement1).replace(target2, replacement2).replace(target3, replacement3)

with open('scratch/batao_src/frontend/src/pages/general.tsx', 'w', encoding='utf-8', newline='\n') as f:
    f.write(content)

print("Updated general.tsx successfully!")
