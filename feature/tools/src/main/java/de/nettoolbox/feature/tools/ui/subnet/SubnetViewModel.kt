package de.nettoolbox.feature.tools.ui.subnet

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import de.nettoolbox.feature.tools.domain.subnet.Ipv4Subnet
import de.nettoolbox.feature.tools.domain.subnet.Ipv6Subnet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * @param fullWidth renders the value on its own line below the label. Binary
 *   octets are 35 characters and would otherwise wrap mid-value, which is
 *   exactly the presentation that makes people miscount bits.
 */
data class SubnetRow(
    val label: String,
    val value: String,
    val monospace: Boolean = true,
    val fullWidth: Boolean = false,
)

sealed interface SubnetResult {
    data object Empty : SubnetResult
    data class Invalid(val input: String) : SubnetResult
    data class Ipv4(val subnet: Ipv4Subnet) : SubnetResult
    data class Ipv6(val subnet: Ipv6Subnet) : SubnetResult
}

data class SubnetUiState(
    val input: String = "",
    val result: SubnetResult = SubnetResult.Empty,
    val splitPrefix: Int? = null,
    val splitResult: List<Ipv4Subnet> = emptyList(),
    val splitError: String? = null,
)

/**
 * The calculator is a pure function, so there is no coroutine and no loading
 * state here - and no history entry either: recording a row on every keystroke
 * would bury the run history of the tools that actually touch the network.
 */
@HiltViewModel
class SubnetViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SubnetUiState(input = savedStateHandle[KEY_INPUT] ?: ""),
    )
    val uiState: StateFlow<SubnetUiState> = _uiState.asStateFlow()

    init {
        onInputChange(_uiState.value.input)
    }

    fun onInputChange(input: String) {
        savedStateHandle[KEY_INPUT] = input
        _uiState.update {
            it.copy(
                input = input,
                result = calculate(input),
                splitResult = emptyList(),
                splitError = null,
            )
        }
    }

    fun onSplit(newPrefix: Int) {
        val current = _uiState.value.result
        if (current !is SubnetResult.Ipv4) return

        try {
            _uiState.update {
                it.copy(
                    splitPrefix = newPrefix,
                    splitResult = current.subnet.split(newPrefix),
                    splitError = null,
                )
            }
        } catch (invalid: IllegalArgumentException) {
            _uiState.update {
                it.copy(splitPrefix = newPrefix, splitResult = emptyList(), splitError = invalid.message)
            }
        }
    }

    private fun calculate(input: String): SubnetResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return SubnetResult.Empty

        Ipv4Subnet.parseOrNull(trimmed)?.let { return SubnetResult.Ipv4(it) }
        Ipv6Subnet.parseOrNull(trimmed)?.let { return SubnetResult.Ipv6(it) }
        return SubnetResult.Invalid(trimmed)
    }

    private companion object {
        const val KEY_INPUT = "subnet_input"
    }
}
