@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package app.folio.reader

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared Reading/Audiobooks cover layout, including badges and touch targets. */
@Composable
internal fun LibraryCoverTile(title: String,badge: String,progress: Float,prefs: HomePreferences,
    selected: Boolean=false,click: ()->Unit,longClick: ()->Unit,actionLabel: String,action: ()->Unit,
    cover: @Composable BoxScope.()->Unit) {
    Column(Modifier.clip(RoundedCornerShape(6.dp)).background(if(selected)MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
        .combinedClickable(onClick=click,onLongClick=longClick).padding(if(selected)3.dp else 0.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(.68f).clip(RoundedCornerShape(6.dp))) {
            cover()
            if(prefs.layout=="Compact")Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.85f))))
                .padding(top=28.dp,start=6.dp,end=6.dp,bottom=7.dp)) {
                Text(title,color=Color.White,maxLines=2,overflow=TextOverflow.Ellipsis,fontSize=13.sp,fontWeight=FontWeight.Medium)
            }
            if(prefs.showProgress)Surface(Modifier.align(Alignment.TopStart).padding(4.dp),shape=RoundedCornerShape(4.dp),color=MaterialTheme.colorScheme.primaryContainer) {
                Text(badge,Modifier.padding(horizontal=5.dp,vertical=2.dp),fontSize=10.sp)
            }
            if(selected)Icon(Icons.Outlined.CheckCircle,"Selected $title",Modifier.align(Alignment.TopEnd).padding(5.dp),tint=MaterialTheme.colorScheme.primary)
            else IconButton(onClick=action,modifier=Modifier.align(Alignment.TopEnd).size(48.dp)
                .background(MaterialTheme.colorScheme.primaryContainer,RoundedCornerShape(24.dp))) {
                Icon(Icons.Outlined.PlayArrow,actionLabel,tint=MaterialTheme.colorScheme.onPrimaryContainer)
            }
            if(prefs.showProgress && progress>0)LinearProgressIndicator(progress={progress.coerceIn(0f,1f)},modifier=Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp))
        }
        if(prefs.layout=="Comfortable")Text(title,Modifier.padding(horizontal=2.dp,vertical=6.dp),maxLines=2,overflow=TextOverflow.Ellipsis,fontSize=13.sp,fontWeight=FontWeight.Medium)
    }
}

@Composable
internal fun LibraryTitleRow(title: String,author: String,status: String,selected: Boolean=false,
    click: ()->Unit,longClick: ()->Unit,actionLabel: String,action: ()->Unit,cover: @Composable ()->Unit) {
    Row(Modifier.fillMaxWidth().background(if(selected)MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
        .combinedClickable(onClick=click,onLongClick=longClick).padding(horizontal=16.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.width(54.dp).height(78.dp).clip(RoundedCornerShape(4.dp))) { cover() }
        Column(Modifier.weight(1f).padding(horizontal=16.dp)) {
            Text(title,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleMedium)
            Text(author.ifBlank{"Unknown author"},maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(status.isNotBlank())Text(status,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
        }
        IconButton(onClick=action) { Icon(Icons.Outlined.PlayArrow,actionLabel) }
    }
}
