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
 *  - Dotted variants of the same underlying character:
 *      ب پ ٻ ڀ / ج چ ڇ ڄ / ز ژ / ن ڽ / ل ڶ ڷ
 *  - Feh-family dotted variants, including user-requested ف ↔ ڨ:
 *      ف ڨ ڤ ڦ ڥ ڢ ڣ
 *
 * Do not fold unrelated standard Arabic letters (ف and ق remain different;
 * ت and ث remain different; ج and خ remain different). Importantly,
 * although Unicode calls ڨ a dotted QAF, the user explicitly wants it
 * paired with ف, not with an ordinary ق. These groups are not phonetic
 * substitutions and cannot grow transitively to other letter families.
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
            // NFKC decomposes ٵ into (ا + U+0674 HIGH HAMZA), so also
            // remove that modifier to keep ALL alef forms symmetric.
            if (ch == '\u0674') continue
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
                'ب','پ','ٻ','ڀ' -> 'ب'
                'ج','چ','ڇ','ڄ' -> 'ج'
                'ز','ژ' -> 'ز'
                'ن','ڼ','ڽ' -> 'ن'
                'ل','ڶ','ڷ','ڸ' -> 'ل'
                // ڨ is paired with FEH by user request, not with QAF.
                'ف','ڨ','ڤ','ڦ','ڥ','ڢ','ڣ','ڡ' -> 'ف'
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
