package me.rerere.rikkahub.ui.pages.setting

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import me.rerere.ui.toast.ToastType
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.stellar.StellarManager
import me.rerere.rikkahub.data.stellar.StellarStatus
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import roro.stellar.Stellar

private const val STELLAR_RELEASES_URL = "https://github.com/roro2239/Stellar/releases/latest"

/**
 * 设置 -> Stellar。
 *
 * 三行状态(管理器 / 服务 / 授权)加一段说明, 与 Termux 那类外部依赖页的结构保持一致。
 * 授权只从这里的显式点击发起, 应用启动和首次对话都不会自动弹窗。
 */
@Composable
fun SettingStellarPage() {
    val ctx = LocalContext.current
    val toaster = LocalToaster.current
    val settings = LocalSettings.current
    val settingsStore: SettingsStore = koinInject()
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var status by remember { mutableStateOf(StellarManager.status(ctx)) }
    fun refresh() {
        status = StellarManager.status(ctx)
    }

    // 实时刷新: binder 出现/消失, 以及授权请求的异步结果。
    DisposableEffect(Unit) {
        val binderReceived = Stellar.OnBinderReceivedListener { refresh() }
        val binderDead = Stellar.OnBinderDeadListener { refresh() }
        val permissionResult = Stellar.OnRequestPermissionResultListener { _, allowed, _ ->
            refresh()
            toaster.show(
                message = ctx.getString(
                    if (allowed) R.string.setting_stellar_toast_permission_granted
                    else R.string.setting_stellar_toast_permission_denied
                ),
                type = if (allowed) ToastType.Success else ToastType.Error,
            )
        }
        StellarManager.addBinderReceivedListener(binderReceived)
        StellarManager.addBinderDeadListener(binderDead)
        StellarManager.addRequestPermissionResultListener(permissionResult)
        onDispose {
            StellarManager.removeBinderReceivedListener(binderReceived)
            StellarManager.removeBinderDeadListener(binderDead)
            StellarManager.removeRequestPermissionResultListener(permissionResult)
        }
    }

    // 从 Stellar 管理器返回时重新检查, 免得"装好了/服务起了"但页面还停在旧状态。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_stellar_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CardGroup(
                title = { Text(stringResource(R.string.setting_stellar_section_status)) },
            ) {
                val managerInstalled = status != StellarStatus.NOT_INSTALLED
                val serviceRunning = status == StellarStatus.PERMISSION_DENIED || status == StellarStatus.READY
                val permissionGranted = status == StellarStatus.READY

                item(
                    onClick = {
                        // 装了就打开管理器; 没装就直接跳 releases 页
                        val opened = StellarManager.openManager(ctx)
                        if (!opened) {
                            runCatching {
                                ctx.startActivity(
                                    Intent(Intent.ACTION_VIEW).apply {
                                        data = Uri.parse(STELLAR_RELEASES_URL)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                )
                            }
                        }
                    },
                    headlineContent = { Text(stringResource(R.string.setting_stellar_status_manager)) },
                    supportingContent = {
                        Text(
                            stringResource(
                                if (managerInstalled) R.string.setting_stellar_status_manager_installed
                                else R.string.setting_stellar_status_manager_missing
                            )
                        )
                    },
                    leadingContent = { StatusDot(active = managerInstalled) },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_stellar_status_service)) },
                    supportingContent = {
                        Text(
                            stringResource(
                                when {
                                    !serviceRunning -> R.string.setting_stellar_status_service_not_running
                                    StellarManager.serviceUid() == 0 -> R.string.setting_stellar_status_service_running_root
                                    else -> R.string.setting_stellar_status_service_running
                                }
                            )
                        )
                    },
                    leadingContent = { StatusDot(active = serviceRunning) },
                )
                item(
                    onClick = {
                        if (!serviceRunning) {
                            toaster.show(
                                message = ctx.getString(R.string.setting_stellar_toast_service_not_running),
                                type = ToastType.Error,
                            )
                        } else {
                            StellarManager.requestPermission()
                        }
                    },
                    headlineContent = { Text(stringResource(R.string.setting_stellar_status_permission)) },
                    supportingContent = {
                        Text(
                            stringResource(
                                if (permissionGranted) R.string.setting_stellar_status_permission_granted
                                else R.string.setting_stellar_status_permission_missing
                            )
                        )
                    },
                    leadingContent = { StatusDot(active = permissionGranted) },
                )
            }

            CardGroup(
                title = { Text(stringResource(R.string.setting_stellar_section_safety)) },
            ) {
                switchItem(
                    checked = settings.stellarAutoApprove,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            settingsStore.update { it.copy(stellarAutoApprove = enabled) }
                        }
                    },
                    headlineContent = { Text(stringResource(R.string.setting_stellar_auto_approve_title)) },
                    supportingContent = { Text(stringResource(R.string.setting_stellar_auto_approve_desc)) },
                )
            }

            CardGroup(
                title = { Text(stringResource(R.string.setting_stellar_section_help)) },
            ) {
                item(
                    headlineContent = { Text(stringResource(R.string.setting_stellar_help_what_title)) },
                    supportingContent = { Text(stringResource(R.string.setting_stellar_help_what_body)) },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_stellar_help_how_title)) },
                    supportingContent = { Text(stringResource(R.string.setting_stellar_help_how_body)) },
                )
            }
        }
    }
}

@Composable
private fun StatusDot(active: Boolean) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(
                if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
    )
}
