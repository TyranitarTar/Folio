package app.folio.reader

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

internal fun openMisoHome(context:Context) {
    CafeGodotState(context).initialize()
    context.startActivity(Intent(context,CafeActivity::class.java))
}

@Composable internal fun CafeScreen(foremost:Boolean=true) {
    val context=LocalContext.current
    Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
        Text("Misos lille hjem",style=MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Button(onClick={openMisoHome(context)},enabled=foremost) { Text("Åbn Misos hjem") }
        TextButton(onClick={context.startActivity(Intent(context,CafeArchiveActivity::class.java))}) { Text("Tidligere café og gemte data") }
        TextButton(onClick={context.startActivity(Intent(context,CafeArchiveActivity::class.java).putExtra("classic",true))}) { Text("Åbn tidligere værelse") }
    }
}
