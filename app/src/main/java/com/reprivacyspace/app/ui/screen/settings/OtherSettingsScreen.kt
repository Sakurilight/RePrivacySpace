package com.reprivacyspace.app.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Switch
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.accompanist.insets.navigationBarsPadding
import com.reprivacyspace.app.R
import com.reprivacyspace.app.ui.widget.TopBar
import com.reprivacyspace.app.util.ConfigHelper
import com.reprivacyspace.app.util.LocalNavHostController
import com.reprivacyspace.app.util.isLauncherIconVisible
import com.reprivacyspace.app.util.setLauncherIconVisible
import com.reprivacyspace.app.util.showToast

/**
 * "Other Settings" from PrivacySpace 1.3.11: quick launch (Volume+/Volume-/Power) and the
 * verbose logging switch.  Both are stored in the shared config file so the system_server
 * side picks them up on the next config reload / reboot.
 */
@Composable
fun OtherSettingsScreen() {
    val navController = LocalNavHostController.current
    val config by ConfigHelper.configDataFlow.collectAsState()
    val context = LocalContext.current
    var launcherIconVisible by remember { mutableStateOf(context.isLauncherIconVisible()) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(title = stringResource(R.string.other_settings), onNavigationIconClick = {
            navController.popBackStack()
        })
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                SwitchSettingItem(
                    title = stringResource(R.string.quick_launch),
                    summary = stringResource(R.string.quick_launch_explanation),
                    checked = config.quickLaunch == true,
                    onCheckedChange = { ConfigHelper.updateQuickLaunch(it) }
                )
            }
            item {
                SwitchSettingItem(
                    title = stringResource(R.string.verbose_logs),
                    summary = stringResource(R.string.verbose_logs_explanation),
                    checked = config.enableDetailLog,
                    onCheckedChange = { ConfigHelper.updateVerboseLog(it) }
                )
            }
            item {
                SwitchSettingItem(
                    title = stringResource(R.string.hide_launcher_icon),
                    summary = stringResource(R.string.hide_launcher_icon_explanation),
                    checked = !launcherIconVisible,
                    onCheckedChange = { hide ->
                        if (context.setLauncherIconVisible(!hide)) {
                            launcherIconVisible = !hide
                            if (hide && config.quickLaunch != true) {
                                // Safety net: without an icon the only reliable way back in
                                // is the key sequence, so enable it together with hiding.
                                ConfigHelper.updateQuickLaunch(true)
                                context.showToast(
                                    context.getString(R.string.hide_launcher_icon_quick_launch_enabled)
                                )
                            }
                        }
                    }
                )
            }
            item {
                Text(
                    text = stringResource(R.string.configuration_takes_effect_after_restarting_the_phone_system),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.caption,
                    color = Color.Gray
                )
            }
            item {
                Box(modifier = Modifier.navigationBarsPadding())
            }
        }
    }
}

@Composable
private fun SwitchSettingItem(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.subtitle1)
            Text(
                text = summary,
                style = MaterialTheme.typography.caption,
                color = Color.Gray
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
    Divider()
}
