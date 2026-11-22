package com.myappvpn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.myappvpn.data.repository.Offer
import com.myappvpn.ui.viewmodel.OffersEvent
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myappvpn.R
import com.myappvpn.ui.components.GlassCard
import com.myappvpn.ui.components.OfferCard
import com.myappvpn.ui.theme.*
import com.myappvpn.ui.viewmodel.OffersViewModel

/**
 * "Verified paid subscriptions": every partner offer (remote list), VPN subscriptions first,
 * plus a short how-to for adding a bought subscription link in the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OffersScreen(
    viewModel: OffersViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onAddSubscription: () -> Unit,
) {
    val offers by viewModel.offers.collectAsStateWithLifecycle()
    val addedUrls by viewModel.addedUrls.collectAsStateWithLifecycle()
    val (vpn, other) = offers.partition { it.isVpn }

    val snackbar = remember { SnackbarHostState() }
    val msgAdded = stringResource(R.string.offer_trial_added_msg)
    val msgAlready = stringResource(R.string.offer_trial_already_msg)
    LaunchedEffect(Unit) {
        viewModel.events.collect { e ->
            val msg = when (e) {
                OffersEvent.TrialAdded -> msgAdded
                OffersEvent.TrialAlreadyAdded -> msgAlready
                is OffersEvent.TrialInvalid -> e.reason
            }
            snackbar.currentSnackbarData?.dismiss()
            launch { snackbar.showSnackbar(msg) }
        }
    }

    @Composable
    fun OfferItem(offer: Offer) = OfferCard(
        offer = offer,
        trialAdded = offer.trialUrl != null && offer.trialUrl in addedUrls,
        onTrial = { name -> viewModel.addTrial(offer, name) },
    )

    val topBarScroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(topBarScroll.nestedScrollConnection),
        containerColor = AetherBackground,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                scrollBehavior = topBarScroll,
                title = { Text(stringResource(R.string.offers_title), color = AetherTextPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.offers_cd_back),
                            tint = AetherTextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AetherBackground,
                    scrolledContainerColor = AetherSurface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(AetherBackground),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.offers_intro),
                    color = AetherTextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            if (vpn.isNotEmpty()) {
                item(key = "h_vpn") { OffersSectionHeader(stringResource(R.string.offers_section_vpn)) }
                items(vpn, key = { "o_" + it.id }) { OfferItem(it) }
                item(key = "howto") { HowToAddCard(onAddSubscription) }
            }
            if (other.isNotEmpty()) {
                item(key = "h_other") { OffersSectionHeader(stringResource(R.string.offers_section_other)) }
                items(other, key = { "o_" + it.id }) { OfferItem(it) }
            }
            // No VPN offers in the list yet — the how-to still belongs on this screen.
            if (vpn.isEmpty()) item(key = "howto") { HowToAddCard(onAddSubscription) }
        }
    }
}

@Composable
private fun OffersSectionHeader(title: String) {
    Text(
        text = title,
        color = AetherCyan,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 2.dp)
    )
}

@Composable
private fun HowToAddCard(onAddSubscription: () -> Unit) {
    GlassCard(modifier = Modifier
        .fillMaxWidth()
        .padding(top = 8.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                stringResource(R.string.offers_howto_title),
                color = AetherTextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            listOf(
                R.string.offers_howto_step1,
                R.string.offers_howto_step2,
                R.string.offers_howto_step3,
            ).forEachIndexed { i, res ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Fixed-width number column: "1." is narrower than "2."/"3." in proportional
                    // fonts, so without it the step texts don't line up.
                    Text(
                        "${i + 1}.",
                        color = AetherCyan,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(16.dp),
                    )
                    Text(stringResource(res), color = AetherTextSecondary, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f))
                }
            }
            FilledTonalButton(
                onClick = onAddSubscription,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .heightIn(min = 48.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = AetherCyan.copy(alpha = 0.15f),
                    contentColor = AetherCyan,
                ),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.subs_add_button))
            }
        }
    }
}
