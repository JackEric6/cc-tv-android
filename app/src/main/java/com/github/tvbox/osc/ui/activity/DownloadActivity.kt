package com.github.tvbox.osc.ui.activity

import android.content.Context
import android.content.Intent
import androidx.compose.ui.platform.ComposeView
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseActivity
import com.github.tvbox.osc.ui.page.DownloadPage
import com.github.tvbox.osc.ui.page.launchCachedTask
import com.github.tvbox.osc.ui.theme.CcTvTheme
import com.github.tvbox.osc.ui.theme.enableTransparentEdgeToEdge

class DownloadActivity : BaseActivity() {
    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, DownloadActivity::class.java))
        }
    }

    override fun getLayoutResID(): Int = R.layout.activity_main
    override fun shouldRefreshAutoSize(): Boolean = true
    override fun hideSysBar() {
    }

    override fun init() {
        enableTransparentEdgeToEdge()
        findViewById<ComposeView>(R.id.compose_view).setContent {
            CcTvTheme {
                DownloadPage(
                    onNavigateBack = { finish() },
                    onPlay = { task -> launchCachedTask(this@DownloadActivity, task) },
                )
            }
        }
    }
}
