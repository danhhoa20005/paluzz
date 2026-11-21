package com.edusing.chamcongtvbox.domain.repository

import com.edusing.chamcongtvbox.domain.model.LoginResult

/** Kho xác thực tài khoản trường/đơn vị với máy chủ API. */
interface AuthRepository {
    suspend fun login(apiDomain: String, username: String, password: String): LoginResult

    /** Kiểm tra nhanh DNS + HTTPS + độ trễ tới máy chủ API để chẩn đoán lỗi mạng trên TV Box/điện thoại. */
    suspend fun testServerConnection(apiDomain: String): String
}
