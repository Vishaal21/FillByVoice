package com.vishal.fillbyvoice.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.vishal.fillbyvoice.R

enum class Tab(val label: Int, val icon: Int) {
    SCAN(R.string.tab_scan, R.drawable.ic_scan),
    FORMS(R.string.tab_forms, R.drawable.ic_forms),
    MY_INFO(R.string.tab_my_info, R.drawable.ic_my_info),
    SETTINGS(R.string.tab_settings, R.drawable.ic_settings),
}

@Composable
fun FillByVoiceApp() {
    var selected by rememberSaveable { mutableStateOf(Tab.SCAN) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = tab == selected,
                        onClick = { selected = tab },
                        icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                        label = { Text(stringResource(tab.label)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        val modifier = Modifier.padding(innerPadding)
        when (selected) {
            Tab.SCAN -> ScanScreen(modifier)
            Tab.FORMS -> FormsScreen(modifier)
            Tab.MY_INFO -> MyInfoScreen(modifier)
            Tab.SETTINGS -> SettingsScreen(modifier)
        }
    }
}
