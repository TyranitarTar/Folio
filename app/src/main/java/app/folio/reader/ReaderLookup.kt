package app.folio.reader

import android.net.Uri

internal object ReaderLookup {
    fun english(text:String,source:String="auto"):Uri = Uri.parse("https://translate.google.com/").buildUpon()
        .appendQueryParameter("sl",source.substringBefore('-').lowercase().takeIf{it.matches(Regex("[a-z]{2,3}"))} ?: "auto").appendQueryParameter("tl","en")
        .appendQueryParameter("text",text.trim().take(2000)).appendQueryParameter("op","translate").build()
}
