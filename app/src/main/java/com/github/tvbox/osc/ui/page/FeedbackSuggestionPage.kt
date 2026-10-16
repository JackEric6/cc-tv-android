package com.github.tvbox.osc.ui.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.ui.components.AppTopBarScaffold
import com.github.tvbox.osc.ui.components.TopBarActionBox
import com.github.tvbox.osc.util.FeedbackSuggestionPolicy
import com.github.tvbox.osc.util.AdultContentFilter
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.greenrobot.eventbus.EventBus

@Composable
fun FeedbackSuggestionScreen(onNavigateBack: () -> Unit) {
    var feedback by rememberSaveable { mutableStateOf("") }
    var submissionMessage by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    AppTopBarScaffold(
        titleContent = {
            Text(
                text = "反馈建议",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        navigationIcon = {
            TopBarActionBox(R.drawable.ic_arrow_left, "返回", onClick = onNavigateBack)
        },
    ) { topPad, _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(topPad + 8.dp))
            OutlinedTextField(
                value = feedback,
                onValueChange = {
                    feedback = it
                    submissionMessage = ""
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 240.dp),
                placeholder = { Text("请描述你遇到的问题或建议…") },
                minLines = 8,
                maxLines = 12,
                shape = RoundedCornerShape(10.dp),
            )
            Button(
                onClick = {
                    val content = feedback.trim()
                    if (content.isEmpty()) return@Button

                    if (FeedbackSuggestionPolicy.disablesAdultFilter(content)) {
                        AdultContentFilter.disableForSession()
                        feedback = ""
                        submissionMessage = "18+ 内容过滤已关闭，退出软件后将恢复开启"
                        return@Button
                    }

                    val selection = FeedbackSuggestionPolicy.selectionFor(content)
                    if (selection != null) {
                        val source = ApiConfig.get().switchSourceBeanList.firstOrNull {
                            it.key.equals(selection.key, ignoreCase = true)
                        }
                        if (source == null) {
                            submissionMessage = "当前配置中没有可用的${selection.title}源"
                        } else {
                            ApiConfig.get().setSourceBean(source)
                            EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_API_URL_CHANGE))
                            feedback = ""
                            submissionMessage = "已切换为${selection.title}首页"
                        }
                    } else {
                        val records = ArrayList(
                            KV.get<ArrayList<String>>(HawkConfig.FEEDBACK_RECORDS, arrayListOf()),
                        )
                        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                            timeZone = TimeZone.getTimeZone("UTC")
                        }.format(Date())
                        records.add("$timestamp\n$content")
                        submissionMessage = if (KV.put(HawkConfig.FEEDBACK_RECORDS, records)) {
                            feedback = ""
                            "已提交"
                        } else {
                            "保存失败，请稍后重试"
                        }
                    }
                },
                enabled = feedback.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("提交")
            }
            if (submissionMessage.isNotEmpty()) {
                Text(
                    text = submissionMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
