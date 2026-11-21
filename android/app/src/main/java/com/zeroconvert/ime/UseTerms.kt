package com.zeroconvert.ime

/**
 * Terms of use shown on first launch. The text is bundled as
 * `assets/licenses/USE-TERMS.txt` so the exact wording a user agreed to travels
 * with the build. The fallback below is only used if the asset cannot be read.
 */
internal object UseTerms {
    const val ASSET = "licenses/USE-TERMS.txt"

    const val BUNDLED_FALLBACK = "別個の許諾がないZeroCon独自部分は、個人的・非商業的な利用に限り無償で提供します。\n" +
        "その独自部分の商用組み込み、再配布、モデル改変部分・学習データの再利用は許諾しません。\n" +
        "第三者ライセンスや個別の条件で既に許諾された権利は維持します。詳細は設定画面「オープンソースライセンス」を参照してください。"
}
