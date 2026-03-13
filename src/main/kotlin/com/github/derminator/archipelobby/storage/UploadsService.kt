package com.github.derminator.archipelobby.storage

interface UploadsService {
    suspend fun saveFile(content: ByteArray, filename: String): String
    suspend fun getFile(filePath: String): ByteArray
    suspend fun deleteFile(filePath: String)
    suspend fun fileExists(filePath: String): Boolean
}
