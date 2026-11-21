package com.edusing.chamcongtvbox.domain.repository

import com.edusing.chamcongtvbox.domain.model.ClassGroup
import com.edusing.chamcongtvbox.domain.model.Member

interface MemberRepository {
    suspend fun getClasses(apiDomain: String, bearerToken: String): Result<List<ClassGroup>>
    suspend fun getStudents(apiDomain: String, bearerToken: String): Result<List<Member>>
}
