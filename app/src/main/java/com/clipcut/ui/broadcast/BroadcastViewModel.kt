package com.clipcut.ui.broadcast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clipcut.BuildConfig
import com.clipcut.data.db.BroadcastLogDao
import com.clipcut.data.db.BroadcastTarget
import com.clipcut.data.db.BroadcastTargetDao
import com.clipcut.domain.broadcast.BroadcastScheduler
import com.clipcut.domain.broadcast.BroadcastService
import com.clipcut.domain.broadcast.BroadcastSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class BroadcastUiState(
    val enabled: Boolean = false,
    val template: String = "",
    val hour: Int = 7,
    val minute: Int = 0,
    val nextRun: String = "",
    val exactAlarms: Boolean = true,
    val telegramConfigured: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

@HiltViewModel
class BroadcastViewModel @Inject constructor(
    private val service: BroadcastService,
    private val settings: BroadcastSettings,
    private val scheduler: BroadcastScheduler,
    private val targetDao: BroadcastTargetDao,
    logDao: BroadcastLogDao,
) : ViewModel() {
    private val _uiState = MutableStateFlow(read())
    val uiState: StateFlow<BroadcastUiState> = _uiState.asStateFlow()

    val targets = targetDao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val log = logDao.observeRecent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun read() = BroadcastUiState(
        enabled = settings.enabled,
        template = settings.template,
        hour = settings.hour,
        minute = settings.minute,
        nextRun = NEXT.format(scheduler.nextRunAt()),
        exactAlarms = scheduler.canScheduleExact(),
        telegramConfigured = BuildConfig.TELEGRAM_BOT_TOKEN.isNotBlank(),
    )

    private fun refresh() = _uiState.update {
        read().copy(busy = it.busy, message = it.message, error = it.error)
    }

    fun setEnabled(on: Boolean) {
        settings.enabled = on
        scheduler.schedule() // arms it, or cancels when switching off
        refresh()
    }

    fun setTemplate(text: String) {
        settings.template = text
        refresh()
    }

    fun setTime(hour: Int, minute: Int) {
        settings.hour = hour
        settings.minute = minute
        scheduler.schedule()
        refresh()
    }

    fun addTarget(platform: String, chatId: String, label: String) {
        val id = chatId.trim()
        val name = label.trim().ifBlank { id }
        if (id.isBlank()) return
        viewModelScope.launch {
            targetDao.insert(BroadcastTarget(platform = platform, chatId = id, label = name))
        }
    }

    fun toggleTarget(target: BroadcastTarget) = viewModelScope.launch {
        targetDao.update(target.copy(enabled = !target.enabled))
    }

    fun removeTarget(target: BroadcastTarget) = viewModelScope.launch { targetDao.delete(target) }

    /** Fetches today's link and checks the bot, sending nothing. The safe way to prove the setup. */
    fun testSetup() {
        _uiState.update { it.copy(busy = true, message = null, error = null) }
        viewModelScope.launch {
            runCatching { service.dryRun() }.fold(
                onSuccess = { (link, bot) ->
                    _uiState.update {
                        it.copy(
                            busy = false,
                            message = "Link OK: $link" + (bot?.let { b -> "\nTelegram bot: $b" } ?: "\nTelegram: no token set"),
                        )
                    }
                },
                onFailure = { e -> _uiState.update { it.copy(busy = false, error = e.message) } },
            )
        }
    }

    /** Sends for real, right now, to every enabled group. Used to prove delivery before trusting 7am. */
    fun sendNow() {
        _uiState.update { it.copy(busy = true, message = null, error = null) }
        viewModelScope.launch {
            runCatching { service.run(manual = true) }.fold(
                onSuccess = { r ->
                    _uiState.update {
                        it.copy(
                            busy = false,
                            message = r.error ?: "Sent to ${r.sent} group(s), ${r.failed} failed.",
                        )
                    }
                },
                onFailure = { e -> _uiState.update { it.copy(busy = false, error = e.message) } },
            )
        }
    }

    fun clearMessage() = _uiState.update { it.copy(message = null, error = null) }

    private companion object {
        val NEXT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")
    }
}
