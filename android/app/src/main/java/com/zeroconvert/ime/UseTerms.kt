package com.zeroconvert.ime

/**
 * Terms of use shown on first launch. The text is bundled as
 * `assets/licenses/USE-TERMS.txt` so the exact wording a user agreed to travels
 * with the build. The fallback below is only used if the asset cannot be read.
 */
internal object UseTerms {
    const val ASSET = "licenses/USE-TERMS.txt"

    const val BUNDLED_FALLBACK = "ZeroCon Keyboard は個人的・非商業的な利用に限り無償で提供されます。\n" +
        "商用目的での組み込み、再配布、ZeroCon Model Nano の重み・学習データの再利用は許諾しません。\n" +
        "第三者ライセンスの詳細は設定画面「オープンソースライセンス」と同梱の assets/licenses/ を参照してください。"
}
