package com.edusing.chamcongtvbox.domain.repository

import com.edusing.chamcongtvbox.domain.model.ClassGroup
import com.edusing.chamcongtvbox.domain.model.Member

interface ExportRepository {
    /**
     * Generates a Word-compatible (.doc HTML) table containing full name, abbreviated device name,
     * card ID, and class/department — matching the "Xuất Word" feature in PushForm.cs.
     * Returns the absolute file path written on disk.
     */
    suspend fun exportMembersToWordDoc(
        title: String,
        orgName: String,
        groupLabel: String,
        personLabel: String,
        classes: List<ClassGroup>,
        members: List<Member>,
        abbreviatedNames: Map<Int, String>,
    ): Result<String>
}
