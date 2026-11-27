package com.zeroconvert.ime.theme

import org.json.JSONObject

object KeyboardThemeJson {

    fun encode(spec: KeyboardThemeSpec): String {
        val s = spec.sanitized()
        val root = JSONObject().apply {
            put("schemaVersion", s.schemaVersion)
            put("id", s.id)
            put("name", s.name)
            put("category", s.category.name)
            put("isBuiltIn", s.isBuiltIn)
            put("isDark", s.isDark)
            put("background", JSONObject().apply {
                put("kind", s.background.kind.name)
                put("solidColor", formatHexColor(s.background.solidColor))
                put("gradientStartColor", formatHexColor(s.background.gradientStartColor))
                put("gradientEndColor", formatHexColor(s.background.gradientEndColor))
                put("gradientAngleDeg", s.background.gradientAngleDeg.toDouble())
                if (s.background.imageFileName != null) {
                    put("imageFileName", s.background.imageFileName)
                }
                put("cropCenterX", s.background.cropCenterX.toDouble())
                put("cropCenterY", s.background.cropCenterY.toDouble())
                put("cropScale", s.background.cropScale.toDouble())
                put("blurRadiusDp", s.background.blurRadiusDp.toDouble())
                put("dimAlpha", s.background.dimAlpha.toDouble())
                put("dimColor", formatHexColor(s.background.dimColor))
                put("applyToBars", s.background.applyToBars)
            })
            put("keySurface", JSONObject().apply {
                put("preset", s.keySurface.preset.name)
                put("cornerRadiusDp", s.keySurface.cornerRadiusDp.toDouble())
                put("enterCornerRadiusDp", s.keySurface.enterCornerRadiusDp.toDouble())
                put("horizontalGapDp", s.keySurface.horizontalGapDp.toDouble())
                put("verticalGapDp", s.keySurface.verticalGapDp.toDouble())
                put("normalKeyFill", formatHexColor(s.keySurface.normalKeyFill))
                put("modifierKeyFill", formatHexColor(s.keySurface.modifierKeyFill))
                put("accentKeyFill", formatHexColor(s.keySurface.accentKeyFill))
                put("spaceKeyFill", formatHexColor(s.keySurface.spaceKeyFill))
                if (s.keySurface.pressedKeyFill != null) {
                    put("pressedKeyFill", formatHexColor(s.keySurface.pressedKeyFill))
                }
                put("keyFillAlpha", s.keySurface.keyFillAlpha.toDouble())
                put("modifierKeyAlpha", s.keySurface.modifierKeyAlpha.toDouble())
                put("borderWidthDp", s.keySurface.borderWidthDp.toDouble())
                put("borderColor", formatHexColor(s.keySurface.borderColor))
                put("borderAlpha", s.keySurface.borderAlpha.toDouble())
                put("shadowElevationDp", s.keySurface.shadowElevationDp.toDouble())
                put("shadowColor", formatHexColor(s.keySurface.shadowColor))
                put("bevelIntensity", s.keySurface.bevelIntensity.toDouble())
            })
            put("typography", JSONObject().apply {
                put("primaryTextColor", formatHexColor(s.typography.primaryTextColor))
                put("secondaryTextColor", formatHexColor(s.typography.secondaryTextColor))
                put("accentTextColor", formatHexColor(s.typography.accentTextColor))
                put("hintTextColor", formatHexColor(s.typography.hintTextColor))
                put("textShadowEnabled", s.typography.textShadowEnabled)
                put("labelScale", s.typography.labelScale.toDouble())
                put("showFlickDirectionHints", s.typography.showFlickDirectionHints)
            })
            put("bars", JSONObject().apply {
                put("backgroundColor", formatHexColor(s.bars.backgroundColor))
                put("backgroundAlpha", s.bars.backgroundAlpha.toDouble())
                put("dividerColor", formatHexColor(s.bars.dividerColor))
                put("candidateTextColor", formatHexColor(s.bars.candidateTextColor))
                put("aiCandidateTextColor", formatHexColor(s.bars.aiCandidateTextColor))
                put("mutedTextColor", formatHexColor(s.bars.mutedTextColor))
                put("chipBackgroundColor", formatHexColor(s.bars.chipBackgroundColor))
            })
            put("popup", JSONObject().apply {
                put("backgroundColor", formatHexColor(s.popup.backgroundColor))
                put("accentColor", formatHexColor(s.popup.accentColor))
                put("textColor", formatHexColor(s.popup.textColor))
                put("onAccentColor", formatHexColor(s.popup.onAccentColor))
                put("borderColor", formatHexColor(s.popup.borderColor))
                put("cornerRadiusDp", s.popup.cornerRadiusDp.toDouble())
            })
            if (s.fontFamilyId != null) put("fontFamilyId", s.fontFamilyId)
            if (s.tapEffectId != null) put("tapEffectId", s.tapEffectId)
            if (s.soundProfileId != null) put("soundProfileId", s.soundProfileId)
        }
        return root.toString(2)
    }

    fun decode(raw: String): KeyboardThemeSpec? {
        if (raw.isBlank()) return null
        return try {
            val root = JSONObject(raw)
            val id = root.optString("id", "").trim()
            if (id.isEmpty()) return null
            val name = root.optString("name", id).trim().ifEmpty { id }
            val category = parseEnum(root.optString("category", ""), ThemeCategory.CUSTOM)
            val isBuiltIn = root.optBoolean("isBuiltIn", false)
            val isDark = root.optBoolean("isDark", true)

            val bgObj = root.optJSONObject("background") ?: JSONObject()
            val bgDefaults = BackgroundStyle()
            val background = BackgroundStyle(
                kind = parseEnum(bgObj.optString("kind", ""), bgDefaults.kind),
                solidColor = parseColor(bgObj, "solidColor", bgDefaults.solidColor),
                gradientStartColor = parseColor(bgObj, "gradientStartColor", bgDefaults.gradientStartColor),
                gradientEndColor = parseColor(bgObj, "gradientEndColor", bgDefaults.gradientEndColor),
                gradientAngleDeg = bgObj.optDouble("gradientAngleDeg", bgDefaults.gradientAngleDeg.toDouble()).toFloat(),
                imageFileName = bgObj.optNullableString("imageFileName"),
                cropCenterX = bgObj.optDouble("cropCenterX", bgDefaults.cropCenterX.toDouble()).toFloat(),
                cropCenterY = bgObj.optDouble("cropCenterY", bgDefaults.cropCenterY.toDouble()).toFloat(),
                cropScale = bgObj.optDouble("cropScale", bgDefaults.cropScale.toDouble()).toFloat(),
                blurRadiusDp = bgObj.optDouble("blurRadiusDp", bgDefaults.blurRadiusDp.toDouble()).toFloat(),
                dimAlpha = bgObj.optDouble("dimAlpha", bgDefaults.dimAlpha.toDouble()).toFloat(),
                dimColor = parseColor(bgObj, "dimColor", bgDefaults.dimColor),
                applyToBars = bgObj.optBoolean("applyToBars", bgDefaults.applyToBars)
            )

            val keyObj = root.optJSONObject("keySurface") ?: JSONObject()
            val keyDefaults = KeySurfaceStyle()
            val keySurface = KeySurfaceStyle(
                preset = parseEnum(keyObj.optString("preset", ""), keyDefaults.preset),
                cornerRadiusDp = keyObj.optDouble("cornerRadiusDp", keyDefaults.cornerRadiusDp.toDouble()).toFloat(),
                enterCornerRadiusDp = keyObj.optDouble("enterCornerRadiusDp", keyDefaults.enterCornerRadiusDp.toDouble()).toFloat(),
                horizontalGapDp = keyObj.optDouble("horizontalGapDp", keyDefaults.horizontalGapDp.toDouble()).toFloat(),
                verticalGapDp = keyObj.optDouble("verticalGapDp", keyDefaults.verticalGapDp.toDouble()).toFloat(),
                normalKeyFill = parseColor(keyObj, "normalKeyFill", keyDefaults.normalKeyFill),
                modifierKeyFill = parseColor(keyObj, "modifierKeyFill", keyDefaults.modifierKeyFill),
                accentKeyFill = parseColor(keyObj, "accentKeyFill", keyDefaults.accentKeyFill),
                spaceKeyFill = parseColor(keyObj, "spaceKeyFill", keyDefaults.spaceKeyFill),
                pressedKeyFill = if (keyObj.has("pressedKeyFill") && !keyObj.isNull("pressedKeyFill")) {
                    parseColor(keyObj, "pressedKeyFill", keyDefaults.normalKeyFill)
                } else {
                    null
                },
                keyFillAlpha = keyObj.optDouble("keyFillAlpha", keyDefaults.keyFillAlpha.toDouble()).toFloat(),
                modifierKeyAlpha = keyObj.optDouble("modifierKeyAlpha", keyDefaults.modifierKeyAlpha.toDouble()).toFloat(),
                borderWidthDp = keyObj.optDouble("borderWidthDp", keyDefaults.borderWidthDp.toDouble()).toFloat(),
                borderColor = parseColor(keyObj, "borderColor", keyDefaults.borderColor),
                borderAlpha = keyObj.optDouble("borderAlpha", keyDefaults.borderAlpha.toDouble()).toFloat(),
                shadowElevationDp = keyObj.optDouble("shadowElevationDp", keyDefaults.shadowElevationDp.toDouble()).toFloat(),
                shadowColor = parseColor(keyObj, "shadowColor", keyDefaults.shadowColor),
                bevelIntensity = keyObj.optDouble("bevelIntensity", keyDefaults.bevelIntensity.toDouble()).toFloat()
            )

            val typoObj = root.optJSONObject("typography") ?: JSONObject()
            val typoDefaults = KeyTypographyStyle()
            val typography = KeyTypographyStyle(
                primaryTextColor = parseColor(typoObj, "primaryTextColor", typoDefaults.primaryTextColor),
                secondaryTextColor = parseColor(typoObj, "secondaryTextColor", typoDefaults.secondaryTextColor),
                accentTextColor = parseColor(typoObj, "accentTextColor", typoDefaults.accentTextColor),
                hintTextColor = parseColor(typoObj, "hintTextColor", typoDefaults.hintTextColor),
                textShadowEnabled = typoObj.optBoolean("textShadowEnabled", typoDefaults.textShadowEnabled),
                labelScale = typoObj.optDouble("labelScale", typoDefaults.labelScale.toDouble()).toFloat(),
                showFlickDirectionHints = typoObj.optBoolean("showFlickDirectionHints", typoDefaults.showFlickDirectionHints)
            )

            val barsObj = root.optJSONObject("bars") ?: JSONObject()
            val barsDefaults = BarStyle()
            val bars = BarStyle(
                backgroundColor = parseColor(barsObj, "backgroundColor", barsDefaults.backgroundColor),
                backgroundAlpha = barsObj.optDouble("backgroundAlpha", barsDefaults.backgroundAlpha.toDouble()).toFloat(),
                dividerColor = parseColor(barsObj, "dividerColor", barsDefaults.dividerColor),
                candidateTextColor = parseColor(barsObj, "candidateTextColor", barsDefaults.candidateTextColor),
                aiCandidateTextColor = parseColor(barsObj, "aiCandidateTextColor", barsDefaults.aiCandidateTextColor),
                mutedTextColor = parseColor(barsObj, "mutedTextColor", barsDefaults.mutedTextColor),
                chipBackgroundColor = parseColor(barsObj, "chipBackgroundColor", barsDefaults.chipBackgroundColor)
            )

            val popupObj = root.optJSONObject("popup") ?: JSONObject()
            val popupDefaults = PopupStyle()
            val popup = PopupStyle(
                backgroundColor = parseColor(popupObj, "backgroundColor", popupDefaults.backgroundColor),
                accentColor = parseColor(popupObj, "accentColor", popupDefaults.accentColor),
                textColor = parseColor(popupObj, "textColor", popupDefaults.textColor),
                onAccentColor = parseColor(popupObj, "onAccentColor", popupDefaults.onAccentColor),
                borderColor = parseColor(popupObj, "borderColor", popupDefaults.borderColor),
                cornerRadiusDp = popupObj.optDouble("cornerRadiusDp", popupDefaults.cornerRadiusDp.toDouble()).toFloat()
            )

            KeyboardThemeSpec(
                schemaVersion = root.optInt("schemaVersion", KeyboardThemeSpec.CURRENT_SCHEMA_VERSION),
                id = id,
                name = name,
                category = category,
                isBuiltIn = isBuiltIn,
                isDark = isDark,
                background = background,
                keySurface = keySurface,
                typography = typography,
                bars = bars,
                popup = popup,
                fontFamilyId = root.optNullableString("fontFamilyId"),
                tapEffectId = root.optNullableString("tapEffectId"),
                soundProfileId = root.optNullableString("soundProfileId")
            ).sanitized()
        } catch (_: Exception) {
            null
        }
    }

    fun formatHexColor(argb: Long): String {
        val masked = argb and 0xFFFFFFFFL
        return "#" + masked.toString(16).uppercase().padStart(8, '0')
    }

    internal fun parseColorString(raw: String, fallback: Long): Long {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("#")) return trimmed.toLongOrNull() ?: fallback
        val hex = trimmed.substring(1)
        return when (hex.length) {
            8 -> hex.toLongOrNull(16) ?: fallback
            6 -> hex.toLongOrNull(16)?.let { 0xFF000000L or it } ?: fallback
            else -> fallback
        }
    }

    private fun parseColor(obj: JSONObject, key: String, fallback: Long): Long {
        if (!obj.has(key) || obj.isNull(key)) return fallback
        val value = obj.opt(key) ?: return fallback
        return when (value) {
            is Number -> value.toLong() and 0xFFFFFFFFL
            is String -> parseColorString(value, fallback)
            else -> fallback
        }
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key, "").trim().takeIf { it.isNotEmpty() }
    }

    private inline fun <reified T : Enum<T>> parseEnum(raw: String, fallback: T): T {
        val normalized = raw.trim()
        if (normalized.isEmpty()) return fallback
        return enumValues<T>().firstOrNull { it.name.equals(normalized, ignoreCase = true) } ?: fallback
    }
}
