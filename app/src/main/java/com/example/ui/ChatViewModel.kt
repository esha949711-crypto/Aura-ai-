package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.ChatRepository
import com.example.data.ChatSession
import com.example.data.Message
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: ChatRepository

    init {
        val chatDao = AppDatabase.getDatabase(application).chatDao()
        repository = ChatRepository(chatDao)
    }

    // List of all persistent sessions
    val sessions: StateFlow<List<ChatSession>> = repository.allSessions
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Current active session ID
    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    // Automatically retrieve the full ChatSession details for the active session
    @OptIn(ExperimentalCoroutinesApi::class)
    val currentSession: StateFlow<ChatSession?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf<ChatSession?>(null)
            else flow {
                emit(repository.getSessionById(id))
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    // Current active session's messages
    @OptIn(ExperimentalCoroutinesApi::class)
    val currentMessages: StateFlow<List<Message>> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else repository.getMessagesForSession(id)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // AI is generating response indicator
    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    // Error messages
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // Active typed message input
    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    init {
        // Auto-select the most recent session if available
        viewModelScope.launch {
            sessions.collectFirst { list ->
                if (list.isNotEmpty() && _currentSessionId.value == null) {
                    _currentSessionId.value = list.first().id
                }
            }
        }
    }

    fun selectSession(sessionId: String?) {
        _currentSessionId.value = sessionId
        clearError()
    }

    fun updateInputText(text: String) {
        _inputText.value = text
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun startNewChat(
        systemInstruction: String = "You are a helpful assistant.",
        temperature: Float = 0.7f
    ) {
        viewModelScope.launch {
            val session = repository.createNewSession(
                title = "New Chat",
                systemInstruction = systemInstruction,
                temperature = temperature
            )
            _currentSessionId.value = session.id
            _inputText.value = ""
            clearError()
        }
    }

    fun deleteSession(session: ChatSession) {
        viewModelScope.launch {
            repository.deleteSession(session)
            if (_currentSessionId.value == session.id) {
                // Select another session or null
                val remaining = sessions.value.filter { it.id != session.id }
                if (remaining.isNotEmpty()) {
                    _currentSessionId.value = remaining.first().id
                } else {
                    _currentSessionId.value = null
                }
            }
        }
    }

    fun updateSessionConfig(systemInstruction: String, temperature: Float) {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch {
            val session = repository.getSessionById(sessionId)
            if (session != null) {
                val updated = session.copy(
                    systemInstruction = systemInstruction,
                    temperature = temperature
                )
                repository.updateSession(updated)
            }
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            repository.clearAllHistory()
            _currentSessionId.value = null
        }
    }

    fun sendMessage() {
        val messageText = _inputText.value.trim()
        if (messageText.isBlank()) return

        val sessionId = _currentSessionId.value

        viewModelScope.launch {
            _inputText.value = ""
            _isGenerating.value = true
            clearError()

            // If there's no active session, create one automatically
            val activeSessionId = if (sessionId == null) {
                val session = repository.createNewSession()
                _currentSessionId.value = session.id
                session.id
            } else {
                sessionId
            }

            val result = repository.sendMessage(activeSessionId, messageText)
            _isGenerating.value = false

            result.onFailure { error ->
                _errorMessage.value = error.message ?: "An unknown network error occurred."
            }
        }
    }

    fun sendSuggestedPrompt(promptText: String) {
        _inputText.value = promptText
        sendMessage()
    }

    // Helper extension to collect the first non-empty list of sessions (or empty if none exist immediately)
    private suspend fun <T> Flow<T>.collectFirst(action: suspend (T) -> Unit) {
        take(1).collect(action)
    }
}
