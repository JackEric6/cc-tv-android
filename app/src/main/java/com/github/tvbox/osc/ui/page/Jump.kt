package com.github.tvbox.osc.ui.page

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.github.tvbox.osc.ui.activity.DetailActivity
import com.github.tvbox.osc.ui.activity.SearchActivity

fun Context.jumpToDetail(id: String?, sourceKey: String?, title: String?, picture: String?, collect: Boolean = false) {
    startActivity(
        Intent(this, DetailActivity::class.java)
            .putExtra("id", id?.trim().orEmpty())
            .putExtra("sourceKey", sourceKey?.trim().orEmpty())
            .putExtra("title", title)
            .putExtra("picture", picture)
            .putExtra("collect", collect),
    )
}

fun Context.jumpToSearch(title: String) {
    val bundle = Bundle()
    bundle.putString("title", title)
    startActivity(Intent(this, SearchActivity::class.java).putExtras(bundle))
}
