package app.opentv.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.opentv.data.remote.TmdbCastPortrait
import coil.compose.AsyncImage

/** The same focused, clickable person portraits across movies and series. */
@Composable
internal fun CastPortraitShelf(people: List<TmdbCastPortrait>, onPerson: (String) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp)) {
        items(people, key = { it.name }) { actor ->
            var focused by remember { mutableStateOf(false) }
            Column(
                Modifier.width(104.dp)
                    .onFocusChanged { focused = it.isFocused }
                    .border(if (focused) 2.dp else 0.dp, Color(0xFF79ECF7), RoundedCornerShape(7.dp))
                    .tvRemoteActions({ onPerson(actor.name) }, { onPerson(actor.name) })
                    .padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.width(94.dp).height(128.dp)
                    .background(Color(0xFF212B46), RoundedCornerShape(5.dp)),
                    contentAlignment = Alignment.Center) {
                    if (actor.imageUrl != null) {
                        AsyncImage(actor.imageUrl, contentDescription = actor.name,
                            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else Text(actor.name.take(1), color = Color.White, fontSize = 36.sp)
                }
                Spacer(Modifier.height(4.dp))
                Text(actor.name, color = Color.White, fontSize = 12.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
