package com.tulipskun.aixodia.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * The daemon's server-driven UI spec (AX-UI). The app ships the components; the
 * spec decides how they are worded and arranged, so a change on the daemon
 * reaches the phone on its next open with no rebuild and no reinstall.
 *
 * Every field has a built-in default here, so the app looks right even before
 * the first fetch or when the daemon is unreachable. Unknown keys in the JSON
 * are ignored, so the daemon may add vocabulary the app does not use yet.
 */
@JsonClass(generateAdapter = true)
data class UiSpec(
    val version: Int = 1,
    val theme: UiTheme = UiTheme(),
    val strings: Map<String, String> = emptyMap(),
    val topbar: TopbarSpec = TopbarSpec(),
    @Json(name = "tool_block") val toolBlock: ToolBlockSpec = ToolBlockSpec(),
    @Json(name = "error_card") val errorCard: ErrorCardSpec = ErrorCardSpec(),
    val composer: ComposerSpec = ComposerSpec(),
    /** Whole screens the daemon composes from the app's built-in primitives. */
    val screens: Map<String, UiScreen> = emptyMap(),
    /** Extra rows the drawer shows, each opening a screen or running an action. */
    @Json(name = "drawer_items") val drawerItems: List<UiItem> = emptyList(),
) {
    private fun s(key: String, fallback: String): String =
        strings[key]?.takeIf { it.isNotBlank() } ?: fallback

    val appName get() = s("app_name", "AIxodia")
    val composerPlaceholder get() = s("composer_placeholder", "ส่งข้อความ…")
    val send get() = s("send", "ส่ง")
    val attach get() = s("attach", "แนบไฟล์")
    val attachTitle get() = s("attach_title", "แนบสิ่งที่ต้องการ")
    val attachMedia get() = s("attach_media", "รูปภาพ / วิดีโอ")
    val attachFile get() = s("attach_file", "ไฟล์แนบ")
    val stop get() = s("stop", "หยุดการทำงาน")
    val retry get() = s("retry", "ลองอีกครั้ง")
    val dismiss get() = s("dismiss", "ปิด")
    val newChat get() = s("new_chat", "แชทใหม่")
    val settings get() = s("settings", "ตั้งค่า")
    val providers get() = s("providers", "ผู้ให้บริการ")
    val refresh get() = s("refresh", "ดึงข้อมูลล่าสุด")
    val menu get() = s("menu", "เมนูแชท")
    val agentDefault get() = s("agent_default", "Agent default")
    val pickModel get() = s("pick_model", "เลือก model")
    val thinking get() = s("thinking", "กำลังคิด")
    val answering get() = s("answering", "กำลังตอบ")
    val copied get() = s("copied", "คัดลอกแล้ว")
    val copyHint get() = s("copy_hint", "แตะค้างเพื่อคัดลอก")
    val online get() = s("online", "ออนไลน์")
    val connecting get() = s("connecting", "กำลังต่อ")
    val offline get() = s("offline", "ออฟไลน์")
    val errorTitle get() = s("error_title", "เกิดข้อผิดพลาด")
    val collapse get() = s("collapse", "ยุบ")
    val expand get() = s("expand", "ขยาย")
    val working get() = s("working", "agent กำลังทำงาน")
    val emptyTitle get() = s("empty_title", "เริ่มสนทนากับ agent")
    val emptyHint get() = s("empty_hint", "พิมพ์งานด้านล่าง แล้ว agent จะทำต่อแม้ปิดแอป")
}

@JsonClass(generateAdapter = true)
data class UiTheme(
    val seed: String = "#7C6CF0",
    val dark: Boolean = true,
    @Json(name = "corner_radius") val cornerRadius: Int = 18,
    /** Optional explicit accent; when blank the built-in scheme is kept. */
    val primary: String = "",
)

@JsonClass(generateAdapter = true)
data class TopbarSpec(
    @Json(name = "show_status_dot") val showStatusDot: Boolean = true,
    @Json(name = "show_uptime") val showUptime: Boolean = true,
    @Json(name = "uptime_format") val uptimeFormat: String = "HH:mm:ss",
    @Json(name = "show_refresh") val showRefresh: Boolean = true,
    @Json(name = "show_model_chip") val showModelChip: Boolean = true,
)

@JsonClass(generateAdapter = true)
data class ToolBlockSpec(
    @Json(name = "collapsed_by_default") val collapsedByDefault: Boolean = true,
    @Json(name = "header_template") val headerTemplate: String = "{icon} {name} {args}",
    @Json(name = "show_duration") val showDuration: Boolean = true,
    @Json(name = "max_args_chars") val maxArgsChars: Int = 80,
    @Json(name = "show_icon") val showIcon: Boolean = true,
)

@JsonClass(generateAdapter = true)
data class ErrorCardSpec(
    @Json(name = "show_retry") val showRetry: Boolean = true,
    @Json(name = "show_dismiss") val showDismiss: Boolean = true,
    @Json(name = "show_raw") val showRaw: Boolean = false,
)

@JsonClass(generateAdapter = true)
data class ComposerSpec(
    @Json(name = "max_lines") val maxLines: Int = 5,
    @Json(name = "show_stop_button") val showStopButton: Boolean = true,
    @Json(name = "show_attach") val showAttach: Boolean = true,
    @Json(name = "show_attach_title") val showAttachTitle: Boolean = false,
)


/**
 * A screen built from the app's component vocabulary. The app ships the
 * primitives (text, button, toggle, field, list, chips, section); the daemon
 * decides how they are arranged and worded. That is what lets a new screen ship
 * without a new APK.
 */
@JsonClass(generateAdapter = true)
data class UiScreen(
    val title: String = "",
    val subtitle: String = "",
    val body: List<UiNode> = emptyList(),
)

/**
 * One node in a spec screen. Only the fields the node's [type] uses are read,
 * so one shape covers every primitive and the daemon can add a field without
 * the app having to know it.
 */
@JsonClass(generateAdapter = true)
data class UiNode(
    val type: String = "",
    // text
    val value: String = "",
    val style: String = "",
    // button / toggle / field
    val label: String = "",
    val subtitle: String = "",
    // action name plus its arguments; {key} in any string is replaced with the
    // stored value of that key, so a screen can show live state.
    val action: String = "",
    val params: Map<String, String> = emptyMap(),
    // toggle / field
    val key: String = "",
    val checked: Boolean = false,
    val placeholder: String = "",
    // container
    val children: List<UiNode> = emptyList(),
    val items: List<UiItem> = emptyList(),
    val options: List<UiOption> = emptyList(),
    val selected: String = "",
)

/** One row of a list node or of the drawer. */
@JsonClass(generateAdapter = true)
data class UiItem(
    val title: String = "",
    val subtitle: String = "",
    val action: String = "",
    val params: Map<String, String> = emptyMap(),
    val trailing: String = "",
)

/** One chip of a chips node. */
@JsonClass(generateAdapter = true)
data class UiOption(
    val label: String = "",
    val value: String = "",
    val action: String = "",
    val params: Map<String, String> = emptyMap(),
)
