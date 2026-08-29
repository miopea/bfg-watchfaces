package com.bfg.watchfaces.mobile

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri

/**
 * The free promise, and the rest of what BFG makes.
 *
 * ## Why FREE is the headline
 *
 * Every other watch face app on Play is a paywall with a preview, so the first
 * question anybody has is what this one costs. The localhost app answers it
 * before it is asked, in a gold-on-dark card at the top of the screen, and this
 * one now does the same — the earlier version had the words and none of the
 * emphasis, which is most of what the card was for.
 *
 * ## The product list is not filler
 *
 * It is the only place this app tells you who made it and what else they make,
 * and it is the reason there is no ad anywhere else in the product. Same five
 * entries and same copy as the localhost app: two lists that drift would tell
 * two stories about the same company.
 *
 * Every claim about privacy here is checked against the code rather than
 * aspirational — the app makes no network calls and links no analytics or
 * advertising library.
 */
private data class Product(
    val name: String,
    val availability: String,
    val href: String,
    val badge: String?,
    val tagline: String
)

private val PRODUCTS = listOf(
    Product(
        "BudgetBug", "Web · iOS · Android", "https://budgetbug.live", null,
        "A personal budget tracker that syncs with your bank, tracks recurring bills, " +
            "and shows you exactly where your money is going."
    ),
    Product(
        "Sculpt Studio", "Web · iOS · Android",
        "https://bfgsolutions.net/products/sculptstudio", null,
        "Goal-driven strength training — a guided session runner, honest progression, " +
            "and an AI coach that can read your data and program your week."
    ),
    Product(
        "VoiceBridge", "Web", "https://bfgsolutions.net/products/voicebridge", null,
        "Real-time speech translation. Speak once, reach everyone in the room — " +
            "in their own language."
    ),
    Product(
        "Swarm", "Linux · macOS · WSL", "https://bfgsolutions.net/products/swarm",
        "Free & open source",
        "A web-based control center for AI coding agents. Manage one agent or ten " +
            "from a single browser tab."
    ),
    Product(
        "Shotcraft", "CLI · GitHub", "https://bfgsolutions.net/products/shotcraft",
        "Free & open source",
        "Capture your live app and ship App Store-ready screenshots, README hero " +
            "images, and social cards in one command."
    )
)

@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    fun open(url: String) = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        FreeHero()

        Spacer(Modifier.height(28.dp))
        SectionHeading("Also from BFG Solutions")
        for (product in PRODUCTS) {
            ProductRow(product) { open(product.href) }
            HorizontalDivider()
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "bfgsolutions.net ›",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable { open("https://bfgsolutions.net") }
                .padding(vertical = 8.dp)
        )
        Spacer(Modifier.height(28.dp))
    }
}

/**
 * The gold-on-dark card.
 *
 * The gradient is the localhost app's: a warm wash from the top-left corner
 * over the surface colour, so the card reads as lit rather than as a coloured
 * rectangle. `tertiary` is the workbench's gold, kept for exactly this.
 */
@Composable
private fun FreeHero() {
    val gold = MaterialTheme.colorScheme.tertiary
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        gold.copy(alpha = 0.18f),
                        MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            )
            .padding(20.dp)
    ) {
        Column {
            Surface(
                color = gold,
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    "FREE",
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 3.sp,
                    color = Color(0xFF2A2213)
                )
            }
            Spacer(Modifier.height(13.dp))
            Text(
                "Every part of this app is free.",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "No ads. No account. No subscription, no trial, nothing held back for a " +
                    "paid tier. Design as many watch faces as you like and keep them.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Row {
                Text("Built and given away by ", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "BFG Solutions",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = gold
                )
                Text(".", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "Nothing leaves this phone except a face you send to your own watch. " +
                    "No analytics, no advertising, no personal data collected.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ProductRow(product: Product, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 15.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    product.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                product.badge?.let {
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            it,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                product.availability,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Text(
                product.tagline,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            "›",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.outline
        )
    }
}
