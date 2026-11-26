package com.downnet

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.downnet.core.urlnorm.UrlNormalizer
import com.downnet.core.urlnorm.UrlNormalizer.Platform
import com.downnet.extract.MediaFormat
import com.downnet.extract.MediaInfo
import com.downnet.extract.VariantPicker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface State {
        data object Idle : State
        data object Resolving : State
        data class Extracting(val url: String) : State
        data class Ready(
            val url: String,
            val platform: Platform,
            val info: MediaInfo,
            val muxed: List<VariantPicker.Choice>,
            val audio: VariantPicker.Choice?,
            val images: List<VariantPicker.Choice>,
            val diagnostics: String?,
            val fromShare: Boolean,
        ) : State

        data class Failed(val message: String) : State
    }

    private val container = (app as DownNetApplication).container

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    val downloads = container.downloads.items

    private var lastRaw: String? = null
    private var lastFromShare: Boolean = false

    /** Entry point for both shared links and the paste field. */
    fun onLink(raw: String, fromShare: Boolean) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return
        lastRaw = trimmed
        lastFromShare = fromShare
        viewModelScope.launch {
            _state.value = State.Resolving
            val resolved = runCatching {
                container.shortLinkResolver.resolve(trimmed)
            }.getOrDefault(trimmed)
            val url = UrlNormalizer.normalize(resolved)
            val platform = UrlNormalizer.platformOf(url)
            _state.value = State.Extracting(url)
            container.extractorRegistry.extract(url)
                .onSuccess { info ->
                    val muxed = VariantPicker.muxedChoices(info)
                    val audio = VariantPicker.audioChoice(info)
                    val images = VariantPicker.imageChoices(info)
                    if (muxed.isEmpty() && audio == null && images.isEmpty()) {
                        _state.value = State.Failed(
                            "No directly downloadable formats — needs the M2 downloader (HLS/split streams)"
                        )
                    } else {
                        // Diagnostics doubles as the JS-runtime status probe:
                        // when video formats are missing it tells us why.
                        val diagnostics = runCatching {
                            container.ytdlp.diagnostics()
                        }.getOrNull()
                        _state.value = State.Ready(
                            url, platform, info, muxed, audio, images, diagnostics, fromShare,
                        )
                    }
                }
                .onFailure { t ->
                    _state.value = State.Failed(t.message ?: "Extraction failed")
                }
        }
    }

    fun retry() {
        lastRaw?.let { onLink(it, lastFromShare) }
    }

    fun startDownload(info: MediaInfo, format: MediaFormat) {
        val current = _state.value as? State.Ready ?: return
        container.downloads.enqueue(info, format, current.platform, current.fromShare)
        _state.value = State.Idle
    }

    fun dismissResult() {
        _state.value = State.Idle
    }
}
