package de.nettoolbox.feature.tools.ui.wol

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.nettoolbox.core.common.result.NetToolboxError
import de.nettoolbox.core.common.result.Outcome
import de.nettoolbox.core.database.entity.ToolRunEntity
import de.nettoolbox.core.database.entity.ToolType
import de.nettoolbox.core.database.repository.ToolRunRepository
import de.nettoolbox.feature.tools.domain.wol.MagicPacket
import de.nettoolbox.feature.tools.domain.wol.WakeOnLanSender
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WolUiState(
    val macAddress: String = "",
    val broadcastAddress: String = "255.255.255.255",
    val port: String = MagicPacket.DEFAULT_PORT.toString(),
    val isSending: Boolean = false,
    val packetSent: Boolean = false,
    val error: NetToolboxError? = null,
) {
    val isMacValid: Boolean get() = MagicPacket.parseMac(macAddress) != null
    val canSend: Boolean get() = isMacValid && !isSending && port.toIntOrNull() in 1..65535
}

@HiltViewModel
class WolViewModel @Inject constructor(
    private val sender: WakeOnLanSender,
    private val toolRunRepository: ToolRunRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WolUiState())
    val uiState: StateFlow<WolUiState> = _uiState.asStateFlow()

    /** Previously woken devices double as the saved-device list. */
    val savedDevices: StateFlow<List<ToolRunEntity>> = toolRunRepository
        .history(ToolType.WOL, limit = 20)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onMacChange(value: String) = _uiState.update {
        it.copy(macAddress = value, packetSent = false, error = null)
    }

    fun onBroadcastChange(value: String) = _uiState.update { it.copy(broadcastAddress = value) }

    fun onPortChange(value: String) = _uiState.update { it.copy(port = value.filter { c -> c.isDigit() }) }

    fun onSelectSaved(entry: ToolRunEntity) = _uiState.update {
        it.copy(macAddress = entry.target, packetSent = false, error = null)
    }

    fun send() {
        val state = _uiState.value
        if (!state.canSend) return

        _uiState.update { it.copy(isSending = true, packetSent = false, error = null) }

        viewModelScope.launch {
            val runId = toolRunRepository.startRun(
                toolType = ToolType.WOL,
                target = state.macAddress.uppercase(),
                paramsJson = """{"broadcast":"${state.broadcastAddress}","port":${state.port}}""",
            )

            when (
                val result = sender.send(
                    macAddress = state.macAddress,
                    broadcastAddress = state.broadcastAddress,
                    port = state.port.toInt(),
                )
            ) {
                is Outcome.Success -> {
                    toolRunRepository.finishRun(runId, resultJson = null, success = true)
                    // "Sent", never "woken": Wake-on-LAN has no acknowledgement.
                    _uiState.update { it.copy(isSending = false, packetSent = true) }
                }

                is Outcome.Failure -> {
                    toolRunRepository.finishRun(runId, resultJson = null, success = false)
                    _uiState.update { it.copy(isSending = false, error = result.error) }
                }

                Outcome.Loading -> Unit
            }
        }
    }
}
