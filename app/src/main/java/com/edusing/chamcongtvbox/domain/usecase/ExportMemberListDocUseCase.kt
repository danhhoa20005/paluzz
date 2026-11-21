package com.edusing.chamcongtvbox.domain.usecase

import com.edusing.chamcongtvbox.domain.model.ClassGroup
import com.edusing.chamcongtvbox.domain.model.Member
import com.edusing.chamcongtvbox.domain.repository.ConfigRepository
import com.edusing.chamcongtvbox.domain.repository.ExportRepository

/**
 * Xuất danh sách học sinh/nhân viên ra tệp Word (`.doc`) chia theo Lớp/Phòng ban
 * để in giấy gọi chích vân tay / khuôn mặt.
 */
class ExportMemberListDocUseCase(
    private val exportRepository: ExportRepository,
    private val abbreviateNamesUseCase: AbbreviateNamesUseCase,
    private val configRepository: ConfigRepository,
) {
    suspend operator fun invoke(
        orgName: String,
        classes: List<ClassGroup>,
        members: List<Member>,
        existingDeviceNames: Collection<String>,
        maxNameBytes: Int = AbbreviateNamesUseCase.MAX_NAME_BYTES,
    ): Result<String> {
        val branding = configRepository.getBranding()
        val shortList = abbreviateNamesUseCase.abbreviateBatch(
            fullNames = members.map { it.name },
            existingNamesOnDevice = existingDeviceNames,
            maxNameBytes = maxNameBytes,
        )
        val map = LinkedHashMap<Int, String>(members.size)
        members.forEachIndexed { index, member ->
            member.cardIdInt?.let { map[it] = shortList[index] }
        }
        return exportRepository.exportMembersToWordDoc(
            title = "DANH SÁCH GỌI CHÍCH VÂN TAY / KHUÔN MẶT (${branding.person.uppercase()})",
            orgName = orgName,
            groupLabel = branding.group,
            personLabel = branding.person,
            classes = classes,
            members = members,
            abbreviatedNames = map,
        )
    }
}
