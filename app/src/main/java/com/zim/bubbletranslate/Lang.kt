package com.zim.bubbletranslate

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.TimeUnit

/**
 * Language detection and translation. Both run on the phone.
 * Every function here blocks, so call it from a background thread only.
 */
object Lang {
    private val identifier: LanguageIdentifier by lazy { LanguageIdentification.getClient() }
    private val translators = HashMap<String, Translator>()

    /** Every language the translator knows, as short codes like "en" or "ja". */
    fun all(): List<String> = TranslateLanguage.getAllLanguages()

    /** Returns a language code, or "und" (undetermined) when it can't tell. */
    fun identify(text: String): String = try {
        Tasks.await(identifier.identifyLanguage(text), 20, TimeUnit.SECONDS) ?: "und"
    } catch (e: Exception) {
        "und"
    }

    /**
     * The detector and the translator don't always use the same code for a
     * language. This turns a detector code into a translator code, or null
     * when the translator doesn't know that language.
     */
    fun toTranslate(tag: String): String? {
        val base = when (val t = tag.substringBefore('-').lowercase()) {
            "fil" -> "tl"
            "iw" -> "he"
            "in" -> "id"
            "nb", "nn" -> "no"
            else -> t
        }
        return TranslateLanguage.fromLanguageTag(base)
    }

    private fun translator(src: String, dst: String): Translator = synchronized(translators) {
        translators.getOrPut("$src>$dst") {
            Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(dst).build()
            )
        }
    }

    /** Is the language pack already on the phone? English is always built in. */
    fun hasPack(lang: String): Boolean {
        if (lang == TranslateLanguage.ENGLISH) return true
        return try {
            val model = TranslateRemoteModel.Builder(lang).build()
            Tasks.await(RemoteModelManager.getInstance().isModelDownloaded(model), 10, TimeUnit.SECONDS) == true
        } catch (e: Exception) {
            false
        }
    }

    /** Downloads the two language packs if they are missing. Needs internet the first time. */
    fun prepare(src: String, dst: String) {
        Tasks.await(
            translator(src, dst).downloadModelIfNeeded(DownloadConditions.Builder().build()),
            300, TimeUnit.SECONDS
        )
    }

    fun translate(src: String, dst: String, text: String): String =
        Tasks.await(translator(src, dst).translate(text), 30, TimeUnit.SECONDS)
}
