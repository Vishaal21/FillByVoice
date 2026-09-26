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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.vishal.fillbyvoice.R
import com.vishal.fillbyvoice.voice.Language
import com.vishal.fillbyvoice.voice.saveLanguage
import com.vishal.fillbyvoice.voice.savedLanguage

enum class Tab(val label: Int, val icon: Int) {
    SCAN(R.string.tab_scan, R.drawable.ic_scan),
    FORMS(R.string.tab_forms, R.drawable.ic_forms),
    MY_INFO(R.string.tab_my_info, R.drawable.ic_my_info),
    SETTINGS(R.string.tab_settings, R.drawable.ic_settings),
}

@Composable
fun FillByVoiceApp() {
    val context = LocalContext.current
    var language by remember { mutableStateOf(context.savedLanguage()) }
    var selected by rememberSaveable { mutableStateOf(Tab.SCAN) }

    val chosen = language
    val choose: (Language) -> Unit = {
        context.saveLanguage(it)
        language = it
    }
    if (chosen == null) {
        LanguageScreen(onChoose = choose)
        return
    }

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
            Tab.SCAN -> ScanScreen(chosen, modifier)
            Tab.FORMS -> FormsScreen(modifier)
            Tab.MY_INFO -> MyInfoScreen(modifier)
            Tab.SETTINGS -> SettingsScreen(chosen, onLanguage = choose, modifier = modifier)
        }
    }
}
