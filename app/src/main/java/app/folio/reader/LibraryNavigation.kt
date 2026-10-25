@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.folio.reader

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription

internal data class LibraryRequest(val section: String, val sequence: Long)
internal fun librarySection(value: String?): String = if(value=="Audiobooks") "Audiobooks" else "Books"

/** Collapse reader/player stacks without stopping their background services. */
internal fun returnToLibrary(activity: Activity, section: String) {
    activity.startActivity(Intent(activity,MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra("librarySection",librarySection(section)))
    activity.finish()
}

@Composable
internal fun PublicationHeader(title: String, library: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(title={Text(title,maxLines=1,overflow=TextOverflow.Ellipsis)},navigationIcon={
        TextButton(onClick=library,modifier=Modifier.semantics { contentDescription="Library" }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,null);Text("Library") }
    },actions=actions)
}
