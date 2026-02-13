package com.github.derminator.archipelobby.storage

import org.springframework.http.codec.multipart.FilePart

interface UploadsService {
    suspend fun saveFile(filePart: FilePart): String
    suspend fun getFile(filePath: String): ByteArray
    suspend fun deleteFile(filePath: String)
    suspend fun fileExists(filePath: String): Boolean
}
