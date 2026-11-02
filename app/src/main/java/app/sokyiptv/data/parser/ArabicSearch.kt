/*
 * Arabic search orthography. Pure, Unicode-safe and independent of the database.
 * Search-only equivalence: original names, grouping keys and title display are unchanged.
 */
package app.sokyiptv.data.parser

import java.text.Normalizer
import java.util.Locale

/**
 * Fold writing variants, NOT different consonants. Search is symmetric:
 * `اشغال شقة` matches `أشغال شقه`, and the reverse also works.
 *
 * Explicit groups:
 *  - Alef/hamza: ا أ إ آ ٱ ء ٲ ٳ ٵ
 *  - Yeh: ي ى ئ ی ے
 *  - Heh/taa marbuta: ه ة ہ ھ
 *  - Waw with/without hamza: و ؤ
 *  - Arabic/Persian kaf: ك ک ڪ
 *
 * Crucially never fold ت into ة, or unrelated dotted letters (ف/ق, ب/پ,
 * ج/چ, ك/گ). Hamza-on-yeh/waw follows its visually written *seat*.
 */
object ArabicSearch {
    fun hasArabic(value: String): Boolean = value.any {
        it in '\u0600'..'\u06FF' || it in '\u0750'..'\u077F' ||
            it in '\u08A0'..'\u08FF' || it in '\uFB50'..'\uFDFF' ||
            it in '\uFE70'..'\uFEFF'
    }

    fun fold(value: String): String {
        if (!hasArabic(value)) return value.lowercase(Locale.ROOT).trim()
        // NFKC also expands Arabic presentation forms and lam-alef ligatures.
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
        val out = StringBuilder(normalized.length)
        var pendingSpace = false
        for (ch in normalized) {
            if (ch == '\u0640' || ch == '\u200C' || ch == '\u200D' ||
                ch == '\u200E' || ch == '\u200F' || ch == '\u061C') continue
            val type = Character.getType(ch)
            if ((ch in '\u0600'..'\u08FF') &&
                (type == Character.NON_SPACING_MARK.toInt() ||
                    type == Character.COMBINING_SPACING_MARK.toInt() ||
                    type == Character.ENCLOSING_MARK.toInt())) continue
            if (ch.isWhitespace() || ch == '\u00A0') {
                pendingSpace = out.isNotEmpty()
                continue
            }
            if (pendingSpace) {
                out.append(' ')
                pendingSpace = false
            }
            out.append(when (ch) {
                'ا','أ','إ','آ','ٱ','ء','ٲ','ٳ','ٵ' -> 'ا'
                'ي','ى','ئ','ی','ے' -> 'ي'
                'ه','ة','ہ','ھ' -> 'ه'
                'و','ؤ' -> 'و'
                'ك','ک','ڪ' -> 'ك'
                in '٠'..'٩' -> ('0'.code + (ch.code - '٠'.code)).toChar()
                in '۰'..'۹' -> ('0'.code + (ch.code - '۰'.code)).toChar()
                else -> ch.lowercaseChar()
            })
        }
        return out.toString()
    }

    fun matches(value: String, query: String): Boolean {
        val term = fold(query)
        return term.isNotEmpty() && fold(value).contains(term)
    }
}
