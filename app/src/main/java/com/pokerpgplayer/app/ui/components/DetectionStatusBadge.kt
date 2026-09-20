package com.pokerpgplayer.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pokerpgplayer.app.R
import com.pokerpgplayer.app.data.model.DetectionStatus

/**
 * Colors echo PokeRPG Player OS's own status badge palette (success/info/
 * violet/danger) — a small but deliberate cross-product continuity choice,
 * not a coincidence.
 */
@Composable
fun DetectionStatusBadge(status: DetectionStatus, modifier: Modifier = Modifier) {
    val (label, color) = when (status) {
        DetectionStatus.HIGH_CONFIDENCE -> stringResource(R.string.detection_high_confidence) to Color(0xFF34D399)
        DetectionStatus.MEDIUM_CONFIDENCE -> stringResource(R.string.detection_medium_confidence) to Color(0xFF60A5FA)
        DetectionStatus.NEEDS_REVIEW -> stringResource(R.string.detection_needs_review) to Color(0xFFA78BFA)
        DetectionStatus.LOW_CONFIDENCE -> stringResource(R.string.detection_low_confidence) to Color(0xFFFB7185)
    }
    Text(
        text = label,
        color = color,
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}
