package com.fayyaztech.dialer_core.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fayyaztech.dialer_core.R

@Composable
fun DialathonBrandHeader(
    modifier: Modifier = Modifier,
    title: String = "Dialathon",
    tagline: String = "Every Call Matters",
    showTagline: Boolean = true,
    logoSizeDp: Int = 30,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    taglineColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = modifier.defaultMinSize(minHeight = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_dialathon_logo),
            contentDescription = "Dialathon logo",
            modifier = Modifier.size(logoSizeDp.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                text = title,
                color = titleColor,
                style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
            )
            if (showTagline) {
                Text(
                    text = tagline,
                    color = taglineColor,
                    style = TextStyle(fontSize = 11.sp),
                )
            }
        }
    }
}