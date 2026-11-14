# Bubble Translate

An Android app that puts a small blue bubble on top of whatever you are
reading. Tap the bubble and the page on screen is translated in place.

## How it works

One tap runs five steps, all on the phone:

    tap bubble
        -> take a picture of the screen        (ScreenGrabber.kt)
        -> read the text out of the picture    (Reader.kt)
        -> work out which language it is       (Lang.kt)
        -> translate each block of text        (Lang.kt)
        -> draw each translation over the      (Views.kt)
           spot the original text was in

`Pipeline.kt` runs those steps in order. `BubbleService.kt` owns the bubble
and keeps the app alive while you are in another app. `MainActivity.kt` is the
settings screen.

Reading text out of a picture is called OCR (optical character recognition).
The OCR, the language detector and the translator all come from Google's
ML Kit, a free library that runs on the phone itself. Nothing you read is
sent to a server. The only download is a language pack of about 30 MB the
first time you use a language.

## What it can and cannot do

- Reads: languages written in the Latin alphabet (English, French, Spanish,
  German and so on), Chinese, Japanese, Korean and Hindi.
- Cannot read: Arabic, Russian and other Cyrillic alphabets, Thai, Greek.
  It can translate INTO those, but not read them off the screen.
- Translates between more than 50 languages.
- Apps that block screenshots (some banking and paid comic apps) show up as
  a black picture, so there is nothing to read.
- Japanese text written top to bottom, as in manga, is the weakest case.
- Translation quality is "understand the gist", not "published translation".

## Building it

GitHub builds the app. Every push to `main` runs `.github/workflows/build.yml`
and puts `BubbleTranslate.apk` on the repo's Releases page.

To build on a computer instead: open the folder in Android Studio and press Run.

`debug.keystore` is a throwaway signing key so that each new build installs
over the old one. It is not a secret and is not for publishing to the Play Store.
