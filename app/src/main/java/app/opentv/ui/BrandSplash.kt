package app.opentv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.opentv.R

/** TV boot wordmark. Works without network and doesn't delay background loading. */
@Composable
fun BrandSplash() {
    Column(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(Color(0xFF10132C), Color(0xFF222251), Color(0xFF0A1024)))
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(painterResource(R.drawable.ic_opentv_logo), contentDescription = "Koky and Sosy logo",
            modifier = Modifier.size(150.dp))
        Spacer(Modifier.height(16.dp))
        Text("KOKY & SOSY", color = Color.White, fontWeight = FontWeight.Black,
            fontSize = 38.sp, letterSpacing = 2.sp)
        Text("PLAYER", color = Color(0xFF74E5F3), fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp, letterSpacing = 7.sp)
    }
}
