package app.folio.reader

import android.net.Uri
import android.provider.DocumentsContract

internal fun validLinkedAudio(tree: Uri,document: Uri): Boolean = runCatching {
    tree.scheme=="content" && document.scheme=="content" && tree.authority==document.authority &&
        DocumentsContract.isTreeUri(tree) && DocumentsContract.isTreeUri(document) &&
        DocumentsContract.getTreeDocumentId(tree)==DocumentsContract.getTreeDocumentId(document) &&
        DocumentsContract.getDocumentId(document).isNotBlank()
}.getOrDefault(false)
