package com.zim.bubbletranslate

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.TextRecognizerOptionsInterface
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.lang.Character.UnicodeScript
import java.util.concurrent.TimeUnit

/**
 * A writing system. The text reader (OCR) needs a separate model for each,
 * so we have to pick one before we can read anything.
 */
enum class Script { LATIN, JA, ZH, KO, DEVA }

class ReadResult(val script: Script, val text: Text)

/**
 * Reads text out of a picture of the screen.
 * Every function here blocks, so call it from a background thread only.
 */
object Reader {
    private val clients = HashMap<Script, TextRecognizer>()

    private fun client(s: Script): TextRecognizer = synchronized(clients) {
        clients.getOrPut(s) {
            val options: TextRecognizerOptionsInterface = when (s) {
                Script.LATIN -> TextRecognizerOptions.DEFAULT_OPTIONS
                Script.JA -> JapaneseTextRecognizerOptions.Builder().build()
                Script.ZH -> ChineseTextRecognizerOptions.Builder().build()
                Script.KO -> KoreanTextRecognizerOptions.Builder().build()
                Script.DEVA -> DevanagariTextRecognizerOptions.Builder().build()
            }
            TextRecognition.getClient(options)
        }
    }

    fun run(bmp: Bitmap, s: Script): Text =
        Tasks.await(client(s).process(InputImage.fromBitmap(bmp, 0)), 60, TimeUnit.SECONDS)

    /** Which reading model a chosen language needs. */
    fun scriptFor(lang: String): Script = when (lang) {
        "ja" -> Script.JA
        "zh" -> Script.ZH
        "ko" -> Script.KO
        "hi", "mr" -> Script.DEVA
        else -> Script.LATIN
    }

    /** How many characters of the given writing systems are in the text. */
    fun count(text: String, vararg scripts: UnicodeScript): Int {
        var n = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (UnicodeScript.of(cp) in scripts) n++
            i += Character.charCount(cp)
        }
        return n
    }

    fun kana(t: String) = count(t, UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA)
    fun han(t: String) = count(t, UnicodeScript.HAN)
    fun hangul(t: String) = count(t, UnicodeScript.HANGUL)
    fun deva(t: String) = count(t, UnicodeScript.DEVANAGARI)

    /** Does this text contain the writing system we think the page is in? */
    fun belongs(s: Script, t: String): Boolean = when (s) {
        Script.JA -> kana(t) + han(t) >= 1
        Script.ZH -> han(t) >= 1
        Script.KO -> hangul(t) >= 1
        Script.DEVA -> deva(t) >= 1
        Script.LATIN -> t.count { it.isLetter() } >= 2
    }

    /**
     * Works out the writing system by trying the models and looking at what
     * comes back. Japanese is checked first because only Japanese has kana
     * (the curvy syllable letters). Chinese characters with no kana means
     * Chinese. Then Korean, then Devanagari (Hindi), and Latin is the fallback.
     *
     * [last] is the script that worked on the previous tap. Trying it first
     * makes the common case (same book, next page) need one pass, not four.
     * [looksReal] says whether Latin text is a real language or just noise.
     */
    fun auto(bmp: Bitmap, last: Script?, looksReal: (String) -> Boolean): ReadResult {
        val cache = HashMap<Script, Text>()
        fun get(s: Script): Text = cache.getOrPut(s) { run(bmp, s) }

        if (last != null) {
            val t = get(last).text
            val ok = when (last) {
                Script.JA -> kana(t) >= 2
                Script.ZH -> han(t) >= 2 && kana(get(Script.JA).text) < 2
                Script.KO -> hangul(t) >= 2
                Script.DEVA -> deva(t) >= 2
                Script.LATIN -> t.count { it.isLetter() } >= 12 && looksReal(t)
            }
            if (ok) return ReadResult(last, get(last))
        }

        val ja = get(Script.JA).text
        if (kana(ja) >= 2) return ReadResult(Script.JA, get(Script.JA))
        if (han(ja) >= 2) {
            val zh = get(Script.ZH)
            return if (han(zh.text) >= 2) ReadResult(Script.ZH, zh) else ReadResult(Script.JA, get(Script.JA))
        }
        if (hangul(get(Script.KO).text) >= 2) return ReadResult(Script.KO, get(Script.KO))
        if (deva(get(Script.DEVA).text) >= 2) return ReadResult(Script.DEVA, get(Script.DEVA))
        return ReadResult(Script.LATIN, get(Script.LATIN))
    }
}
