package dev.notypie.repository.task.schema

import com.fasterxml.jackson.annotation.JsonProperty
import dev.notypie.domain.command.entity.context.form.RequestMeetingContextResult
import dev.notypie.domain.command.entity.context.form.RequestTaskContextResult
import dev.notypie.repository.meeting.schema.MeetingSchema
import dev.notypie.repository.meeting.schema.ParticipantsSchema
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime
import java.util.UUID

@Entity(name = "tasks")
class TaskSchema(
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id")
    val id: Long = 0L,

    @field:Column(name = "idempotency_key", unique = true, nullable = false)
    val idempotencyKey: UUID,

    @field:Column(name = "title", nullable = false)
    val title: String,

    @field:Column(name = "description")
    val description: String? = null,

    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "taskStatus")
    val taskStatus: TaskStatus = TaskStatus.NOT_STARTED,

    @field:OneToMany(
        mappedBy = "task",
        fetch = FetchType.LAZY,
        orphanRemoval = false,
        cascade = [CascadeType.MERGE, CascadeType.PERSIST]
    )
    val assignees: MutableList<AssigneesSchema> = mutableListOf(),

    @field:Column(name = "due_date")
    val dueDate: LocalDateTime? = null,

    @field:Column(name = "channel", nullable = false)
    val channel: String,

    @field:CreationTimestamp
    @field:JsonProperty("created_at")
    @field:Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    // ?= null : consistency 목적
    @field:UpdateTimestamp
    @field:Column(name = "updated_at")
    val updatedAt: LocalDateTime? = null

    // id, title, description, status, due_date, created_by
)

@Entity(name = "task_assignees")
class AssigneesSchema(
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0L,

    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "task_id")
    val task: TaskSchema,

    @field:Column(name = "user_id", nullable = false)
    val userId: String,

    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "role", nullable = false)
    val role: AssigneeRole,

    @field:CreationTimestamp
    @field:JsonProperty("created_at")
    @field:Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @field:UpdateTimestamp
    @field:Column(name = "updated_at")
    val updatedAt: LocalDateTime? = null
)

fun RequestTaskContextResult.newTask(
    description: String? = null,
    dueDate: LocalDateTime? = null,
    taskStatus: TaskStatus = TaskStatus.NOT_STARTED,
): TaskSchema {
    val taskSchema = TaskSchema(
        idempotencyKey = this.idempotencyKey,
        title = this.title,
        description = description,
        taskStatus = taskStatus,
        dueDate = dueDate,
        channel = this.channel
    )
    val assignees = this.assignees.map {
        AssigneesSchema(
            task = taskSchema,
            userId = it,
            role = AssigneeRole.CONTRIBUTOR,
        )
    }
    taskSchema.assignees.addAll(assignees)
    return taskSchema
}