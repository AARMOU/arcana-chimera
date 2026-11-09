package com.arcanatable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** WotC Commander products: expandable set parents; title-only children with color identity pips. */
@Composable
internal fun ChimeraPreconTree(decks: List<DeckChoice>, activeId: String?, onPick: (DeckChoice) -> Unit) {
    val groups = remember(decks) {
        decks.groupBy { ChimeraPrecons.meta(it.id)?.setName ?: "Other Commander products" }
            .toList()
            .sortedWith(compareByDescending<Pair<String, List<DeckChoice>>> { item ->
                item.second.mapNotNull { ChimeraPrecons.meta(it.id)?.releaseDate }.maxOrNull().orEmpty()
            }.thenBy { it.first.lowercase() })
    }
    var openSets by remember(decks) { mutableStateOf(groups.take(1).map { it.first }.toSet()) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        groups.forEach { (setName, children) ->
            val expanded = setName in openSets
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    .background(Color(0xFF253645), RoundedCornerShape(10.dp))
                    .clickable { openSets = if (expanded) openSets - setName else openSets + setName }
                    .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (expanded) "⌄" else "›", color = Color(0xFF7DDBCB), fontSize = 19.sp,
                    modifier = Modifier.padding(end = 10.dp))
                Text(setName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(children.size.toString(), color = Color(0xFF9EB0BB), fontSize = 12.sp)
            }
            if (expanded) {
                children.sortedBy { it.name.lowercase() }.forEach { deck ->
                    val isSelected = deck.id == activeId
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, top = 2.dp)
                            .background(if (isSelected) Color(0xFF174B46) else Color(0xFF182633), RoundedCornerShape(10.dp))
                            .border(1.dp, if (isSelected) Color(0xFF80DDC9) else Color(0xFF344856), RoundedCornerShape(10.dp))
                            .clickable { onPick(deck) }
                            .heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(deck.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val colors = ChimeraPrecons.meta(deck.id)?.colors.orEmpty().ifEmpty { "C" }
                        colors.forEach { ManaPip(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManaPip(letter: Char) {
    val (fill, ink) = when (letter) {
        'W' -> Color(0xFFF3E6C8) to Color(0xFF433B2C)
        'U' -> Color(0xFF66ADE0) to Color(0xFF10344C)
        'B' -> Color(0xFF807888) to Color.White
        'R' -> Color(0xFFE87866) to Color(0xFF401F1B)
        'G' -> Color(0xFF75BE8B) to Color(0xFF123D25)
        else -> Color(0xFFB5BBC2) to Color(0xFF30363B)
    }
    Box(Modifier.padding(start = 3.dp).size(19.dp).background(fill, CircleShape)
        .border(1.dp, Color(0xFF34424B), CircleShape), contentAlignment = Alignment.Center) {
        Text(letter.toString(), color = ink, fontWeight = FontWeight.Bold, fontSize = 10.sp)
    }
}
