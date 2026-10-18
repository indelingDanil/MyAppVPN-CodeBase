package com.myappvpn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myappvpn.BuildConfig
import com.myappvpn.R
import com.myappvpn.ui.components.DisclaimerText
import com.myappvpn.ui.components.LocalBottomBarPadding
import com.myappvpn.ui.components.ProjectLinksCard
import com.myappvpn.ui.components.SupportCard
import com.myappvpn.ui.theme.*

/** "Проект" tab: news channel, source code, voluntary support, disclaimer. No nagging anywhere else. */
@Composable
fun ProjectScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AetherBackground)
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 16.dp + LocalBottomBarPadding.current),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column {
            Text(
                text = stringResource(R.string.project_title),
                color = AetherTextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.project_subtitle, BuildConfig.VERSION_NAME),
                color = AetherTextSecondary,
                fontSize = 12.sp,
            )
        }
        ProjectLinksCard()
        SupportCard()
        DisclaimerText()
        Spacer(Modifier.height(8.dp))
    }
}
