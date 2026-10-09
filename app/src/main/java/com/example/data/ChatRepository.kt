package com.example.data

import com.example.api.GeminiContent
import com.example.api.GeminiGenerationConfig
import com.example.api.GeminiPart
import com.example.api.GeminiRequest
import com.example.api.RetrofitClient
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

class ChatRepository(private val chatDao: ChatDao) {

    val allSessions: Flow<List<ChatSession>> = chatDao.getAllSessions()

    fun getMessagesForSession(sessionId: String): Flow<List<Message>> =
        chatDao.getMessagesForSession(sessionId)

    suspend fun createNewSession(
        title: String = "New Chat",
        systemInstruction: String = "You are a helpful assistant.",
        temperature: Float = 0.7f
    ): ChatSession = withContext(Dispatchers.IO) {
        val newSession = ChatSession(
            id = UUID.randomUUID().toString(),
            title = title,
            systemInstruction = systemInstruction,
            temperature = temperature,
            timestamp = System.currentTimeMillis()
        )
        chatDao.insertSession(newSession)
        newSession
    }

    suspend fun updateSession(session: ChatSession) = withContext(Dispatchers.IO) {
        chatDao.updateSession(session)
    }

    suspend fun deleteSession(session: ChatSession) = withContext(Dispatchers.IO) {
        chatDao.deleteSession(session)
    }

    suspend fun clearAllHistory() = withContext(Dispatchers.IO) {
        chatDao.deleteAllSessions()
    }

    suspend fun insertMessage(message: Message) = withContext(Dispatchers.IO) {
        chatDao.insertMessage(message)
    }

    suspend fun getSessionById(sessionId: String): ChatSession? = withContext(Dispatchers.IO) {
        chatDao.getSessionById(sessionId)
    }

    suspend fun sendMessage(
        sessionId: String,
        userMessageText: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            // 1. Get the session info
            val session = chatDao.getSessionById(sessionId)
                ?: return@withContext Result.failure(Exception("Session not found"))

            // 2. Insert User's message
            val userMsg = Message(
                chatSessionId = sessionId,
                role = "user",
                text = userMessageText,
                timestamp = System.currentTimeMillis()
            )
            chatDao.insertMessage(userMsg)

            // 3. Auto-rename session if it's currently named "New Chat"
            if (session.title == "New Chat") {
                val proposedTitle = if (userMessageText.length > 30) {
                    userMessageText.take(27) + "..."
                } else {
                    userMessageText
                }
                chatDao.updateSession(session.copy(title = proposedTitle))
            }

            // 4. Load full message history context
            val history = chatDao.getMessagesForSessionSync(sessionId)
            val contents = history.map { msg ->
                GeminiContent(
                    role = if (msg.role == "user") "user" else "model",
                    parts = listOf(GeminiPart(text = msg.text))
                )
            }

            // 5. Setup System Instruction
            val systemInstructionContent = if (session.systemInstruction.isNotBlank()) {
                GeminiContent(
                    parts = listOf(GeminiPart(text = session.systemInstruction))
                )
            } else {
                null
            }

            // 6. Setup Configuration
            val config = GeminiGenerationConfig(
                temperature = session.temperature,
                maxOutputTokens = 2048
            )

            // 7. Make the API Call using BuildConfig key
            val apiKey = BuildConfig.GEMINI_API_KEY
            if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
                return@withContext Result.failure(
                    Exception("Gemini API key is not configured. Please add it via the Secrets panel.")
                )
            }

            val request = GeminiRequest(
                contents = contents,
                generationConfig = config,
                systemInstruction = systemInstructionContent
            )

            val response = RetrofitClient.service.generateContent(apiKey, request)
            val responseText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                ?: return@withContext Result.failure(Exception("Received empty response from AI model."))

            // 8. Insert Model's message
            val modelMsg = Message(
                chatSessionId = sessionId,
                role = "model",
                text = responseText,
                timestamp = System.currentTimeMillis()
            )
            chatDao.insertMessage(modelMsg)

            Result.success(responseText)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
