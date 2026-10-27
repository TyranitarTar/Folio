@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.folio.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** Stable page keys preserve the four independent grid/list scroll states. */
@Composable internal fun LibraryStatusPager(status:String,change:(String)->Unit,enabled:Boolean=true,content:@Composable (String)->Unit) {
    val pager=rememberPagerState(initialPage=libraryStatuses.indexOf(libraryStatus(status)).coerceAtLeast(0)){libraryStatuses.size}
    val latestChange by rememberUpdatedState(change)
    val scope=rememberCoroutineScope()
    LaunchedEffect(status){val page=libraryStatuses.indexOf(libraryStatus(status));if(page!=pager.currentPage && !pager.isScrollInProgress)pager.scrollToPage(page)}
    LaunchedEffect(pager){snapshotFlow{pager.settledPage}.drop(1).collect{latestChange(libraryStatuses[it])}}
    Column(Modifier.fillMaxSize()) {
        PrimaryScrollableTabRow(selectedTabIndex=pager.currentPage,edgePadding=androidx.compose.ui.unit.Dp(0f)) {
            libraryStatuses.forEachIndexed { i,label -> Tab(selected=pager.currentPage==i,enabled=enabled,modifier=Modifier.semantics{contentDescription="$label filter"},
                onClick={scope.launch{pager.animateScrollToPage(i)}},text={Text(label)}) }
        }
        HorizontalPager(state=pager,key={libraryStatuses[it]},userScrollEnabled=enabled,verticalAlignment=androidx.compose.ui.Alignment.Top,modifier=Modifier.weight(1f)) { page ->
            Box(Modifier.fillMaxSize(),contentAlignment=androidx.compose.ui.Alignment.TopStart){content(libraryStatuses[page])}
        }
    }
}
