package com.tulipskun.aixodia.ui.chat

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tulipskun.aixodia.screen.ScreenControlUiState
import com.tulipskun.aixodia.screen.ScreenStage

@Composable
fun ScreenControlCard() {
    val context = LocalContext.current
    val state by ScreenControlUiState.state.collectAsState()
    val accessibility = isAccessibilityEnabled(context)
    val active = accessibility && state.enabled
    val stage = when {
        !accessibility -> "ต้องเปิด Accessibility"
        state.stage == ScreenStage.SEEING -> "กำลังอ่านหน้าจอ"
        state.stage == ScreenStage.WAITING -> "กำลังให้ JEV เลือก"
        state.stage == ScreenStage.ACTING -> "กำลังควบคุมหน้าจอ"
        state.stage == ScreenStage.VERIFYING -> "กำลังตรวจผล"
        state.stage == ScreenStage.ERROR -> "เกิดข้อผิดพลาด"
        else -> "พร้อม"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.TouchApp, contentDescription = null)
                Text(
                    "Screen Control",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
                AssistChip(
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    label = { Text(stage) },
                    leadingIcon = {
                        Icon(
                            if (accessibility) Icons.Default.TouchApp else Icons.Default.OpenInNew,
                            contentDescription = null,
                        )
                    },
                )
            }
            Text(
                when {
                    !accessibility -> "เปิด Accessibility Service เพื่อให้ Main Agent / Sub-agent ควบคุมหน้าจอได้"
                    state.action.isNotBlank() -> state.action + if (state.target.isNotBlank()) " · \${state.target}" else ""
                    else -> "Main Agent / Sub-agent เป็นผู้เรียก JEV; ตัวแอปทำหน้าที่อ่านและลงมือบนหน้าจอ"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (accessibility && state.stage != ScreenStage.IDLE) {
                TextButton(
                    onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    modifier = Modifier.align(Alignment.End),
                ) { Text("ตั้งค่า Accessibility") }
            }
        }
    }
}

private fun isAccessibilityEnabled(context: Context): Boolean {
    val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { it.resolveInfo.serviceInfo.packageName == context.packageName }
}
