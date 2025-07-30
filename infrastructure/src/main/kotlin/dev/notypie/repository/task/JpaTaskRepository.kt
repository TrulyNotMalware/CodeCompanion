package dev.notypie.repository.task

import dev.notypie.repository.task.schema.TaskSchema
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface JpaTaskRepository: JpaRepository<TaskSchema, Long> {
}