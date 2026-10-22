package com.zeroconvert.ime.assist

import com.zeroconvert.ime.KanaKanjiConverter

object PredictiveAssistEngine {

    // Common Japanese predictive phrases and completions (Prefix -> Continuation)
    private val PREDICTIVE_PHRASES = listOf(
        "いつもお世話に" to "なっております。",
        "よろしくお願い" to "いたします。",
        "お疲れ様" to "でした。",
        "ご確認のほど" to "よろしくお願いいたします。",
        "ご連絡いただき" to "ありがとうございます。",
        "現在原因を調査" to "中です。",
        "機能の実装が完了" to "いたしました。",
        "進捗状況をご報告" to "いたします。",
        "メンテナンス作業は無事終了" to "しました。",
        "最新バージョンを公開" to "しました。",
        "ご都合のよろしい" to "日時をご教示ください。",
        "日程の調整を" to "お願いできますでしょうか。",
        "お忙しいところ恐縮ですが、" to "ご確認をお願いいたします。",
        "添付の資料を" to "ご覧ください。",
        "出欠のご連絡を" to "お願いいたします。",
        "プルリクエストを" to "作成しました。",
        "デプロイが正常に" to "完了しました。",
        "エラーログを" to "確認してください。",
        "タイポの修正と" to "リファクタリングを行いました。",
        "エンドポイントの仕様を" to "変更しました。",
        "承知いた" to "しました。",
        "今からそちらへ" to "向かいます。",
        "ぜひ参加させて" to "いただきます。",
        "電車が遅延しているため、" to "少し遅れます。",
        "助かりました、" to "ありがとうございます！",
        "傘を持って" to "いったほうがいいですよ。",
        "近くのラーメン屋に" to "行きませんか？",
        "大会議室が空いて" to "います。",
        "精一杯頑張り" to "ますのでよろしくお願いいたします。",
        "一日中晴れの" to "予報です。",
        "ありがとうございます" to "ございました。",
        "何卒よろしく" to "お願い申し上げます。",
        "お手数をおかけ" to "いたしますが、よろしくお願いいたします。",
        "ご検討のほど" to "よろしくお願いいたします。"
    )

    /**
     * Finds a predictive completion based on the current surface and context.
     * Returns the full completed text to commit (e.g. "いつもお世話になっております。")
     */
    fun findPrediction(
        currentSurface: String,
        hiraganaReading: String,
        context: String,
        modelType: String = "rule_based"
    ): String? {
        val trimmedSurface = currentSurface.trim()
        val trimmedHira = hiraganaReading.trim()
        if (trimmedSurface.isEmpty() && trimmedHira.isEmpty()) return null

        // 1. Check exact phrase prefix match
        for ((prefix, continuation) in PREDICTIVE_PHRASES) {
            if (trimmedSurface.isNotEmpty() && prefix.startsWith(trimmedSurface) && trimmedSurface.length >= 2) {
                // If user typed a partial prefix, complete the full sentence
                return prefix + continuation
            }
            if (trimmedSurface == prefix || trimmedSurface.endsWith(prefix)) {
                return trimmedSurface + continuation
            }
            // Check reading match
            val prefixHira = KanaKanjiConverter.toHiragana(prefix)
            if (trimmedHira.isNotEmpty() && prefixHira.startsWith(trimmedHira) && trimmedHira.length >= 3) {
                return prefix + continuation
            }
        }

        // 2. Context-combined check (e.g. context: "取引先への返信で、" + partial: "いつもお世話に")
        val combined = context + trimmedSurface
        for ((prefix, continuation) in PREDICTIVE_PHRASES) {
            if (combined.endsWith(prefix)) {
                return trimmedSurface + continuation
            }
        }

        return null
    }
}
