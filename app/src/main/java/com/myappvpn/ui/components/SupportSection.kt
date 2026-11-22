package com.myappvpn.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myappvpn.R
import com.myappvpn.data.repository.Offer
import com.myappvpn.ui.theme.*
import java.text.NumberFormat
import java.util.Locale

/**
 * Project links + support block at the bottom of Settings. Deliberately quiet: no popups, no
 * banners, nothing outside this screen — visible for those who scroll here, invisible otherwise.
 */
object SupportInfo {
    /** Donated so far, ₽ — update by hand in each release. */
    const val DONATED_RUB = 700
    const val GOAL_RUB = 1_000_000

    const val TELEGRAM_URL = "https://t.me/myappvpn_dprog"
    const val GITHUB_URL = "https://github.com/indelingDanil/MyAppVPN"
    const val CLOUDTIPS_URL = "https://pay.cloudtips.ru/p/8c90d7a5"

    val CRYPTO = listOf(
        "USDT (TRC20)" to "TB3Qn2guzVdj93kUi43KG8gQY6mruGZbNG",
        "BTC" to "16Bs15Mpu2HAYyVjgiLdwQC1yzLpbf2qyv",
        "ETH (ERC20)" to "0x3704ecc83ea8b6a852d63dc82c0badcd761d8e6f",
    )
}

@Composable
fun ProjectLinksCard() {
    val context = LocalContext.current
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            LinkRow(
                icon = Icons.Default.Send,
                title = stringResource(R.string.support_tg_title),
                subtitle = stringResource(R.string.support_tg_subtitle),
                onClick = { openUrl(context, SupportInfo.TELEGRAM_URL) },
            )
            HorizontalDivider(color = AetherDivider, thickness = 1.dp)
            LinkRow(
                icon = Icons.Default.Code,
                title = stringResource(R.string.support_github_title),
                subtitle = stringResource(R.string.support_github_subtitle),
                onClick = { openUrl(context, SupportInfo.GITHUB_URL) },
            )
        }
    }
}

@Composable
fun SupportCard() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var cryptoExpanded by rememberSaveable { mutableStateOf(false) }

    val fmt = remember { NumberFormat.getIntegerInstance(Locale("ru")) }
    val fraction = SupportInfo.DONATED_RUB.toFloat() / SupportInfo.GOAL_RUB
    val percent = remember(fraction) { String.format(Locale("ru"), "%.2f", fraction * 100) }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.support_title),
                color = AetherTextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.support_text),
                color = AetherTextSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )

            // Goal tracker — tongue-in-cheek, updated by hand each release.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.support_dream, fmt.format(SupportInfo.GOAL_RUB)),
                    color = AetherTextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                LinearProgressIndicator(
                    // Real share is ~0.07% — invisible; show a sliver so the bar reads as "started".
                    progress = { fraction.coerceIn(0.012f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = AetherCyan,
                    trackColor = AetherSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        R.string.support_progress,
                        fmt.format(SupportInfo.DONATED_RUB),
                        percent,
                    ),
                    color = AetherTextTertiary,
                    fontSize = 11.sp,
                    style = MaterialTheme.typography.bodySmall.numeric(),
                )
            }

            FilledTonalButton(
                onClick = { openUrl(context, SupportInfo.CLOUDTIPS_URL) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = AetherCyan.copy(alpha = 0.15f),
                    contentColor = AetherCyan,
                ),
            ) {
                Icon(Icons.Default.Favorite, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.support_cloudtips))
            }

            // Crypto — collapsed by default to keep the block compact.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(role = Role.Button) { cryptoExpanded = !cryptoExpanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.support_crypto),
                    color = AetherTextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (cryptoExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = AetherTextTertiary,
                )
            }
            AnimatedVisibility(visible = cryptoExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SupportInfo.CRYPTO.forEach { (network, address) ->
                        val copiedMsg = stringResource(R.string.support_copied, network)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(
                                    role = Role.Button,
                                    onClickLabel = stringResource(R.string.support_copy_action),
                                ) {
                                    clipboard.setText(AnnotatedString(address))
                                    Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(network, color = AetherTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    text = address,
                                    color = AetherTextSecondary,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(Icons.Default.ContentCopy, contentDescription = null, tint = AetherTextTertiary, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            Text(
                text = stringResource(R.string.support_thanks),
                color = AetherTextTertiary,
                fontSize = 11.sp,
            )
        }
    }
}

/**
 * One quiet row leading to the "Verified paid subscriptions" screen (Home under the connect
 * button, and the Support tab) — the offers themselves live only on that screen.
 */
@Composable
fun OffersLinkCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.Verified, contentDescription = null, tint = AetherCyan, modifier = Modifier.size(20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.offers_link_title), color = AetherTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    stringResource(R.string.offers_link_subtitle),
                    color = AetherTextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = AetherTextTertiary)
        }
    }
}

/**
 * Quiet partner card: tap opens the partner site in the browser, the paper-plane opens the
 * Telegram bot/channel. Plain links — no ad SDK, no tracking.
 *
 * With a valid [Offer.trialUrl] and [onTrial] set, a compact "Try for free" button adds the trial
 * link as a custom subscription (the callback gets the localized "<title> (trial)" name);
 * [trialAdded] = that URL is already among the user's subscriptions → disabled "Added ✓".
 */
@Composable
fun OfferCard(
    offer: Offer,
    trialAdded: Boolean = false,
    onTrial: ((name: String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val lang = LocalConfiguration.current.locales[0].language
    val text = if (offer.textRes != 0) stringResource(offer.textRes) else offer.textFor(lang)
    val showTrial = offer.trialUrl != null && onTrial != null
    val trialNote = if (showTrial) offer.trialNoteFor(lang) else null
    val trialName = stringResource(R.string.offer_trial_sub_name, offer.title)

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(role = Role.Button) { openUrl(context, offer.url) }
                .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AetherCyan.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(offerIcon(offer.icon), contentDescription = null, tint = AetherCyan, modifier = Modifier.size(20.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(offer.title, color = AetherTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                trialNote?.let {
                    Text(
                        stringResource(R.string.offer_trial_note, it),
                        color = AetherCyan,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text,
                    color = AetherTextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.padding(top = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(Icons.Default.Verified, contentDescription = null, tint = AetherCyan.copy(alpha = 0.6f), modifier = Modifier.size(11.dp))
                    Text(
                        if (offer.subscription) stringResource(R.string.offer_tested_subscription) else stringResource(R.string.offer_tested),
                        color = AetherTextTertiary,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                    )
                }
                if (showTrial) {
                    FilledTonalButton(
                        onClick = { onTrial?.invoke(trialName) },
                        enabled = !trialAdded,
                        modifier = Modifier.padding(top = 6.dp).heightIn(min = 32.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = AetherCyan.copy(alpha = 0.15f),
                            contentColor = AetherCyan,
                            disabledContainerColor = AetherCyan.copy(alpha = 0.06f),
                            disabledContentColor = AetherTextSecondary,
                        ),
                    ) {
                        Text(
                            stringResource(if (trialAdded) R.string.offer_trial_added else R.string.offer_trial_button),
                            fontSize = 12.sp,
                        )
                    }
                }
            }
            // Two explicit actions: website (globe) and Telegram (real logo, not a generic "send").
            IconButton(onClick = { openUrl(context, offer.url) }) {
                Icon(Icons.Default.Language, contentDescription = stringResource(R.string.offer_open_site), tint = AetherCyan, modifier = Modifier.size(22.dp))
            }
            offer.tg?.let { tg ->
                IconButton(onClick = { openTelegram(context, tg, offer.tgStart) }) {
                    Image(
                        painter = painterResource(R.drawable.ic_telegram),
                        contentDescription = stringResource(R.string.partner_open_tg),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

private fun offerIcon(name: String): ImageVector = when (name) {
    "game" -> Icons.Default.SportsEsports
    "vpn" -> Icons.Default.VpnKey
    "ai" -> Icons.Default.AutoAwesome
    "card" -> Icons.Default.CreditCard
    "gift" -> Icons.Default.CardGiftcard
    else -> Icons.Default.ShoppingBag
}

/**
 * Opens the bot/channel straight in the Telegram app (t.me links are often grabbed by the
 * browser); without Telegram installed falls back to the t.me web link.
 */
private fun openTelegram(context: Context, tg: String, start: String?) {
    try {
        val appUrl = "tg://resolve?domain=$tg" + (start?.let { "&start=$it" } ?: "")
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(appUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        openUrl(context, "https://t.me/$tg" + (start?.let { "?start=$it" } ?: ""))
    }
}

@Composable
fun DisclaimerText() {
    Text(
        text = stringResource(R.string.support_disclaimer),
        color = AetherTextTertiary,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun LinkRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = AetherCyan, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = AetherTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = AetherTextSecondary, fontSize = 11.sp, lineHeight = 15.sp)
        }
        Icon(Icons.Default.OpenInNew, contentDescription = null, tint = AetherTextTertiary, modifier = Modifier.size(18.dp))
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, url, Toast.LENGTH_LONG).show()
    }
}
