package dev.notypie.repository

import dev.notypie.domain.meet.entity.enums.MeetingReminderStatus
import dev.notypie.domain.standup.entity.enums.DispatchStatus
import dev.notypie.domain.standup.entity.enums.SessionStatus
import dev.notypie.repository.cve.JpaCveDeliveryRepository
import dev.notypie.repository.cve.JpaCveEventRepository
import dev.notypie.repository.cve.schema.CveDeliveryStatus
import dev.notypie.repository.cve.schema.CveSummaryStatus
import dev.notypie.repository.meeting.JpaMeetingReminderRepository
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.schema.MessageStatus
import dev.notypie.repository.standup.JpaSessionDispatchRepository
import dev.notypie.repository.standup.JpaStandupSessionRepository
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AssignableTypeFilter
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository

private val QUOTED_CONSTANT = Regex("""'([A-Z][A-Z_]*)'""")

private val STATUS_ENUM_BY_REPOSITORY: Map<Class<*>, Class<out Enum<*>>> =
    mapOf(
        MessageOutboxRepository::class.java to MessageStatus::class.java,
        JpaCveEventRepository::class.java to CveSummaryStatus::class.java,
        JpaCveDeliveryRepository::class.java to CveDeliveryStatus::class.java,
        JpaMeetingReminderRepository::class.java to MeetingReminderStatus::class.java,
        JpaSessionDispatchRepository::class.java to DispatchStatus::class.java,
        JpaStandupSessionRepository::class.java to SessionStatus::class.java,
    )

private fun Class<*>.nativeQueryConstants(): Map<String, Set<String>> =
    declaredMethods
        .mapNotNull { method ->
            method.getAnnotation(Query::class.java)?.takeIf { it.nativeQuery }?.let { query ->
                method.name to QUOTED_CONSTANT.findAll(input = query.value).map { it.groupValues[1] }.toSet()
            }
        }.filter { (_, constants) -> constants.isNotEmpty() }
        .toMap()

private fun repositoryInterfaces(): List<Class<*>> =
    object : ClassPathScanningCandidateComponentProvider(false) {
        override fun isCandidateComponent(beanDefinition: AnnotatedBeanDefinition): Boolean =
            beanDefinition.metadata.isInterface
    }.apply { addIncludeFilter(AssignableTypeFilter(Repository::class.java)) }
        .findCandidateComponents("dev.notypie.repository")
        .map { Class.forName(it.beanClassName) }

class NativeQueryStatusLiteralTest :
    StringSpec({
        "every quoted constant in a native query names a constant of that repository's status enum" {
            val unknown =
                STATUS_ENUM_BY_REPOSITORY.flatMap { (repository, statusEnum) ->
                    val names = statusEnum.enumConstants.map { it.name }.toSet()
                    repository.nativeQueryConstants().flatMap { (method, constants) ->
                        (constants - names).map {
                            "${repository.simpleName}.$method: '$it' is not a ${statusEnum.simpleName}"
                        }
                    }
                }
            unknown.shouldBeEmpty()
        }

        "every repository whose native queries quote constants is checked above" {
            val quoting = repositoryInterfaces().filter { it.nativeQueryConstants().isNotEmpty() }.toSet()
            quoting shouldBe STATUS_ENUM_BY_REPOSITORY.keys
        }
    })
