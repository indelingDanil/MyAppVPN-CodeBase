package com.myappvpn.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
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
    const val DONATED_RUB = 1_550
    /** RUB per USD for the approximate $ amounts shown in non-Russian UIs (USDT ≈ 81.35 ₽). */
    const val RUB_PER_USD = 81.35
    const val GOAL_RUB = 1_000_000

    const val TELEGRAM_URL = "https://t.me/myappvpn_dprog"
    const val GITHUB_URL = "https://github.com/indelingDanil/MyAppVPN"
    const val CLOUDTIPS_URL = "https://pay.cloudtips.ru/p/8c90d7a5"

    // All addresses: one Walt (Telegram) wallet; checksums verified (TON CRC16, TRON Base58Check,
    // BTC Bech32, EVM EIP-55). No Memo needed on any of them.
    private val USDT_TON = CryptoAddress("USDT (TON)", "USDT", "TON", "UQBi78BaJNXuEXHrP-OhuFAIf29yvpIo1s2agHiTQPB-xWbE", R.string.support_ton_hint)
    private val USDT_TRC20 = CryptoAddress("USDT (TRC20)", "USDT", "TRON (TRC20)", "TYaGhM39zKXS3g177R6VvBhNLkbsFgYAHj")
    private val USDT_BEP20 = CryptoAddress("USDT (BEP20)", "USDT", "BNB Smart Chain (BEP20)", "0xB65bb82aB8f85db12b3DEfebE2a4a0b210f097d6")
    private val GRAM_TON = CryptoAddress("Gram (TON)", "GRAM", "TON", "UQBi78BaJNXuEXHrP-OhuFAIf29yvpIo1s2agHiTQPB-xWbE")
    private val BTC = CryptoAddress("BTC", "BTC", "Bitcoin", "bc1qjrtd68le7dcd2h2psqcgmp4nvcp9p092m7ew8z")

    /**
     * Address order by UI language — what people in each region actually use (TRC20 dominates
     * Iran / CIS / China; TON via Telegram in RU/CIS; BEP20 common with Binance users in CN/TR).
     */
    fun cryptoFor(lang: String): List<CryptoAddress> = when (lang) {
        "ru" -> listOf(USDT_TON, USDT_TRC20, GRAM_TON, BTC)
        "kk", "uz", "fa" -> listOf(USDT_TRC20, USDT_TON, GRAM_TON, BTC)
        "zh", "tr" -> listOf(USDT_TRC20, USDT_BEP20, BTC, USDT_TON)
        else -> listOf(USDT_TRC20, USDT_TON, USDT_BEP20, BTC, GRAM_TON)
    }
}

/**
 * One donation address. [label] is what the row shows, [coin]/[network] feed the
 * "send only <coin> on <network>" warning in the QR dialog, [hint] is an optional extra line.
 */
data class CryptoAddress(
    val label: String,
    val coin: String,
    val network: String,
    val address: String,
    @StringRes val hint: Int = 0,
)

/** Wraps a Latin token (address, "USDT (TON)") in a Unicode LTR isolate so RTL text can't mangle it. */
private fun ltr(s: String) = "⁦$s⁩"

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
    // Russian UI: CloudTips (₽) first, crypto collapsed. Any other UI language: crypto only,
    // expanded (Russian cards are useless there).
    val cryptoFirst = LocalConfiguration.current.locales[0].language != "ru"
    var cryptoExpanded by rememberSaveable(cryptoFirst) { mutableStateOf(cryptoFirst) }
    var qrFor by remember { mutableStateOf<CryptoAddress?>(null) }

    val copyAddress: (CryptoAddress, String) -> Unit = { item, msg ->
        clipboard.setText(AnnotatedString(item.address))
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    val fmt = remember { NumberFormat.getIntegerInstance(Locale("ru")) }
    // Russian UI: roubles. Other languages: an approximate dollar amount (wrapped in LRI…PDI so
    // "≈ $19" keeps its order inside right-to-left Persian text).
    val inRub = LocalConfiguration.current.locales[0].language == "ru"
    val usdFmt = remember { NumberFormat.getIntegerInstance(Locale.US) }
    fun money(rub: Int, roundTo: Int): String = if (inRub) "${fmt.format(rub)} ₽" else
        "⁦≈ $" + usdFmt.format(Math.round(rub / SupportInfo.RUB_PER_USD / roundTo) * roundTo) + "⁩"
    val fraction = SupportInfo.DONATED_RUB.toFloat() / SupportInfo.GOAL_RUB
    val percent = remember(fraction, inRub) { String.format(if (inRub) Locale("ru") else Locale.US, "%.2f", fraction * 100) }

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
                    text = stringResource(R.string.support_dream, money(SupportInfo.GOAL_RUB, 100)),
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
                        money(SupportInfo.DONATED_RUB, 1),
                        percent,
                    ),
                    color = AetherTextTertiary,
                    fontSize = 11.sp,
                    style = MaterialTheme.typography.bodySmall.numeric(),
                )
            }

            val cloudTipsButton: @Composable () -> Unit = {
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
            }

            // Crypto — collapsed by default for ru/en to keep the block compact.
            val cryptoSection: @Composable () -> Unit = {
                Column {
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
                            SupportInfo.cryptoFor(LocalConfiguration.current.locales[0].language).forEach { item ->
                                CryptoRow(
                                    item = item,
                                    onCopy = copyAddress,
                                    onShowQr = { qrFor = item },
                                )
                            }
                        }
                    }
                }
            }

            // CloudTips takes Russian cards only → shown in the Russian UI only; everyone else gets
            // crypto, expanded.
            if (inRub) {
                cloudTipsButton()
                cryptoSection()
            } else {
                cryptoSection()
            }

            Text(
                text = stringResource(R.string.support_thanks),
                color = AetherTextTertiary,
                fontSize = 11.sp,
            )
        }
    }

    qrFor?.let { item ->
        CryptoQrDialog(item = item, onCopy = copyAddress, onDismiss = { qrFor = null })
    }
}

/** Address row: tap = copy, QR button = dialog with the QR code. */
@Composable
private fun CryptoRow(
    item: CryptoAddress,
    onCopy: (CryptoAddress, String) -> Unit,
    onShowQr: () -> Unit,
) {
    val copiedMsg = stringResource(R.string.support_copied, ltr(item.label))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(R.string.support_copy_action),
            ) { onCopy(item, copiedMsg) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            LtrText(item.label, color = AetherTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            LtrText(
                text = item.address,
                color = AetherTextSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.hint != 0) {
                Text(
                    text = stringResource(item.hint),
                    color = AetherTextTertiary,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                )
            }
        }
        IconButton(onClick = onShowQr, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Default.QrCode2,
                contentDescription = stringResource(R.string.support_qr_action),
                tint = AetherCyan,
                modifier = Modifier.size(20.dp),
            )
        }
        Icon(Icons.Default.ContentCopy, contentDescription = null, tint = AetherTextTertiary, modifier = Modifier.size(18.dp))
    }
}

/** Latin-only text (address, ticker) laid out LTR even inside an RTL (fa) screen. */
@Composable
private fun LtrText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Text(
            text = text,
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            fontFamily = fontFamily,
            maxLines = maxLines,
            overflow = overflow,
            textAlign = textAlign,
            style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
            modifier = modifier,
        )
    }
}

@Composable
private fun CryptoQrDialog(
    item: CryptoAddress,
    onCopy: (CryptoAddress, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val qr = remember(item.address) { qrBitmap(item.address) }
    val copiedMsg = stringResource(R.string.support_copied, ltr(item.label))
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AetherSurface,
        title = { LtrText(item.label, color = AetherTextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Image(
                    bitmap = qr,
                    contentDescription = item.address,
                    filterQuality = FilterQuality.None,
                    modifier = Modifier
                        .size(220.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White),
                )
                Text(
                    text = stringResource(R.string.support_qr_network, ltr(item.network)),
                    color = AetherTextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                SelectionContainer {
                    LtrText(
                        text = item.address,
                        color = AetherTextSecondary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (item.hint != 0) {
                    Text(stringResource(item.hint), color = AetherTextTertiary, fontSize = 11.sp)
                }
                Text(
                    text = stringResource(R.string.support_qr_warning, ltr(item.coin), ltr(item.network)),
                    color = AetherConnecting,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    textAlign = TextAlign.Center,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCopy(item, copiedMsg) }) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, tint = AetherCyan, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.support_qr_copy), color = AetherCyan)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.support_qr_close), color = AetherTextSecondary)
            }
        },
    )
}

/**
 * Plain-string QR (no URI scheme) rendered locally with ZXing: dark modules on white, 4-module
 * quiet zone, each module an integer number of pixels so it stays crisp when scaled.
 */
private fun qrBitmap(content: String, targetPx: Int = 600): ImageBitmap {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 4,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    // width/height 0 → matrix at native size (1 px per module, quiet zone included).
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
    val modules = matrix.width
    val scale = (targetPx / modules).coerceAtLeast(1)
    val size = modules * scale
    val dark = 0xFF000000.toInt()
    val light = 0xFFFFFFFF.toInt()
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        val my = y / scale
        val row = y * size
        for (x in 0 until size) {
            pixels[row + x] = if (matrix[x / scale, my]) dark else light
        }
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/**
 * One quiet row leading to the "Verified paid subscriptions" screen (Home under the connect
 * button, and the Support tab) — the offers themselves live only on that screen.
 */
@Composable
fun OffersLinkCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    // The verified paid subscriptions are Russian services (paid by SBP / Russian cards):
    // the entry point exists only in the Russian UI.
    if (LocalConfiguration.current.locales[0].language != "ru") return
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
