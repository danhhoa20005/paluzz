package com.edusing.chamcongtvbox.domain.usecase

import com.edusing.chamcongtvbox.domain.model.MembersTreeData
import com.edusing.chamcongtvbox.domain.protocol.FkDeviceGateway
import com.edusing.chamcongtvbox.domain.repository.ConfigRepository
import com.edusing.chamcongtvbox.domain.repository.MemberRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Tải song song danh sách lớp/phòng ban và học sinh/nhân viên từ máy chủ (tự làm mới token 1 lần nếu hết hạn),
 * đồng thời đọc danh sách mã thẻ đang có trên máy chấm công đích để tách thành 2 nhóm:
 * "Chưa có trên máy" và "Đã có trên máy".
 */
class LoadMembersTreeUseCase(
    private val configRepository: ConfigRepository,
    private val memberRepository: MemberRepository,
    private val validateCredentialsUseCase: ValidateCredentialsUseCase,
    private val deviceGateway: FkDeviceGateway,
) {
    suspend operator fun invoke(targetHandles: List<Int>): Result<MembersTreeData> = coroutineScope {
        var cfg = configRepository.getConfig()
        val domain = configRepository.getBranding().normalizeApiDomain(cfg.apiDomain)
        if (cfg.token.isBlank()) {
            validateCredentialsUseCase()
            cfg = configRepository.getConfig()
        }

        val initialClassesJob = async { memberRepository.getClasses(domain, cfg.token) }
        val initialStudentsJob = async { memberRepository.getStudents(domain, cfg.token) }
        var classesRes = initialClassesJob.await()
        var studentsRes = initialStudentsJob.await()

        // Nếu token hết hạn, tự làm mới 1 lần và gọi song song lại API
        if ((classesRes.isFailure || studentsRes.isFailure) && cfg.password.isNotBlank()) {
            if (validateCredentialsUseCase() == CredentialCheckStatus.VALID) {
                cfg = configRepository.getConfig()
                val retryClassesJob = async { memberRepository.getClasses(domain, cfg.token) }
                val retryStudentsJob = async { memberRepository.getStudents(domain, cfg.token) }
                classesRes = retryClassesJob.await()
                studentsRes = retryStudentsJob.await()
            }
        }

        val students = studentsRes.getOrElse { return@coroutineScope Result.failure(it) }
        val classes = classesRes.getOrElse { emptyList() }

        val existingCards = HashSet<Int>()
        val existingNames = HashSet<String>()
        for (handle in targetHandles) {
            val idsRes = deviceGateway.readAllUserIds(handle)
            if (!idsRes.isSuccess) continue
            val uniqueIds = idsRes.data.orEmpty().map { it.enrollNumber }.distinct()
            existingCards.addAll(uniqueIds)
            // Chỉ đọc tên khi chọn 1 máy đơn để tối ưu tốc độ tải
            if (targetHandles.size == 1) {
                for (id in uniqueIds) {
                    val n = deviceGateway.getUserName(handle, id).data?.trim().orEmpty()
                    if (n.isNotEmpty()) existingNames.add(n)
                }
            }
        }

        Result.success(
            MembersTreeData(
                classes = classes,
                members = students,
                existingCardIdsOnTarget = existingCards,
                existingNamesOnTarget = existingNames,
            )
        )
    }
}
