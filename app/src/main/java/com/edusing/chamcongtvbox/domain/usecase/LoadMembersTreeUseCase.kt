package com.edusing.chamcongtvbox.domain.usecase

import com.edusing.chamcongtvbox.domain.model.MembersTreeData
import com.edusing.chamcongtvbox.domain.protocol.FkDeviceGateway
import com.edusing.chamcongtvbox.domain.protocol.HikvisionControlGateway
import com.edusing.chamcongtvbox.domain.repository.ConfigRepository
import com.edusing.chamcongtvbox.domain.repository.MemberRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Tải song song danh sách lớp/phòng ban và học sinh/nhân viên từ máy chủ (tự làm mới token 1 lần nếu hết hạn),
 * đồng thời đọc danh sách mã đang có trên máy chấm công đích (giữ nguyên mã gốc `"001"`, `"NV01"` cho Hikvision).
 */
class LoadMembersTreeUseCase(
    private val configRepository: ConfigRepository,
    private val memberRepository: MemberRepository,
    private val validateCredentialsUseCase: ValidateCredentialsUseCase,
    private val deviceGateway: FkDeviceGateway,
    private val hikControl: HikvisionControlGateway? = null,
) {
    suspend operator fun invoke(targetHandles: List<Int>): Result<MembersTreeData> =
        loadInternal(targetHandles.map { it to null })

    suspend fun loadForTargets(targets: List<ConnectedDeviceHandle>): Result<MembersTreeData> =
        loadInternal(targets.map { it.handle to it })

    private suspend fun loadInternal(targets: List<Pair<Int, ConnectedDeviceHandle?>>): Result<MembersTreeData> = coroutineScope {
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
        val existingEmpNos = HashSet<String>()
        val existingNames = HashSet<String>()
        val ctrl = hikControl

        for ((handle, connected) in targets) {
            if (connected != null && connected.device.isHikvision && ctrl != null) {
                val users = ctrl.fetchDetailedUsers(connected.device).getOrNull().orEmpty()
                for (u in users) {
                    existingEmpNos.add(u.displayCardId)
                    u.displayCardId.toIntOrNull()?.let { existingCards.add(it) }
                    if (u.deviceName.isNotBlank()) existingNames.add(u.deviceName)
                }
                continue
            }
            val idsRes = deviceGateway.readAllUserIds(handle)
            if (!idsRes.isSuccess) continue
            val uniqueIds = idsRes.data.orEmpty().map { it.enrollNumber }.distinct()
            existingCards.addAll(uniqueIds)
            uniqueIds.forEach { existingEmpNos.add(it.toString()) }
            if (targets.size == 1) {
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
                existingEmployeeNosOnTarget = existingEmpNos,
            )
        )
    }
}
