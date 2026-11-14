package com.zim.bubbletranslate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.google.mlkit.vision.text.Text

/** One block of translated text and where it sits on the screen. */
class Piece(
    val box: Rect,       // position on screen, in pixels
    val text: String,    // the translation
    val lines: Int,      // how many lines the original had (used to pick a font size)
    val bg: Int,         // colour of the page around the original text
    val fg: Int          // black or white, whichever reads better on bg
)

class PageResult(val pieces: List<Piece>, val from: String, val to: String)

/**
 * The whole job for one tap of the bubble:
 * picture of the screen -> read the text -> work out the language ->
 * translate each block -> hand back what to draw and where.
 *
 * Blocks, so run it on a background thread.
 */
object Pipeline {

    /** A problem we can explain to the reader in plain words. */
    class Stop(message: String) : Exception(message)

    fun run(ctx: Context, bmp: Bitmap, status: (String) -> Unit): PageResult {
        val target = Prefs.target(ctx)
        val chosen = Prefs.source(ctx)

        // 1. Read the text. Either with the model for the chosen language,
        //    or by trying the models to find the right one.
        val read = if (chosen != Prefs.AUTO) {
            val s = Reader.scriptFor(chosen)
            ReadResult(s, Reader.run(bmp, s))
        } else {
            Reader.auto(bmp, Prefs.lastScript(ctx)) { Lang.identify(it) != "und" }
        }

        // Ignore the strip at the very top of the screen (clock, battery, signal).
        val barId = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        val barHeight = if (barId != 0) ctx.resources.getDimensionPixelSize(barId) else 0
        val blocks = read.text.textBlocks.filter { b ->
            val box = b.boundingBox
            box != null && box.bottom > barHeight && Reader.belongs(read.script, b.text)
        }
        if (blocks.isEmpty()) {
            throw Stop("No text found on this screen. Some apps block screen reading.")
        }
        if (chosen == Prefs.AUTO) Prefs.setLastScript(ctx, read.script)

        // 2. Work out the language.
        val detected = if (chosen != Prefs.AUTO) chosen else when (read.script) {
            Script.JA -> "ja"
            Script.ZH -> "zh"
            Script.KO -> "ko"
            Script.DEVA, Script.LATIN -> {
                val guess = Lang.identify(blocks.joinToString("\n") { it.text })
                when {
                    guess != "und" -> guess
                    read.script == Script.DEVA -> "hi"
                    else -> throw Stop("Couldn't tell the language. Pick it in the app.")
                }
            }
        }
        val src = Lang.toTranslate(detected)
            ?: throw Stop("${Prefs.name(detected)} isn't a language this app can translate yet.")
        if (src == target) {
            throw Stop("This page is already in ${Prefs.name(target)}.")
        }

        // 3. Make sure both language packs are on the phone.
        if (!Lang.hasPack(src) || !Lang.hasPack(target)) {
            status("Getting a language pack, about 30 MB. First time only.")
        }
        try {
            Lang.prepare(src, target)
        } catch (e: Exception) {
            throw Stop("Couldn't get the language pack. Check your internet and try again.")
        }

        // 4. Translate block by block, so each translation can be drawn
        //    over the text it came from.
        val pieces = ArrayList<Piece>()
        for (b in blocks) {
            val box = b.boundingBox ?: continue
            val original = join(b, read.script)
            val translated = try {
                Lang.translate(src, target, original)
            } catch (e: Exception) {
                continue
            }
            // Names, numbers and brand words often come back unchanged.
            // Leave those alone instead of covering them with a box.
            if (translated.isBlank() || translated.trim().equals(original.trim(), ignoreCase = true)) continue
            val bg = backgroundAround(bmp, box)
            pieces.add(Piece(Rect(box), translated.trim(), b.lines.size.coerceAtLeast(1), bg, readableOn(bg)))
        }
        if (pieces.isEmpty()) throw Stop("Found text but couldn't translate it.")
        return PageResult(pieces, src, target)
    }

    /**
     * A block arrives as separate lines. Glue them back into one passage so
     * the translator sees whole sentences. Chinese, Japanese and Korean don't
     * put spaces between lines; the others do, and a word split with a hyphen
     * at the end of a line is rejoined.
     */
    private fun join(b: Text.TextBlock, script: Script): String {
        val lines = b.lines.map { it.text.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return b.text
        if (script == Script.JA || script == Script.ZH || script == Script.KO) {
            return lines.joinToString("")
        }
        val sb = StringBuilder()
        for (line in lines) {
            if (sb.isEmpty()) {
                sb.append(line)
            } else if (sb.endsWith("-") && sb.length >= 2 && sb[sb.length - 2].isLetter()) {
                sb.setLength(sb.length - 1)
                sb.append(line)
            } else {
                sb.append(' ').append(line)
            }
        }
        return sb.toString()
    }

    /**
     * The colour of the page just outside a text block, so the translation
     * box blends in (white in a speech bubble, cream on a novel page, dark in
     * dark mode). Takes the middle value of samples around the edge, which
     * ignores the odd stray pixel of artwork.
     */
    private fun backgroundAround(bmp: Bitmap, box: Rect): Int {
        val l = (box.left - 3).coerceIn(0, bmp.width - 1)
        val r = (box.right + 3).coerceIn(0, bmp.width - 1)
        val t = (box.top - 3).coerceIn(0, bmp.height - 1)
        val b = (box.bottom + 3).coerceIn(0, bmp.height - 1)
        val reds = ArrayList<Int>()
        val greens = ArrayList<Int>()
        val blues = ArrayList<Int>()
        fun take(x: Int, y: Int) {
            val p = bmp.getPixel(x, y)
            reds.add(Color.red(p)); greens.add(Color.green(p)); blues.add(Color.blue(p))
        }
        val steps = 12
        for (i in 0..steps) {
            val x = l + (r - l) * i / steps
            val y = t + (b - t) * i / steps
            take(x, t); take(x, b); take(l, y); take(r, y)
        }
        reds.sort(); greens.sort(); blues.sort()
        val mid = reds.size / 2
        return Color.rgb(reds[mid], greens[mid], blues[mid])
    }

    private fun readableOn(bg: Int): Int {
        val brightness = 0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg)
        return if (brightness > 140) Color.rgb(15, 20, 25) else Color.rgb(231, 233, 234)
    }
}
