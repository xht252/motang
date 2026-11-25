package io.github.xht252.motang.ui.search

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.xht252.motang.data.SearchHitUi
import io.github.xht252.motang.data.SearchRepository
import io.github.xht252.motang.data.toUi
import io.github.xht252.motang.core.MotangException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 索引准备阶段。 */
sealed interface IndexState {
    data object Preparing : IndexState
    data class Building(val message: String) : IndexState
    data class Ready(val poems: Int, val elapsedMs: Long) : IndexState
    data class Failed(val reason: String) : IndexState
}

data class SearchUiState(
    val index: IndexState = IndexState.Preparing,
    val query: String = "",
    val hits: List<SearchHitUi> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val loading: Boolean = false,
    val error: String? = null,
    val selected: PoemDetail? = null,
)

data class PoemDetail(
    val title: String,
    val author: String,
    val paragraphs: List<String>,
    val notes: String?,
)

/**
 * 检索页 ViewModel。
 *
 * 索引准备是一次性重活（解压 15MB + 建 5.7 万首索引），放在独立状态里，
 * 与检索状态解耦，避免 UI 在准备期间误发查询。
 */
class SearchViewModel(private val repository: SearchRepository) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    init {
        prepareIndex()
    }

    private fun prepareIndex() {
        viewModelScope.launch {
            try {
                _state.update { it.copy(index = IndexState.Building("正在展开语料…")) }
                repository.ensureCorpus { message ->
                    _state.update { it.copy(index = IndexState.Building(message)) }
                }

                if (repository.hasIndex()) {
                    repository.openIndex()
                    _state.update {
                        it.copy(index = IndexState.Ready(poems = 0, elapsedMs = 0))
                    }
                } else {
                    _state.update {
                        it.copy(index = IndexState.Building("正在建立索引（首次启动约需数十秒）…"))
                    }
                    val stats = repository.buildIndex()
                    _state.update {
                        it.copy(
                            index = IndexState.Ready(
                                poems = stats.poems.toInt(),
                                elapsedMs = stats.elapsedMs.toLong(),
                            ),
                        )
                    }
                    // 设备侧证据：建索引条数与耗时，供 verify 脚本从 logcat 断言。
                    Log.i(TAG, "index built: poems=${stats.poems} elapsedMs=${stats.elapsedMs}")
                }
            } catch (error: MotangException) {
                _state.update { it.copy(index = IndexState.Failed(error.message ?: "索引失败")) }
            }
        }
    }

    fun onQueryChange(value: String) {
        _state.update { it.copy(query = value) }
    }

    fun submitSearch() {
        val current = _state.value
        if (current.index !is IndexState.Ready) return
        val query = current.query.trim()
        if (query.isEmpty()) {
            _state.update { it.copy(hits = emptyList(), total = 0, page = 1, error = null) }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, page = 1) }
            runCatching { repository.search(query, page = 1, pageSize = PAGE_SIZE) }
                .onSuccess { page ->
                    _state.update {
                        it.copy(
                            loading = false,
                            hits = page.hits.map { hit -> hit.toUi() },
                            total = page.total.toInt(),
                        )
                    }
                    Log.i(
                        TAG,
                        "search query=$query total=${page.total} first=${page.hits.firstOrNull()?.title}",
                    )
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(loading = false, error = error.message ?: "检索失败")
                    }
                }
        }
    }

    fun openPoem(poemId: String) {
        viewModelScope.launch {
            runCatching { repository.getPoem(poemId) }
                .onSuccess { poem ->
                    if (poem == null) return@onSuccess
                    _state.update {
                        it.copy(
                            selected = PoemDetail(
                                title = poem.title,
                                author = poem.author,
                                paragraphs = poem.paragraphs,
                                notes = poem.notes,
                            ),
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(error = error.message ?: "打开失败") }
                }
        }
    }

    fun closePoem() {
        _state.update { it.copy(selected = null) }
    }

    companion object {
        private const val PAGE_SIZE = 20
        private const val TAG = "MotangM1"

        fun factory(repository: SearchRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(SearchViewModel::class.java)) {
                        "未知 ViewModel：$modelClass"
                    }
                    return SearchViewModel(repository) as T
                }
            }
    }
}
