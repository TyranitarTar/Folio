@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.folio.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

/** Existing data and compatibility room remain in Folio's main process. */
class CafeArchiveActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FolioTheme(false) {
            Scaffold(topBar={ TopAppBar(title={ Text(if(intent.getBooleanExtra("classic",false))"Tidligere værelse" else "Gemte cafédata") },
                navigationIcon={ TextButton(onClick={finish()}) { Text("Tilbage") } }) }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    if(intent.getBooleanExtra("classic",false)) CafeClassicScreen() else CafeLegacyScreen()
                }
            }
        } }
    }
}
